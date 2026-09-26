import { Injectable, afterNextRender } from '@angular/core';

/**
 * Diz se a primeira tela da visita ja foi pintada no navegador (26/09/2026).
 *
 * <p>Animacao de entrada so pode rodar em conteudo que nasce DEPOIS disso. O que chega pronto do
 * SSR e hidratado ja esta na tela: zerar pra animar de novo seria uma piscada. Ja o que nasce
 * numa navegacao interna (clicar no perfil de alguem, trocar de aba) nunca foi visto e pode entrar
 * animado.
 *
 * <p>Instanciado pelo App na inicializacao, pra o afterNextRender contar a partir da primeira tela.
 * No servidor o afterNextRender nao roda, entao jaPintou fica false e nada anima.
 */
@Injectable({ providedIn: 'root' })
export class PrimeiraPintura {
  jaPintou = false;

  constructor() {
    afterNextRender(() => { this.jaPintou = true; });
  }
}
