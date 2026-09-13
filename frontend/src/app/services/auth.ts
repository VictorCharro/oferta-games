import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { supabase } from './supabase';
import type { User, Session } from '@supabase/supabase-js';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private _user = new BehaviorSubject<User | null>(null);
  private _avatar = new BehaviorSubject<string>('');
  private readonly sessaoInicial: Promise<void>;
  user$ = this._user.asObservable();
  avatar$ = this._avatar.asObservable();

  /**
   * Se a sessão inicial já foi consultada.
   *
   * `isLoggedIn` sozinho não distingue "deslogado" de "ainda não sei" — `_user` começa `null` e o
   * `getSession()` é assíncrono. Quem monta interface a partir do estado de login precisa checar
   * isto antes, senão mostra a versão de visitante por um instante para quem está logado.
   *
   * <b>Fica `false` para sempre no servidor</b>, mesmo depois do `getSession()` responder: a sessão
   * mora no browser do visitante, então o que o servidor obtém é sempre "deslogado" — uma resposta
   * que ele não tem como saber se é verdade. Marcá-la como resolvida faria o SSR renderizar
   * "Entrar / Criar conta" no HTML de todo mundo, que é exatamente a piscada que isto evita.
   */
  private _sessaoResolvida = new BehaviorSubject<boolean>(false);
  sessaoResolvida$ = this._sessaoResolvida.asObservable();
  get sessaoResolvida(): boolean { return this._sessaoResolvida.value; }

  constructor(private router: Router) {
    // Carrega sessão existente
    this.sessaoInicial = supabase.auth.getSession().then(({ data }) => {
      this._user.next(data.session?.user ?? null);
      this.loadAvatar(data.session?.user ?? null);
      if (typeof window !== 'undefined') this._sessaoResolvida.next(true);
    });

    // Escuta mudanças de sessão
    supabase.auth.onAuthStateChange((event, session) => {
      this._user.next(session?.user ?? null);
      this.loadAvatar(session?.user ?? null);
      // Link de "esqueci minha senha": o supabase-js le o token da URL e emite PASSWORD_RECOVERY.
      // Tratado aqui, no servico global, e nao so na pagina /redefinir-senha, porque se a URL de
      // retorno nao estiver na lista permitida do Supabase ele devolve pra raiz do site — e a
      // pessoa precisa cair no formulario de nova senha de qualquer jeito.
      if (event === 'PASSWORD_RECOVERY') this.router.navigate(['/redefinir-senha']);
    });
  }

  get user(): User | null { return this._user.value; }
  get avatarUrl(): string { return this._avatar.value; }
  get isLoggedIn(): boolean { return this._user.value !== null; }

  async aguardarSessaoInicial() {
    await this.sessaoInicial;
  }

  get displayName(): string {
    const u = this._user.value;
    if (!u) return '';
    return u.user_metadata?.['name'] || u.email?.split('@')[0] || 'Usuário';
  }

  async login(email: string, password: string): Promise<string | null> {
    const { error } = await supabase.auth.signInWithPassword({ email, password });
    return error?.message ?? null;
  }

  async register(email: string, password: string, name: string): Promise<string | null> {
    const { error } = await supabase.auth.signUp({
      email,
      password,
      options: { data: { name } }
    });
    return error?.message ?? null;
  }

  /**
   * Envia o e-mail de redefinicao de senha. O Supabase responde igual pra e-mail cadastrado ou nao,
   * e a tela tambem: mensagem neutra, pra ninguem descobrir quais e-mails tem conta.
   */
  async solicitarRedefinicaoSenha(email: string): Promise<string | null> {
    const { error } = await supabase.auth.resetPasswordForEmail(email, {
      redirectTo: `${window.location.origin}/redefinir-senha`,
    });
    return error?.message ?? null;
  }

  /** Nova senha pra sessao de recuperacao aberta pelo link do e-mail. */
  async definirNovaSenha(senha: string): Promise<string | null> {
    const { error } = await supabase.auth.updateUser({ password: senha });
    return error?.message ?? null;
  }

  async reenviarConfirmacao(email: string): Promise<string | null> {
    const { error } = await supabase.auth.resend({
      type: 'signup',
      email,
      options: { emailRedirectTo: window.location.origin },
    });
    return error?.message ?? null;
  }

  // destino: caminho interno ja validado (ver destinoSeguro em login.ts). Se ele nao estiver na
  // lista de URLs permitidas do Supabase, o Supabase volta pra raiz — nunca pra fora do site.
  async loginWithGoogle(destino = '/') {
    await supabase.auth.signInWithOAuth({
      provider: 'google',
      options: { redirectTo: window.location.origin + destino }
    });
  }

  async loginWithDiscord(destino = '/') {
    await supabase.auth.signInWithOAuth({
      provider: 'discord',
      options: { redirectTo: window.location.origin + destino }
    });
  }

  async logout() {
    await supabase.auth.signOut();
    this.router.navigate(['/']);
  }

  updateAvatar(value: string) {
    const userId = this.user?.id;
    if (!userId) return;
    localStorage.setItem(`oferta-games-avatar-${userId}`, value);
    this._avatar.next(value);
  }

  private loadAvatar(user: User | null) {
    this._avatar.next(user ? user.user_metadata?.['avatar_url'] || localStorage.getItem(`oferta-games-avatar-${user.id}`) || '' : '');
  }
}
