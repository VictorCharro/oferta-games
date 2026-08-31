import { Component, HostBinding, Input } from '@angular/core';
import { PontoHistoricoPreco } from '../../services/game';

let proximoId = 0;

function mesmoDia(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate();
}

/**
 * Se vale desenhar o gráfico para esses pontos.
 *
 * Quem exibe o card precisa usar isto (e não `pontos.length`) pra decidir, senão mostra um card
 * vazio nos casos em que o gráfico não renderiza.
 *
 * Um ponto só já rende gráfico — como o backend só grava em mudança, um ponto significa "o preço
 * está nesse valor desde aquela data", e a linha reta até hoje diz exatamente isso. A exceção é o
 * ponto gravado hoje: aí ainda não há intervalo nenhum pra mostrar.
 */
export function temHistoricoParaGrafico(pontos: PontoHistoricoPreco[]): boolean {
  if (!pontos?.length) return false;
  if (pontos.length >= 2) return true;
  return !mesmoDia(new Date(pontos[0].capturadoEm), new Date());
}

@Component({
  selector: 'app-price-history-chart',
  standalone: false,
  templateUrl: './price-history-chart.html',
  styleUrl: './price-history-chart.scss',
})
export class PriceHistoryChart {
  @Input() pontos: PontoHistoricoPreco[] = [];
  @Input() chartWidth = 700;
  @Input() chartHeight = 220;
  @Input() mostrarEixoX = true;
  // Versao menor pra caber no carrossel/banner, sem competir de tamanho com o resto do conteudo.
  @HostBinding('class.compacto') @Input() compacto = false;

  readonly gradientId = `priceAreaGradient-${proximoId++}`;
  hoveredIndex: number | null = null;

  get temHistoricoSuficiente(): boolean {
    return this.serie.length >= 2;
  }

  /**
   * Pontos gravados mais um ponto projetado em "agora", quando a última mudança não foi hoje.
   *
   * O backend só grava em mudança de preço, então o intervalo entre o último ponto e agora é
   * exatamente o tempo em que o preço ficou parado — informação real, não extrapolação. Sem esse
   * ponto a linha morre no meio do gráfico e o eixo mostra uma data velha, mesmo com o preço
   * valendo hoje.
   *
   * É também o que dá gráfico para jogo com um único ponto gravado: um ponto não significa "sem
   * dado", significa "o preço não mudou desde aquela data".
   */
  private get serie(): { price: number; data: Date; projetado: boolean }[] {
    const gravados = this.pontos.map(ponto => ({
      price: Number(ponto.price),
      data: new Date(ponto.capturadoEm),
      projetado: false,
    }));
    if (!gravados.length) return [];

    const ultimo = gravados[gravados.length - 1];
    const agora = new Date();
    if (!mesmoDia(ultimo.data, agora)) {
      gravados.push({ price: ultimo.price, data: agora, projetado: true });
    }
    return gravados;
  }

  get pontosXY(): { x: number; y: number; price: number; data: Date; projetado: boolean }[] {
    const serie = this.serie;
    if (serie.length < 2) return [];

    const precos = serie.map(p => p.price);
    const min = Math.min(...precos);
    const max = Math.max(...precos);
    const faixa = max - min;

    const paddingX = 8;
    const paddingTop = 16;
    const paddingBottom = 28;
    const larguraUtil = this.chartWidth - paddingX * 2;
    const alturaUtil = this.chartHeight - paddingTop - paddingBottom;

    // Eixo X proporcional ao tempo, não ao índice: senão um período longo sem alteração ocuparia
    // a mesma largura que duas mudanças seguidas no mesmo dia, e o ponto projetado em "agora"
    // ganharia uma largura arbitrária.
    const inicio = serie[0].data.getTime();
    const intervalo = serie[serie.length - 1].data.getTime() - inicio || 1;

    return serie.map(ponto => ({
      x: paddingX + ((ponto.data.getTime() - inicio) / intervalo) * larguraUtil,
      // Preço estável o período todo não tem faixa pra normalizar; centraliza em vez de colar a
      // linha na base do quadro.
      y: faixa === 0
        ? paddingTop + alturaUtil / 2
        : paddingTop + alturaUtil - ((ponto.price - min) / faixa) * alturaUtil,
      price: ponto.price,
      data: ponto.data,
      projetado: ponto.projetado,
    }));
  }

  get linhaPath(): string {
    return this.pontosXY.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ');
  }

  get areaPath(): string {
    const pts = this.pontosXY;
    if (!pts.length) return '';
    const base = this.chartHeight - 28;
    return `${this.linhaPath} L ${pts[pts.length - 1].x.toFixed(1)} ${base} L ${pts[0].x.toFixed(1)} ${base} Z`;
  }

  get ultimoPonto() {
    const pts = this.pontosXY;
    return pts.length ? pts[pts.length - 1] : null;
  }

  /**
   * Três marcas de data: início, meio e fim do intervalo.
   *
   * Interpola pelo tempo em vez de pegar o ponto do meio da lista, porque o eixo X é temporal —
   * usar o índice deixaria o rótulo do meio desalinhado do que ele marca.
   */
  get rotulosEixoX(): string[] {
    const pts = this.pontosXY;
    if (pts.length < 2) return [];
    const inicio = pts[0].data.getTime();
    const fim = pts[pts.length - 1].data.getTime();
    return [inicio, inicio + (fim - inicio) / 2, fim]
      .map(t => new Date(t).toLocaleDateString('pt-BR', { day: 'numeric', month: 'short' }));
  }

  get pontoHover() {
    if (this.hoveredIndex == null) return null;
    return this.pontosXY[this.hoveredIndex] ?? null;
  }

  get pontoHoverAlinhamento(): 'start' | 'center' | 'end' {
    const ph = this.pontoHover;
    if (!ph) return 'center';
    const percentual = (ph.x / this.chartWidth) * 100;
    if (percentual > 82) return 'end';
    if (percentual < 18) return 'start';
    return 'center';
  }

  get pontoHoverPosicaoVertical(): 'acima' | 'abaixo' {
    const ph = this.pontoHover;
    if (!ph) return 'acima';
    const percentual = (ph.y / this.chartHeight) * 100;
    return percentual < 25 ? 'abaixo' : 'acima';
  }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  formatDataCurta(data: Date): string {
    return data.toLocaleDateString('pt-BR', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  /**
   * Texto de data do tooltip. O ponto projetado não é uma coleta — deixa explícito que ali o preço
   * só continua o mesmo, em vez de fingir que houve uma medição hoje.
   */
  rotuloDataTooltip(ponto: { data: Date; projetado: boolean }): string {
    return ponto.projetado ? 'hoje · sem alteração' : this.formatDataCurta(ponto.data);
  }
}
