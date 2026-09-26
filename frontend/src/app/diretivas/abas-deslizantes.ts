import { AfterViewInit, Directive, ElementRef, OnDestroy, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

/**
 * Barra de abas com um indicador unico que DESLIZA ate a aba escolhida (26/09/2026), no lugar do
 * sublinhado que pulava de uma aba pra outra. Uso: `appAbasDeslizantes` no container das abas; a aba
 * ativa e o filho direto com a classe `active` (o que as paginas ja fazem).
 *
 * <p>Nao precisa de estado da pagina: um MutationObserver percebe quando a classe `active` muda de
 * botao (e quando uma aba entra ou sai por *ngIf), e um ResizeObserver reposiciona quando a barra
 * muda de largura. A aparencia (altura, recuo, cantos, brilho) vem de variaveis CSS de cada pagina,
 * ver `.aba-indicador` em styles.scss.
 *
 * <p>So no navegador. No HTML do servidor cada aba ainda desenha o proprio sublinhado; a diretiva
 * poe a classe `abas-deslizantes` (que esconde esses sublinhados) e o indicador no mesmo lugar, sem
 * salto visivel. A primeira posicao e aplicada sem transicao, senao o indicador entraria deslizando
 * a partir da esquerda ao abrir a pagina.
 *
 * <p>Nada aqui depende de requestAnimationFrame: ele nao dispara com a aba do navegador em segundo
 * plano, e a primeira versao ficava com o indicador parado. Os observers ja agrupam as mudancas.
 */
@Directive({ selector: '[appAbasDeslizantes]', standalone: true })
export class AbasDeslizantes implements AfterViewInit, OnDestroy {
  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly noNavegador = isPlatformBrowser(inject(PLATFORM_ID));
  private indicador?: HTMLSpanElement;
  private observadorClasses?: MutationObserver;
  private observadorTamanho?: ResizeObserver;

  ngAfterViewInit() {
    if (!this.noNavegador) return;
    // offsetLeft das abas e medido a partir do container: ele precisa ser o offsetParent.
    if (getComputedStyle(this.el).position === 'static') this.el.style.position = 'relative';

    this.indicador = document.createElement('span');
    this.indicador.className = 'aba-indicador sem-transicao';
    this.indicador.setAttribute('aria-hidden', 'true');
    this.el.appendChild(this.indicador);
    this.el.classList.add('abas-deslizantes');
    this.posicionar();
    // Le o layout pra forcar o navegador a aplicar a posicao inicial AGORA, ainda sem transicao; so
    // entao liga a transicao. Assim o indicador nasce no lugar em vez de deslizar a partir da esquerda.
    void this.indicador.offsetWidth;
    this.indicador.classList.remove('sem-transicao');

    this.observadorClasses = new MutationObserver(() => this.posicionar());
    this.observadorClasses.observe(this.el, { subtree: true, childList: true, attributes: true, attributeFilter: ['class'] });
    // Navegador sem ResizeObserver (raro) so perde o reposicionamento ao redimensionar a barra.
    if (typeof ResizeObserver !== 'undefined') {
      this.observadorTamanho = new ResizeObserver(() => this.posicionar());
      this.observadorTamanho.observe(this.el);
    }
  }

  ngOnDestroy() {
    // O servidor tambem destroi a view depois de renderizar, e la os observers nunca foram criados.
    // (A versao anterior chamava cancelAnimationFrame aqui e o SSR lancava ReferenceError.)
    if (!this.noNavegador) return;
    this.observadorClasses?.disconnect();
    this.observadorTamanho?.disconnect();
  }

  private posicionar() {
    const indicador = this.indicador;
    if (!indicador) return;
    const ativa = this.el.querySelector<HTMLElement>(':scope > .active');
    if (!ativa) {
      indicador.style.opacity = '0';
      return;
    }
    const recuo = parseFloat(getComputedStyle(this.el).getPropertyValue('--aba-indicador-recuo')) || 0;
    indicador.style.opacity = '1';
    indicador.style.width = `${Math.max(0, ativa.offsetWidth - 2 * recuo)}px`;
    indicador.style.translate = `${ativa.offsetLeft + recuo}px 0`;
  }
}
