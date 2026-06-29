import { Component, OnInit } from '@angular/core';
import { GameService, TopDeal } from '../../services/game';

@Component({
  selector: 'app-promotions',
  standalone: false,
  templateUrl: './promotions.html',
  styleUrl: './promotions.scss',
})
export class Promotions implements OnInit {
  deals: TopDeal[] = [];
  loading = true;

  constructor(private gameService: GameService) {}

  ngOnInit() {
    this.gameService.getTopDeals(50).subscribe({
      next: (data) => { this.deals = data; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }

  formatPrice(price: number): string {
    return price.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }
}
