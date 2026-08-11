import { Component, OnInit, OnDestroy, HostListener, ChangeDetectorRef } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { debounceTime, distinctUntilChanged, skip } from 'rxjs/operators';
import { GameService, GameSummary } from '../../services/game';
import { SearchService } from '../../services/search';
import { PreferencesService } from '../../services/preferences';
import { SeoService } from '../../services/seo';

@Component({
  selector: 'app-catalog',
  standalone: false,
  templateUrl: './catalog.html',
  styleUrl: './catalog.scss',
})
export class Catalog implements OnInit, OnDestroy {
  games: GameSummary[] = [];
  loading = true;
  error = false;
  page = 0;
  hasMore = true;
  readonly pageSize = 20;

  viewMode: 'compact' | 'large' = 'compact';
  sort = 'rank';
  type = 'all';
  platform = 'all';
  query = '';
  minPrice: number | null = null;
  maxPrice: number | null = null;
  minDiscount: number | null = null;
  minPriceInput = '';
  maxPriceInput = '';
  minDiscountInput = '';
  openDropdown: 'sort' | 'type' | 'platform' | null = null;

  private querySub!: Subscription;
  private requestVersion = 0;

  readonly sortOptions = [
    { value: 'rank', label: 'Mais relevantes' },
    { value: 'popularity', label: 'Mais famosos' },
    { value: 'discount', label: 'Maior desconto' },
    { value: 'price_asc', label: 'Menor preço' },
    { value: 'price_desc', label: 'Maior preço' },
  ];

  readonly typeOptions = [
    { value: 'all', label: 'Todos' },
    { value: 'game', label: 'Jogos' },
    { value: 'dlc', label: 'DLCs' },
  ];

  readonly platformOptions = [
    { value: 'all', label: 'Todas' },
    { value: 'pc', label: 'PC' },
    { value: 'xbox', label: 'Xbox' },
  ];

  get selectedSortLabel(): string {
    return this.sortOptions.find(option => option.value === this.sort)?.label ?? 'Mais relevantes';
  }

  get selectedTypeLabel(): string {
    return this.typeOptions.find(option => option.value === this.type)?.label ?? 'Todos';
  }

  get selectedPlatformLabel(): string {
    return this.platformOptions.find(option => option.value === this.platform)?.label ?? 'Todas';
  }

  private scrollTicking = false;

  constructor(
    private gameService: GameService,
    private cdr: ChangeDetectorRef,
    private route: ActivatedRoute,
    private searchService: SearchService,
    private preferencesService: PreferencesService,
    private seo: SeoService
  ) {}

  ngOnInit() {
    this.seo.set({
      title: 'Catálogo de jogos',
      description: 'Veja todos os jogos com o menor preço entre as principais lojas, com filtros de tipo, plataforma e desconto.',
      path: '/catalogo',
    });
    const params = this.route.snapshot.queryParamMap;
    const type = params.get('type');
    const sort = params.get('sort');
    const platform = params.get('platform');
    const preferences = this.preferencesService.preferences;
    this.platform = preferences.preferredPlatform;
    this.type = preferences.hideDlcs ? 'game' : 'all';
    this.maxPrice = preferences.maximumPrice;
    this.maxPriceInput = preferences.maximumPrice?.toString() ?? '';
    this.minDiscount = preferences.minimumDiscount || null;
    this.minDiscountInput = preferences.minimumDiscount ? preferences.minimumDiscount.toString() : '';
    if (type && ['all', 'game', 'dlc'].includes(type)) this.type = type;
    if (sort && this.sortOptions.some(o => o.value === sort)) this.sort = sort;
    if (platform && this.platformOptions.some(o => o.value === platform)) this.platform = platform;

    this.searchService.setQuery('');
    this.querySub = this.searchService.query$
      .pipe(skip(1), debounceTime(300), distinctUntilChanged())
      .subscribe(q => {
        this.query = q;
        this.load(true);
      });

    this.load(true);
  }

  ngOnDestroy() {
    this.querySub?.unsubscribe();
  }

  @HostListener('window:scroll')
  onWindowScroll() {
    if (this.scrollTicking) return;
    this.scrollTicking = true;
    requestAnimationFrame(() => {
      this.scrollTicking = false;
      this.checkLoadMore();
    });
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (!(event.target as HTMLElement).closest('.custom-select')) {
      this.openDropdown = null;
    }
  }

  @HostListener('document:keydown.escape')
  closeSortDropdown() {
    this.openDropdown = null;
  }

  toggleDropdown(name: 'sort' | 'type' | 'platform') {
    this.openDropdown = this.openDropdown === name ? null : name;
  }

  selectSort(value: string) {
    this.sort = value;
    this.openDropdown = null;
    this.applyFilters();
  }

  selectType(value: string) {
    this.type = value;
    this.openDropdown = null;
    this.applyFilters();
  }

  selectPlatform(value: string) {
    this.platform = value;
    this.openDropdown = null;
    this.applyFilters();
  }

  private checkLoadMore() {
    // window/document nao existem em Node (SSR); scroll infinito so faz sentido no browser.
    if (!this.hasMore || this.loading || typeof window === 'undefined') return;
    const scrolledToBottom =
      window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 600;
    if (scrolledToBottom) {
      this.loadMore();
    }
  }

  load(reset = false) {
    if (reset) { this.page = 0; this.games = []; }
    this.loading = true;
    this.error = false;
    const requestVersion = ++this.requestVersion;
    this.gameService.getGames(this.page, this.pageSize, {
      sort: this.sort,
      type: this.type,
      platform: this.platform,
      minPrice: this.minPrice,
      maxPrice: this.maxPrice,
      minDiscount: this.minDiscount,
      q: this.query,
    }).subscribe({
      next: (data) => {
        if (requestVersion !== this.requestVersion) return;
        this.games = [...this.games, ...data];
        this.hasMore = data.length === this.pageSize;
        this.loading = false;
        this.cdr.detectChanges();
        setTimeout(() => this.checkLoadMore());
      },
      error: () => {
        if (requestVersion !== this.requestVersion) return;
        this.loading = false;
        this.error = true;
        this.cdr.detectChanges();
      }
    });
  }

  applyFilters() {
    this.minPrice = this.minPriceInput !== '' ? Number(this.minPriceInput) : null;
    this.maxPrice = this.maxPriceInput !== '' ? Number(this.maxPriceInput) : null;
    this.minDiscount = this.minDiscountInput !== '' ? Number(this.minDiscountInput) : null;
    this.load(true);
  }

  clearFilters() {
    this.sort = 'rank';
    this.type = 'all';
    this.platform = 'all';
    this.query = '';
    this.minPrice = null;
    this.maxPrice = null;
    this.minPriceInput = '';
    this.maxPriceInput = '';
    this.minDiscount = null;
    this.minDiscountInput = '';
    this.searchService.setQuery('');
    this.load(true);
  }

  loadMore() {
    this.page++;
    this.load(false);
  }
}
