import { Directive, ElementRef, Input, OnInit, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';

/**
 * Fade de entrada do conteudo de uma aba (26/09/2026): poe a classe `aba-conteudo-entrar` no
 * elemento quando ele nasce e tira quando a animacao acaba. Uso: `[appEntradaAnimada]="condicao"`.
 *
 * <p>Por que nao o `animate.enter` nativo do Angular: ele so termina de inserir o elemento no
 * proximo ciclo completo da aplicacao. Conteudo criado por um `detectChanges()` local (o padrao do
 * projeto depois de carregar dados) ficava fora da tela ate a pessoa clicar ou digitar alguma coisa.
 * Aqui a classe entra junto com o elemento e a animacao de CSS roda sozinha.
 *
 * <p>So no navegador: no SSR nao ha animacao pra rodar.
 */
@Directive({ selector: '[appEntradaAnimada]', standalone: true })
export class EntradaAnimada implements OnInit {
  /** false = entra sem animacao (ex.: antes da primeira renderizacao, ver animarTrocaDeAba). */
  @Input('appEntradaAnimada') ativa: boolean | '' = true;

  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly noNavegador = isPlatformBrowser(inject(PLATFORM_ID));

  ngOnInit() {
    if (!this.noNavegador || this.ativa === false) return;
    this.el.classList.add('aba-conteudo-entrar');
    const terminar = (evento: AnimationEvent) => {
      // animationend borbulha: animacao de um filho nao pode encerrar a deste elemento.
      if (evento.target !== this.el) return;
      this.el.classList.remove('aba-conteudo-entrar');
      this.el.removeEventListener('animationend', terminar);
    };
    this.el.addEventListener('animationend', terminar);
  }
}
