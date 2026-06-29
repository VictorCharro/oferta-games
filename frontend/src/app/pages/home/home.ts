import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { GameService, TopDeal, GameSummary } from '../../services/game';
import { isDlc } from '../../services/filters';

@Component({
  selector: 'app-home',
  standalone: false,
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home implements OnInit {
  topDeals: TopDeal[] = [];
  recentDeals: TopDeal[] = [];
  dlcDeals: TopDeal[] = [];
  featuredIndex = 0;
  loading = true;

  constructor(private gameService: GameService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.gameService.getTopDeals(50, 'rank').subscribe({
      next: (deals) => {
        const paid = deals.filter(d => Number(d.discountPct) < 100);
        const games = paid.filter(d => !isDlc(d.title));
        this.topDeals = games.slice(0, 5);
        this.recentDeals = games.slice(5, 10);
        this.dlcDeals = paid.filter(d => isDlc(d.title)).slice(0, 5);
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.cdr.detectChanges(); }
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

  isDlc(title: string): boolean { return isDlc(title); }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  toSummary(deal: TopDeal): GameSummary {
    return { slug: deal.slug, title: deal.title, coverUrl: deal.coverUrl, minPrice: deal.price };
  }
}
