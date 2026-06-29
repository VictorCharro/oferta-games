import { Component, Input } from '@angular/core';
import { GameSummary } from '../../services/game';

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
    return this.discountPct ?? 0;
  }

  formatPrice(price: number | null): string {
    if (!price) return '—';
    return price.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  toggleFavorite(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    this.favorited = !this.favorited;
  }
}
