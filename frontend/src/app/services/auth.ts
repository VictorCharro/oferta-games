import { Injectable, inject } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { BehaviorSubject, filter } from 'rxjs';
import { supabase } from './supabase';
import type { User, Session } from '@supabase/supabase-js';
import { PerfisService } from './perfis';

const CHAVE_RECUPERACAO = 'oferta-games-recuperacao-pendente';

/**
 * Chave da foto enviada pelo usuario no user_metadata do Supabase. NAO pode ser `avatar_url`: a cada
 * login por Google/Discord o Supabase regrava os campos do provedor (avatar_url, name, picture...)
 * no user_metadata, e a foto personalizada voltava a ser a do Google (bug relatado em 26/09/2026).
 */
export const CHAVE_AVATAR_PERSONALIZADO = 'avatar_personalizado';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private _user = new BehaviorSubject<User | null>(null);
  private _avatar = new BehaviorSubject<string>('');
  private readonly perfis = inject(PerfisService);
  /** Usuarios cuja foto ja foi conferida no perfil nesta visita (getSession e onAuthStateChange chamam os dois). */
  private readonly avatarConferido = new Set<string>();
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
      if (event === 'PASSWORD_RECOVERY') {
        this.marcarRecuperacaoPendente(true);
        this.router.navigate(['/redefinir-senha']);
      }
      if (event === 'SIGNED_OUT') this.marcarRecuperacaoPendente(false);
    });

    // Enquanto a recuperacao nao terminar, o site prende a pessoa na tela de nova senha. O link do
    // e-mail ja cria sessao (e assim que o Supabase permite trocar a senha), entao sem isso quem
    // abrisse o link entrava na conta e podia navegar sem nunca definir senha nova — e o link do
    // e-mail viraria um atalho de login permanente. Saidas: salvar a senha ou sair da conta.
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(evento => {
        if (this.recuperacaoPendente && !evento.urlAfterRedirects.startsWith('/redefinir-senha')) {
          this.router.navigateByUrl('/redefinir-senha');
        }
      });
  }

  /** Recuperacao de senha aberta: sessao criada pelo link do e-mail, senha ainda nao trocada. */
  get recuperacaoPendente(): boolean {
    return typeof window !== 'undefined' && localStorage.getItem(CHAVE_RECUPERACAO) === 'true';
  }

  private marcarRecuperacaoPendente(pendente: boolean) {
    if (typeof window === 'undefined') return;
    try {
      if (pendente) localStorage.setItem(CHAVE_RECUPERACAO, 'true');
      else localStorage.removeItem(CHAVE_RECUPERACAO);
    } catch { /* navegador com storage bloqueado: sem a trava, o fluxo continua funcionando */ }
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
    if (!error) this.marcarRecuperacaoPendente(false);
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
    this.marcarRecuperacaoPendente(false);
    await supabase.auth.signOut();
    this.router.navigate(['/']);
  }

  updateAvatar(value: string) {
    const userId = this.user?.id;
    if (!userId) return;
    localStorage.setItem(`oferta-games-avatar-${userId}`, value);
    this._avatar.next(value);
  }

  /** Foto personalizada primeiro; a do provedor (Google/Discord) so quando a pessoa nunca enviou uma. */
  private loadAvatar(user: User | null) {
    if (!user) { this._avatar.next(''); return; }
    const metadata = user.user_metadata ?? {};
    const personalizado = metadata[CHAVE_AVATAR_PERSONALIZADO];
    this._avatar.next(personalizado || metadata['avatar_url'] || this.avatarLocal(user.id) || '');
    if (!personalizado) void this.recuperarAvatarPersonalizado(user);
  }

  private avatarLocal(userId: string): string | null {
    if (typeof window === 'undefined') return null;
    try { return localStorage.getItem(`oferta-games-avatar-${userId}`); } catch { return null; }
  }

  /**
   * Conserta quem trocou a foto ANTES da chave propria existir: essa foto so ficou em
   * `avatar_url`, que o login pelo Google ja sobrescreveu. A copia que sobrou esta no perfil
   * (profiles.avatar_url, que o login nao toca). Se ela for do nosso bucket, volta pra tela e e
   * gravada na chave nova, entao isto roda uma vez por conta. Sem foto enviada, marca no navegador
   * pra nao perguntar ao backend a cada visita.
   */
  private async recuperarAvatarPersonalizado(user: User) {
    if (typeof window === 'undefined' || this.avatarConferido.has(user.id)) return;
    this.avatarConferido.add(user.id);
    const marcador = `oferta-games-avatar-conferido-${user.id}`;
    try { if (localStorage.getItem(marcador)) return; } catch { /* storage bloqueado: confere mesmo assim */ }
    try {
      const url = (await this.perfis.proprio())?.avatarUrl;
      if (url && url.includes(`/avatars/${user.id}/`)) {
        if (this._user.value?.id === user.id) this._avatar.next(url);
        await supabase.auth.updateUser({ data: { [CHAVE_AVATAR_PERSONALIZADO]: url } });
      } else {
        try { localStorage.setItem(marcador, '1'); } catch { /* sem marcador: so pergunta de novo na proxima visita */ }
      }
    } catch { /* backend fora: fica a foto do provedor e tenta de novo na proxima visita */ }
  }
}
