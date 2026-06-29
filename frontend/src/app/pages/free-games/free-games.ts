import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { GameService, TopDeal } from '../../services/game';

@Component({
  selector: 'app-free-games',
  standalone: false,
  templateUrl: './free-games.html',
  styleUrl: './free-games.scss',
})
export class FreeGames implements OnInit {
  games: TopDeal[] = [];
  loading = true;

  constructor(private gameService: GameService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.gameService.getTopDeals(100).subscribe({
      next: (deals) => {
        this.games = deals.filter(d => Number(d.discountPct) === 100);
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.cdr.detectChanges(); }
    });
  }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }
}
