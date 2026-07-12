import { Component, Input, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { GameSummary } from '../../services/game';
import { resolveDlc } from '../../services/filters';
import { FavoritesService } from '../../services/favorites';
import { AuthService } from '../../services/auth';
import { PlatformBrand, storeBrand, storePlatforms } from '../../services/store-brand';

@Component({
  selector: 'app-game-card',
  standalone: false,
  templateUrl: './game-card.html',
  styleUrl: './game-card.scss',
})
export class GameCard implements OnInit, OnDestroy {
  @Input() game!: GameSummary;
  @Input() discountPct?: number;
  @Input() storeName?: string;
  @Input() storeUrl?: string | null;

  private sub!: Subscription;

  constructor(
    private favoritesService: FavoritesService,
    private auth: AuthService,
    private router: Router,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    this.sub = this.favoritesService.slugs$.subscribe(() => this.cdr.detectChanges());
  }

  ngOnDestroy() {
    this.sub.unsubscribe();
  }

  get monitoring(): boolean {
    return this.favoritesService.isFavorited(this.game?.slug);
  }

  get discount(): number {
    if (this.discountPct != null) return this.discountPct;
    const min = Number(this.game?.minPrice);
    const reg = Number(this.game?.regularPrice);
    if (!reg || !min || reg <= min) return 0;
    return Math.round((1 - min / reg) * 100);
  }

  get isDlcGame(): boolean {
    return resolveDlc(this.game?.title ?? '', this.game?.isDlc);
  }

  get displayStoreName(): string | null {
    return this.storeName ?? this.game?.storeName ?? null;
  }

  get displayStoreUrl(): string | null {
    return this.storeUrl ?? this.game?.url ?? null;
  }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  storeLogo(storeName?: string | null): string {
    return storeBrand(storeName).logo;
  }

  platforms(storeName?: string | null): PlatformBrand[] {
    return storePlatforms(storeName, this.displayStoreUrl);
  }

  toggleMonitoring(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    if (!this.auth.isLoggedIn) {
      this.router.navigate(['/login']);
      return;
    }
    this.favoritesService.toggle(this.game);
  }
}
