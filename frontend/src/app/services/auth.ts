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
    supabase.auth.onAuthStateChange((_event, session) => {
      this._user.next(session?.user ?? null);
      this.loadAvatar(session?.user ?? null);
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

  async loginWithGoogle() {
    await supabase.auth.signInWithOAuth({
      provider: 'google',
      options: { redirectTo: window.location.origin }
    });
  }

  async loginWithDiscord() {
    await supabase.auth.signInWithOAuth({
      provider: 'discord',
      options: { redirectTo: window.location.origin }
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
