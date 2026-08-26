import { Component, Input } from '@angular/core';
import { PontoHistoricoPreco } from '../../services/game';

let proximoId = 0;

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

  readonly gradientId = `priceAreaGradient-${proximoId++}`;
  hoveredIndex: number | null = null;

  get temHistoricoSuficiente(): boolean {
    return this.pontos.length >= 2;
  }

  get pontosXY(): { x: number; y: number; price: number; data: Date }[] {
    const pontos = this.pontos;
    if (pontos.length < 2) return [];
    const precos = pontos.map(p => Number(p.price));
    const min = Math.min(...precos);
    const max = Math.max(...precos);
    const faixa = max - min || 1;
    const paddingX = 8;
    const paddingTop = 16;
    const paddingBottom = 28;
    const larguraUtil = this.chartWidth - paddingX * 2;
    const alturaUtil = this.chartHeight - paddingTop - paddingBottom;
    return pontos.map((ponto, i) => ({
      x: paddingX + (i / (pontos.length - 1)) * larguraUtil,
      y: paddingTop + alturaUtil - ((Number(ponto.price) - min) / faixa) * alturaUtil,
      price: Number(ponto.price),
      data: new Date(ponto.capturadoEm),
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

  get rotulosEixoX(): string[] {
    const pts = this.pontosXY;
    if (pts.length < 2) return [];
    const indices = [...new Set([0, Math.floor((pts.length - 1) / 2), pts.length - 1])];
    return indices.map(i => pts[i].data.toLocaleDateString('pt-BR', { day: 'numeric', month: 'short' }));
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

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  formatDataCurta(data: Date): string {
    return data.toLocaleDateString('pt-BR', { day: 'numeric', month: 'short', year: 'numeric' });
  }
}
