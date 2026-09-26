import { Directive, ElementRef, Input, OnInit, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

/** Tempo de sobra pra ultima entrada da fila terminar (8 x 50 ms de atraso + 300 ms de animacao). */
const DURACAO_TOTAL_MS = 1000;

/**
 * Filhos do elemento entram um depois do outro (26/09/2026). Uso:
 * `<section [appEntradaCascata]="condicao">` e, em cada filho, `[style.--i]="indice"`.
 * O CSS da cascata fica no styles.scss (`.cascata-entrar > *`).
 *
 * <p>A condicao e lida so quando o elemento nasce, igual ao EntradaAnimada: ligar depois nao
 * reanima o que ja esta na tela. A classe sai sozinha depois da animacao, pra um filho novo
 * (bloco adicionado no editor) nao entrar com atraso.
 */
@Directive({ selector: '[appEntradaCascata]', standalone: true })
export class EntradaCascata implements OnInit {
  @Input('appEntradaCascata') ativa: boolean | '' = true;

  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly noNavegador = isPlatformBrowser(inject(PLATFORM_ID));

  ngOnInit() {
    if (!this.noNavegador || this.ativa === false) return;
    this.el.classList.add('cascata-entrar');
    setTimeout(() => this.el.classList.remove('cascata-entrar'), DURACAO_TOTAL_MS);
  }
}
