import { Component, OnInit, OnDestroy, ChangeDetectorRef, HostListener } from '@angular/core';
import { Subscription } from 'rxjs';
import { GameService, TopDeal, GameSummary } from '../../services/game';
import { FavoritesService } from '../../services/favorites';
import { resolveDlc } from '../../services/filters';
import { PlatformBrand, storeBrand, storePlatforms } from '../../services/store-brand';
import { PreferencesService, UserPreferences } from '../../services/preferences';

export interface DealCardView {
  slug: string;
  title: string;
  coverUrl: string | null;
  discountPct: number;
  price: number | string | null;
  regularPrice: number | string | null;
  storeName?: string | null;
  url?: string | null;
  isDlc?: boolean | null;
}

@Component({
  selector: 'app-home',
  standalone: false,
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home implements OnInit, OnDestroy {
  featuredDeals: TopDeal[] = [];
  famousGames: DealCardView[] = [];
  favoritesDeals: DealCardView[] = [];
  preferredPlatformDeals: DealCardView[] = [];
  freeWeek: DealCardView[] = [];
  topDiscountGames: DealCardView[] = [];
  topDiscountDlcs: DealCardView[] = [];
  featuredIndex = 0;
  loading = true;
  preferredPlatformLabel = '';
  private favSub!: Subscription;
  private autoplayTimer?: ReturnType<typeof setInterval>;
  private readonly autoplayIntervalMs = 6000;

  constructor(
    private gameService: GameService,
    private favoritesService: FavoritesService,
    private preferencesService: PreferencesService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    this.loadPreferredPlatformDeals();
    this.gameService.getTopDeals(100, 'rank').subscribe({
      next: (deals) => {
        const paid = deals.filter(d => Number(d.discountPct) < 100 && !resolveDlc(d.title, d.isDlc) && this.matchesPreferences(d));
        this.featuredDeals = this.shuffle(paid).slice(0, 5);
        this.famousGames = this.shuffle(paid).slice(0, 20).map(d => this.fromTopDeal(d));
        this.loading = false;
        this.startAutoplay();
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.cdr.detectChanges(); }
    });

    this.gameService.getTopDeals(200, 'discount').subscribe({
      next: (deals) => {
        this.freeWeek = deals
          .filter(d => Number(d.discountPct) === 100 && !resolveDlc(d.title, d.isDlc) && this.matchesPreferences(d, true))
          .slice(0, 15)
          .map(d => this.fromTopDeal(d));

        const active = deals.filter(d => Number(d.discountPct) < 100 && this.matchesPreferences(d));
        this.topDiscountGames = active.filter(d => d.rank != null && !resolveDlc(d.title, d.isDlc)).slice(0, 20).map(d => this.fromTopDeal(d));
        this.topDiscountDlcs = active.filter(d => resolveDlc(d.title, d.isDlc)).slice(0, 20).map(d => this.fromTopDeal(d));
        this.cdr.detectChanges();
      }
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
    this.stopAutoplay();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange() {
    if (document.hidden) {
      this.stopAutoplay();
    } else {
      this.startAutoplay();
    }
  }

  prevFeatured() {
    this.goToFeatured((this.featuredIndex - 1 + this.featuredDeals.length) % this.featuredDeals.length);
  }

  nextFeatured() {
    this.goToFeatured((this.featuredIndex + 1) % this.featuredDeals.length);
  }

  goToFeatured(i: number) {
    this.featuredIndex = i;
    this.startAutoplay();
  }

  private startAutoplay() {
    this.stopAutoplay();
    if (this.featuredDeals.length <= 1) return;
    this.autoplayTimer = setInterval(() => {
      this.featuredIndex = (this.featuredIndex + 1) % this.featuredDeals.length;
      this.cdr.detectChanges();
    }, this.autoplayIntervalMs);
  }

  private stopAutoplay() {
    if (this.autoplayTimer) {
      clearInterval(this.autoplayTimer);
      this.autoplayTimer = undefined;
    }
  }

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

  storeLogo(storeName?: string | null): string {
    return storeBrand(storeName).logo;
  }

  platforms(storeName?: string | null, url?: string | null): PlatformBrand[] {
    return storePlatforms(storeName, url);
  }

  private matchesPreferences(deal: TopDeal, free = false): boolean {
    const preferences: UserPreferences = this.preferencesService.preferences;
    const dlcMatches = !preferences.hideDlcs || !resolveDlc(deal.title, deal.isDlc);
    const discountMatches = free || Number(deal.discountPct) >= preferences.minimumDiscount;
    const price = Number(deal.price);
    const priceMatches = preferences.maximumPrice == null || (!Number.isNaN(price) && price <= preferences.maximumPrice);
    return dlcMatches && discountMatches && priceMatches;
  }

  private loadPreferredPlatformDeals() {
    const preferences = this.preferencesService.preferences;
    if (preferences.preferredPlatform === 'all') return;

    this.preferredPlatformLabel = preferences.preferredPlatform === 'xbox' ? 'Xbox' : 'PC';
    this.gameService.getGames(0, 20, {
      sort: 'discount',
      platform: preferences.preferredPlatform,
      type: preferences.hideDlcs ? 'game' : 'all',
      minDiscount: preferences.minimumDiscount,
      maxPrice: preferences.maximumPrice,
    }).subscribe({
      next: games => {
        this.preferredPlatformDeals = games.map(game => this.fromGameSummary(game));
        this.cdr.detectChanges();
      },
    });
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
      url: d.url,
      isDlc: d.isDlc,
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
      storeName: g.storeName ?? null,
      url: g.url ?? null,
      isDlc: g.isDlc,
    };
  }
}
