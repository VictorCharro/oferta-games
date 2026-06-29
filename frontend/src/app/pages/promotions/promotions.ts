import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { GameService, TopDeal } from '../../services/game';
import { isDlc } from '../../services/filters';

@Component({
  selector: 'app-promotions',
  standalone: false,
  templateUrl: './promotions.html',
  styleUrl: './promotions.scss',
})
export class Promotions implements OnInit {
  deals: TopDeal[] = [];
  loading = true;

  constructor(private gameService: GameService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.gameService.getTopDeals(50).subscribe({
      next: (data) => {
        this.deals = data.filter(d => Number(d.discountPct) < 100 && !isDlc(d.title));
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
