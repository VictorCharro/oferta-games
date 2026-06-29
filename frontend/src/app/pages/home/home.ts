import { Component, OnInit } from '@angular/core';
import { GameService, TopDeal, GameSummary } from '../../services/game';

@Component({
  selector: 'app-home',
  standalone: false,
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home implements OnInit {
  topDeals: TopDeal[] = [];
  recentDeals: TopDeal[] = [];
  featuredIndex = 0;
  loading = true;

  constructor(private gameService: GameService) {}

  ngOnInit() {
    this.gameService.getTopDeals(20).subscribe({
      next: (deals) => {
        this.topDeals = deals.slice(0, 5);
        this.recentDeals = deals.slice(5, 10);
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  get featured(): TopDeal | null {
    return this.topDeals[this.featuredIndex] ?? null;
  }

  prevFeatured() {
    this.featuredIndex = (this.featuredIndex - 1 + this.topDeals.length) % this.topDeals.length;
  }

  nextFeatured() {
    this.featuredIndex = (this.featuredIndex + 1) % this.topDeals.length;
  }

  formatPrice(price: number): string {
    return price.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  toSummary(deal: TopDeal): GameSummary {
    return { slug: deal.slug, title: deal.title, coverUrl: deal.coverUrl, minPrice: deal.price };
  }
}
