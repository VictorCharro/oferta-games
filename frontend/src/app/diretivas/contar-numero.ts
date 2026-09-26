import { Directive, ElementRef, Input, OnChanges, OnDestroy, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { PrimeiraPintura } from '../services/primeira-pintura';

/** Duracao da contagem. Curta de proposito: e um enfeite, o numero nao pode demorar a ser lido. */
const DURACAO_MS = 900;

/**
 * Escreve um numero no elemento contando de 0 ate ele na primeira vez que aparece (26/09/2026).
 * Uso: `<strong [appContarNumero]="valor" sufixo="h"></strong>` — o elemento fica vazio no
 * template, porque quem escreve o texto e a diretiva. null vira "--".
 *
 * <p>Conta so quando o numero nasce depois da primeira tela (ver PrimeiraPintura): no perfil que
 * chegou pronto do SSR o numero ja esta na tela e fica como esta. Tambem nao conta com a aba em
 * segundo plano (onde requestAnimationFrame nao roda e o numero ficaria
 * parado no 0). Depois da primeira vez, mudanca de valor so troca o texto.
 */
@Directive({ selector: '[appContarNumero]', standalone: true })
export class ContarNumero implements OnChanges, OnDestroy {
  @Input('appContarNumero') valor: number | null | undefined;
  @Input() sufixo = '';

  private readonly el: HTMLElement = inject(ElementRef<HTMLElement>).nativeElement;
  private readonly noNavegador = isPlatformBrowser(inject(PLATFORM_ID));
  private readonly pintura = inject(PrimeiraPintura);
  private jaApareceu = false;
  private quadro?: number;

  ngOnChanges() {
    this.pararContagem();
    const alvo = this.valor;
    if (alvo == null) { this.el.textContent = '--'; return; }
    const podeContar = this.noNavegador && !this.jaApareceu && this.pintura.jaPintou && alvo > 0
      && !document.hidden;
    this.jaApareceu = true;
    if (!podeContar) { this.escrever(alvo); return; }

    const inicio = performance.now();
    const passo = (agora: number) => {
      const t = Math.min((agora - inicio) / DURACAO_MS, 1);
      const suavizado = 1 - Math.pow(1 - t, 3);
      this.escrever(Math.round(alvo * suavizado));
      this.quadro = t < 1 ? requestAnimationFrame(passo) : undefined;
    };
    this.escrever(0);
    this.quadro = requestAnimationFrame(passo);
  }

  ngOnDestroy() {
    this.pararContagem();
  }

  private pararContagem() {
    // Guarda de SSR: cancelAnimationFrame nao existe no Node, e la quadro nunca e preenchido.
    if (this.quadro !== undefined) cancelAnimationFrame(this.quadro);
    this.quadro = undefined;
  }

  private escrever(n: number) {
    this.el.textContent = n.toLocaleString('pt-BR') + this.sufixo;
  }
}
