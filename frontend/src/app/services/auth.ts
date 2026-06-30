import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { supabase } from './supabase';
import type { User, Session } from '@supabase/supabase-js';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private _user = new BehaviorSubject<User | null>(null);
  user$ = this._user.asObservable();

  constructor(private router: Router) {
    // Carrega sessão existente
    supabase.auth.getSession().then(({ data }) => {
      this._user.next(data.session?.user ?? null);
    });

    // Escuta mudanças de sessão
    supabase.auth.onAuthStateChange((_event, session) => {
      this._user.next(session?.user ?? null);
    });
  }

  get user(): User | null { return this._user.value; }
  get isLoggedIn(): boolean { return this._user.value !== null; }

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

  async logout() {
    await supabase.auth.signOut();
    this.router.navigate(['/']);
  }
}
