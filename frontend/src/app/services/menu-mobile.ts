import { Injectable, effect, signal } from '@angular/core';

// document nao existe em Node (SSR); sem essa guarda, o effect derruba a renderizacao com
// ReferenceError assim que o servico e injetado no server.
const isBrowser = typeof document !== 'undefined';

// Estado do menu gaveta (issue #9): sidebar e topbar compartilham isto — o botao de hamburguer
// na topbar chama toggle(), a sidebar le isOpen() pra aplicar a classe .open e cada link de
// navegacao chama close() ao ser clicado, senao o drawer ficaria aberto por cima da pagina nova.
@Injectable({ providedIn: 'root' })
export class MenuMobileService {
  isOpen = signal(false);

  constructor() {
    // Trava o scroll da pagina atras do drawer enquanto ele estiver aberto. So importa em telas
    // <=900px (unico lugar onde o drawer aparece de verdade), mas aplicar sempre e inofensivo: o
    // hamburguer que dispara isOpen() fica escondido via CSS acima de 900px, entao isOpen nunca
    // vira true em desktop na pratica.
    effect(() => {
      if (!isBrowser) return;
      document.body.classList.toggle('menu-mobile-aberto', this.isOpen());
    });
  }

  toggle() {
    this.isOpen.set(!this.isOpen());
  }

  close() {
    this.isOpen.set(false);
  }
}
