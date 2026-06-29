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
    this.gameService.getTopDeals(200).subscribe({
      next: (deals) => {
        const paid = deals.filter(d => Number(d.discountPct) < 100);
        const allGames = paid.filter(d => !isDlc(d.title));

        // Jogos famosos (rank baixo) com melhor desconto
        const famous = allGames
          .filter(d => d.rank != null && d.rank < 3000)
          .sort((a, b) => Number(b.discountPct) - Number(a.discountPct));

        // Se não tiver famosos suficientes, complementa com o restante
        const fallback = allGames.filter(d => d.rank == null || d.rank >= 3000);
        const games = famous.length >= 10 ? famous : [...famous, ...fallback];

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
