import { Injectable, signal } from '@angular/core';

// localStorage/document nao existem em Node (SSR); sem essa guarda, so de injetar este servico
// no server ja derruba a renderizacao inteira com ReferenceError.
const isBrowser = typeof window !== 'undefined';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  isDark = signal(true);

  constructor() {
    const saved = isBrowser ? localStorage.getItem('theme') : null;
    this.isDark.set(saved !== 'light');
    this.apply();
  }

  toggle() {
    this.isDark.set(!this.isDark());
    if (isBrowser) localStorage.setItem('theme', this.isDark() ? 'dark' : 'light');
    this.apply();
  }

  private apply() {
    if (isBrowser) document.documentElement.setAttribute('data-theme', this.isDark() ? 'dark' : 'light');
  }
}
