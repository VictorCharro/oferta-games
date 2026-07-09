import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { of, Subscription } from 'rxjs';
import { catchError, distinctUntilChanged, filter, map, switchMap, tap } from 'rxjs/operators';
import { GameService, GameDetail as GameDetailModel, GameSummary, Offer } from '../../services/game';
import { FavoritesService } from '../../services/favorites';
import { AuthService } from '../../services/auth';
import { PlatformBrand, storeBrand, storePlatforms } from '../../services/store-brand';

@Component({
  selector: 'app-game-detail',
  standalone: false,
  templateUrl: './game-detail.html',
  styleUrl: './game-detail.scss',
})
export class GameDetail implements OnInit, OnDestroy {
  game: GameDetailModel | null = null;
  loading = true;
  refreshing = false;
  refreshMsg = '';
  private routeSub?: Subscription;
  private favoriteSub?: Subscription;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private gameService: GameService,
    private favoritesService: FavoritesService,
    private auth: AuthService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    this.favoriteSub = this.favoritesService.slugs$.subscribe(() => this.cdr.detectChanges());
    this.routeSub = this.route.paramMap.pipe(
      map(params => params.get('slug')),
      filter((slug): slug is string => !!slug),
      distinctUntilChanged(),
      tap(() => {
        this.game = null;
        this.loading = true;
        this.refreshing = false;
        this.refreshMsg = '';
        this.cdr.detectChanges();
      }),
      switchMap(slug => this.gameService.getGame(slug).pipe(catchError(() => of(null))))
    ).subscribe(data => {
      this.game = data;
      this.loading = false;
      this.cdr.detectChanges();
    });
  }

  ngOnDestroy() {
    this.routeSub?.unsubscribe();
    this.favoriteSub?.unsubscribe();
  }

  get favorited(): boolean {
    return this.game ? this.favoritesService.isFavorited(this.game.slug) : false;
  }

  toggleFavorite(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    if (!this.game) return;
    if (!this.auth.isLoggedIn) {
      this.router.navigate(['/login']);
      return;
    }
    this.favoritesService.toggle(this.gameSummary(this.game));
  }

  private gameSummary(game: GameDetailModel): GameSummary {
    const bestOffer = game.offers.reduce<Offer | null>(
      (best, offer) => !best || offer.price < best.price ? offer : best,
      null
    );

    return {
      slug: game.slug,
      title: game.title,
      coverUrl: game.coverUrl,
      minPrice: bestOffer?.price ?? null,
      regularPrice: bestOffer?.regularPrice ?? null,
    };
  }

  refresh() {
    if (!this.game || this.refreshing) return;
    this.refreshing = true;
    this.refreshMsg = '';
    this.cdr.detectChanges();
    const slug = this.game.slug;
    this.gameService.refreshGame(slug).subscribe({
      next: (res) => {
        this.refreshMsg = `${res.updated} oferta(s) atualizada(s)`;
        this.refreshing = false;
        this.cdr.detectChanges();
        this.gameService.getGame(slug).subscribe({
          next: (d) => { this.game = d; this.cdr.detectChanges(); },
          error: () => {}
        });
      },
      error: () => {
        this.refreshMsg = 'Erro ao atualizar. Tente novamente.';
        this.refreshing = false;
        this.cdr.detectChanges();
      }
    });
  }

  bestPrice(offers: Offer[]): number | null {
    if (!offers.length) return null;
    return Math.min(...offers.map(o => o.price));
  }

  discount(offer: Offer): number {
    if (!offer.regularPrice) return 0;
    return Math.round((1 - offer.price / offer.regularPrice) * 100);
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
}
