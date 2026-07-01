import { Component, OnInit, HostListener, ChangeDetectorRef } from '@angular/core';
import { GameService, GameSummary } from '../../services/game';

@Component({
  selector: 'app-catalog',
  standalone: false,
  templateUrl: './catalog.html',
  styleUrl: './catalog.scss',
})
export class Catalog implements OnInit {
  games: GameSummary[] = [];
  loading = true;
  page = 0;
  hasMore = true;
  readonly pageSize = 20;

  viewMode: 'compact' | 'large' = 'compact';
  sort = 'rank';
  type = 'all';
  minPrice: number | null = null;
  maxPrice: number | null = null;
  minPriceInput = '';
  maxPriceInput = '';

  readonly sortOptions = [
    { value: 'rank', label: 'Mais relevantes' },
    { value: 'discount', label: 'Maior desconto' },
    { value: 'price_asc', label: 'Menor preço' },
    { value: 'price_desc', label: 'Maior preço' },
  ];

  private scrollTicking = false;

  constructor(private gameService: GameService, private cdr: ChangeDetectorRef) {}

  ngOnInit() { this.load(true); }

  @HostListener('window:scroll')
  onWindowScroll() {
    if (this.scrollTicking) return;
    this.scrollTicking = true;
    requestAnimationFrame(() => {
      this.scrollTicking = false;
      this.checkLoadMore();
    });
  }

  private checkLoadMore() {
    if (!this.hasMore || this.loading) return;
    const scrolledToBottom =
      window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 600;
    if (scrolledToBottom) {
      this.loadMore();
    }
  }

  load(reset = false) {
    if (reset) { this.page = 0; this.games = []; }
    this.loading = true;
    this.gameService.getGames(this.page, this.pageSize, {
      sort: this.sort,
      type: this.type,
      minPrice: this.minPrice,
      maxPrice: this.maxPrice,
    }).subscribe({
      next: (data) => {
        this.games = [...this.games, ...data];
        this.hasMore = data.length === this.pageSize;
        this.loading = false;
        this.cdr.detectChanges();
        setTimeout(() => this.checkLoadMore());
      },
      error: () => { this.loading = false; this.cdr.detectChanges(); }
    });
  }

  applyFilters() {
    this.minPrice = this.minPriceInput !== '' ? Number(this.minPriceInput) : null;
    this.maxPrice = this.maxPriceInput !== '' ? Number(this.maxPriceInput) : null;
    this.load(true);
  }

  clearFilters() {
    this.sort = 'rank';
    this.type = 'all';
    this.minPrice = null;
    this.maxPrice = null;
    this.minPriceInput = '';
    this.maxPriceInput = '';
    this.load(true);
  }

  loadMore() {
    this.page++;
    this.load(false);
  }
}
