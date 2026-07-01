import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { Subscription } from 'rxjs';
import { GameService, TopDeal, GameSummary } from '../../services/game';
import { FavoritesService } from '../../services/favorites';
import { isDlc } from '../../services/filters';

export interface DealCardView {
  slug: string;
  title: string;
  coverUrl: string | null;
  discountPct: number;
  price: number | string | null;
  regularPrice: number | string | null;
  storeName?: string | null;
}

@Component({
  selector: 'app-home',
  standalone: false,
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home implements OnInit, OnDestroy {
  featuredDeals: TopDeal[] = [];
  favoritesDeals: DealCardView[] = [];
  freeWeek: DealCardView[] = [];
  topDiscountGames: DealCardView[] = [];
  topDiscountDlcs: DealCardView[] = [];
  featuredIndex = 0;
  loading = true;
  private favSub!: Subscription;

  constructor(
    private gameService: GameService,
    private favoritesService: FavoritesService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    this.gameService.getTopDeals(50, 'rank').subscribe({
      next: (deals) => {
        const paid = deals.filter(d => Number(d.discountPct) < 100 && !isDlc(d.title));
        this.featuredDeals = this.shuffle(paid).slice(0, 5);
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.cdr.detectChanges(); }
    });

    this.gameService.getTopDeals(200, 'discount').subscribe({
      next: (deals) => {
        this.freeWeek = deals
          .filter(d => Number(d.discountPct) === 100 && !isDlc(d.title))
          .slice(0, 15)
          .map(d => this.fromTopDeal(d));

        const active = deals.filter(d => Number(d.discountPct) < 100);
        this.topDiscountGames = active.filter(d => d.rank != null && !isDlc(d.title)).slice(0, 20).map(d => this.fromTopDeal(d));
        this.cdr.detectChanges();
      }
    });

    this.gameService.getGames(0, 20, { sort: 'discount', type: 'dlc' }).subscribe(games => {
      this.topDiscountDlcs = games.map(g => this.fromGameSummary(g));
      this.cdr.detectChanges();
    });

    this.favSub = this.favoritesService.list$.subscribe(list => {
      this.favoritesDeals = list
        .map(g => this.fromGameSummary(g))
        .sort((a, b) => b.discountPct - a.discountPct)
        .slice(0, 15);
      this.cdr.detectChanges();
    });
  }

  ngOnDestroy() {
    this.favSub?.unsubscribe();
  }

  get featured(): TopDeal | null {
    return this.featuredDeals[this.featuredIndex] ?? null;
  }

  prevFeatured() {
    this.featuredIndex = (this.featuredIndex - 1 + this.featuredDeals.length) % this.featuredDeals.length;
  }

  nextFeatured() {
    this.featuredIndex = (this.featuredIndex + 1) % this.featuredDeals.length;
  }

  isDlc(title: string): boolean { return isDlc(title); }

  private shuffle<T>(arr: T[]): T[] {
    const a = [...arr];
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    return a;
  }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  private fromTopDeal(d: TopDeal): DealCardView {
    return {
      slug: d.slug,
      title: d.title,
      coverUrl: d.coverUrl,
      discountPct: Number(d.discountPct),
      price: d.price,
      regularPrice: d.regularPrice,
      storeName: d.storeName,
    };
  }

  private fromGameSummary(g: GameSummary): DealCardView {
    const min = Number(g.minPrice);
    const reg = Number(g.regularPrice);
    const pct = reg > 0 && min >= 0 ? Math.round((1 - min / reg) * 100) : 0;
    return {
      slug: g.slug,
      title: g.title,
      coverUrl: g.coverUrl,
      discountPct: pct,
      price: g.minPrice,
      regularPrice: g.regularPrice ?? null,
      storeName: null,
    };
  }
}
