import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { GameService, TopDeal } from '../../services/game';
import { PlatformBrand, storeBrand, storePlatforms } from '../../services/store-brand';
import { SeoService } from '../../services/seo';

@Component({
  selector: 'app-free-games',
  standalone: false,
  templateUrl: './free-games.html',
  styleUrl: './free-games.scss',
})
export class FreeGames implements OnInit {
  games: TopDeal[] = [];
  loading = true;
  error = false;

  constructor(private gameService: GameService, private cdr: ChangeDetectorRef, private seo: SeoService) {}

  ngOnInit() {
    this.seo.set({
      title: 'Jogos gratuitos',
      description: 'Jogos disponíveis gratuitamente agora nas principais lojas.',
      path: '/gratuitos',
    });
    this.load();
  }

  load() {
    this.loading = true;
    this.error = false;
    this.gameService.getTopDeals(100).subscribe({
      next: (deals) => {
        this.games = deals.filter(d => Number(d.discountPct) === 100);
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.error = true; this.cdr.detectChanges(); }
    });
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
