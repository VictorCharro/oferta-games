import { Component, Input } from '@angular/core';
import { GameSummary } from '../../services/game';
import { isDlc } from '../../services/filters';

@Component({
  selector: 'app-game-card',
  standalone: false,
  templateUrl: './game-card.html',
  styleUrl: './game-card.scss',
})
export class GameCard {
  @Input() game!: GameSummary;
  @Input() discountPct?: number;
  @Input() storeName?: string;
  favorited = false;

  get discount(): number {
    if (this.discountPct != null) return this.discountPct;
    const min = Number(this.game?.minPrice);
    const reg = Number(this.game?.regularPrice);
    if (!reg || !min || reg <= min) return 0;
    return Math.round((1 - min / reg) * 100);
  }

  get isDlcGame(): boolean {
    return isDlc(this.game?.title ?? '');
  }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  toggleFavorite(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    this.favorited = !this.favorited;
  }
}
