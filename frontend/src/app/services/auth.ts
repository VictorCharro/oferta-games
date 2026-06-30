import { Injectable } from '@angular/core';
import { Router } from '@angular/router';

export interface User {
  name: string;
  email: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private _user: User | null = null;

  constructor(private router: Router) {
    const saved = localStorage.getItem('og_user');
    if (saved) this._user = JSON.parse(saved);
  }

  get user(): User | null { return this._user; }
  get isLoggedIn(): boolean { return this._user !== null; }

  login(email: string, password: string): boolean {
    // Mock: aceita qualquer email/senha por enquanto
    this._user = { name: email.split('@')[0], email };
    localStorage.setItem('og_user', JSON.stringify(this._user));
    return true;
  }

  logout() {
    this._user = null;
    localStorage.removeItem('og_user');
    this.router.navigate(['/']);
  }
}
