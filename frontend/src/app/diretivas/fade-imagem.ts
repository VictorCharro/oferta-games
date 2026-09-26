import { AfterViewInit, Directive, ElementRef, OnDestroy, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

/**
 * Capa que aparece com fade quando termina de baixar, em vez de surgir de uma vez depois de um
 * buraco vazio no card (bem visivel no celular, onde as capas sao `loading="lazy"`).
 *
 * <p>So esconde imagem que <b>ainda nao carregou</b>, e so no navegador. Capa que ja estava
 * pronta (cache, ou HTML do servidor ja com a imagem baixada) nunca some. Por isso nada disso
 * afeta o SSR nem a primeira pintura: o HTML do servidor sai sem a classe.
 *
 * <p>A checagem fica em {@code ngAfterViewInit}, e nao em {@code ngOnInit}: no {@code ngOnInit}
 * o {@code [src]} do elemento ainda nao foi aplicado, e uma {@code <img>} sem src conta como
 * {@code complete} — a diretiva nunca faria nada.
 *
 * <p>Erro de carregamento tambem revela a imagem (o {@code (error)} do card troca pela capa
 * padrao); sem isso, uma capa quebrada ficaria invisivel pra sempre.
 */
@Directive({ selector: 'img[appFadeImagem]', standalone: true })
export class FadeImagem implements AfterViewInit, OnDestroy {
  private readonly img: HTMLImageElement = inject(ElementRef<HTMLImageElement>).nativeElement;
  private readonly noNavegador = isPlatformBrowser(inject(PLATFORM_ID));
  private readonly revelar = () => this.img.classList.add('carregada');

  ngAfterViewInit() {
    if (!this.noNavegador || this.img.complete) return;
    this.img.classList.add('capa-fade');
    this.img.addEventListener('load', this.revelar);
    this.img.addEventListener('error', this.revelar);
  }

  ngOnDestroy() {
    this.img.removeEventListener('load', this.revelar);
    this.img.removeEventListener('error', this.revelar);
  }
}
