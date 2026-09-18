import { Component, OnInit, OnDestroy, ChangeDetectorRef, HostListener } from '@angular/core';
import { Subscription, catchError, of } from 'rxjs';
import { GameService, TopDeal, GameSummary, PontoHistoricoPreco } from '../../services/game';
import { FavoritesService, FavoriteGame } from '../../services/favorites';
import { resolveDlc } from '../../services/filters';
import { PlatformBrand, storeBrand, storePlatforms } from '../../services/store-brand';
import { PreferencesService, UserPreferences } from '../../services/preferences';
import { SeoService } from '../../services/seo';
import { temHistoricoParaGrafico } from '../../components/price-history-chart/price-history-chart';
import { trocarPorCapaPadrao } from '../../services/capa';

/** Piso de desconto da secao de promocoes dos mais populares e do banner. */
const DESCONTO_MINIMO_DESTAQUE = 20;

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
  /** Capa que nao carregou vira a imagem padrao (ver services/capa). */
  readonly capaIndisponivel = trocarPorCapaPadrao;

  featuredDeals: TopDeal[] = [];
  famousGames: DealCardView[] = [];
  lancamentos: DealCardView[] = [];
  favoritesDeals: FavoriteGame[] = [];
  preferredPlatformDeals: DealCardView[] = [];
  freeWeek: DealCardView[] = [];
  topDiscountGames: DealCardView[] = [];
  topDiscountDlcs: DealCardView[] = [];
  featuredIndex = 0;
  historicoPorSlug: Record<string, PontoHistoricoPreco[]> = {};
  loading = true;
  error = false;
  preferredPlatformLabel = '';
  private favSub!: Subscription;
  private autoplayTimer?: ReturnType<typeof setInterval>;
  private readonly autoplayIntervalMs = 6000;

  constructor(
    private gameService: GameService,
    private favoritesService: FavoritesService,
    private preferencesService: PreferencesService,
    private cdr: ChangeDetectorRef,
    private seo: SeoService
  ) {}

  ngOnInit() {
    this.seo.set({
      title: 'Início',
      description: 'Compare preços de jogos nas melhores lojas e encontre as maiores promoções.',
      path: '/',
    });
    this.loadPreferredPlatformDeals();
    this.carregarDestaques();

    this.gameService.getTopDeals(200, 'discount').subscribe({
      next: (deals) => {
        this.freeWeek = deals
          .filter(d => Number(d.discountPct) === 100 && !resolveDlc(d.title, d.isDlc) && this.matchesPreferences(d, true))
          .slice(0, 15)
          .map(d => this.fromTopDeal(d));

        const active = deals.filter(d => Number(d.discountPct) < 100 && this.matchesPreferences(d));
        this.topDiscountGames = active.filter(d => d.rank != null && !resolveDlc(d.title, d.isDlc)).slice(0, 20).map(d => this.fromTopDeal(d));
        this.cdr.detectChanges();
      }
    });

    // Busca DLCs direto (type=dlc) em vez de tentar achar dentro dos 200 mais descontados gerais:
    // DLC e uma fatia pequena do catalogo, entao raramente sobra alguma no topo do ranking geral -
    // ver ServicoAquecimentoCache/RepositorioDescontos no backend. Pede 50 (nao so 20) porque as
    // primeiras posicoes por desconto sao dominadas por entradas 100% off (gratis/preco zerado),
    // que o filtro abaixo descarta - com folga suficiente pra sobrar pelo menos 20 com desconto real.
    this.gameService.getTopDeals(50, 'discount', 'dlc').subscribe({
      next: (deals) => {
        this.topDiscountDlcs = deals
          .filter(d => Number(d.discountPct) < 100 && this.matchesPreferences(d))
          .slice(0, 20)
          .map(d => this.fromTopDeal(d));
        this.cdr.detectChanges();
      }
    });

    // Lancamentos quase nunca estao em promocao, entao nunca apareciam nas secoes de desconto:
    // aqui entram pelo preco atual, com ou sem desconto.
    this.gameService.getLancamentos().subscribe({
      next: (jogos) => {
        this.lancamentos = jogos
          .filter(d => !resolveDlc(d.title, d.isDlc) && this.matchesPreferences(d))
          .slice(0, 20)
          .map(d => this.fromTopDeal(d));
        this.cdr.detectChanges();
      }
    });

    this.favSub = this.favoritesService.list$.subscribe(list => {
      this.favoritesDeals = list.slice(0, 15);
      this.cdr.detectChanges();
    });
  }

  ngOnDestroy() {
    this.favSub?.unsubscribe();
    this.stopAutoplay();
  }

  private carregarDestaques() {
    this.loading = true;
    this.error = false;
    this.gameService.getTopDeals(100, 'rank').subscribe({
      next: (deals) => {
        // Promocao de verdade nos jogos mais populares: a partir de 20%. Sem esse piso entravam
        // "descontos" de 2% e 10% em jogo famoso (EA FC 27, Elden Ring), que nao sao destaque.
        // A lista ja chega ordenada por popularidade (ranking diario); o sorteio entre os 40
        // primeiros so varia a Home a cada visita sem sair do topo.
        const paid = deals.filter(d => Number(d.discountPct) >= DESCONTO_MINIMO_DESTAQUE && Number(d.discountPct) < 100
          && !resolveDlc(d.title, d.isDlc) && this.matchesPreferences(d));
        const topo = paid.slice(0, 40);
        this.featuredDeals = this.shuffle(topo).slice(0, 5);
        this.famousGames = this.shuffle(topo).slice(0, 20).map(d => this.fromTopDeal(d));
        this.loading = false;
        this.startAutoplay();
        this.carregarHistoricoSlideAtual();
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.error = true; this.cdr.detectChanges(); }
    });
  }

  retryDestaques() {
    this.carregarDestaques();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange() {
    if (document.hidden) {
      this.stopAutoplay();
    } else {
      this.startAutoplay();
    }
  }

  pausarAutoplay() {
    this.stopAutoplay();
  }

  // Arrastar o banner com o dedo (mobile): as setas somem em telas pequenas (ver @media no
  // home.scss) e o card de dicas passa a exigir arrastar pra navegar, no lugar delas.
  arrastandoBanner = false;
  private toqueInicioX = 0;
  bannerToqueDeltaX = 0;

  onBannerTouchStart(event: TouchEvent) {
    this.toqueInicioX = event.touches[0].clientX;
    this.bannerToqueDeltaX = 0;
    this.arrastandoBanner = false;
    this.pausarAutoplay();
  }

  onBannerTouchMove(event: TouchEvent) {
    const delta = event.touches[0].clientX - this.toqueInicioX;
    // So entra em modo "arrastando" depois de um limiar minimo — um toque quase parado (tremor
    // do dedo antes de soltar) nao pode acabar cancelando o clique de abrir o jogo.
    if (!this.arrastandoBanner && Math.abs(delta) < 8) return;
    this.arrastandoBanner = true;
    this.bannerToqueDeltaX = delta;
  }

  onBannerTouchEnd() {
    const LIMIAR_TROCA_PX = 50;
    if (this.arrastandoBanner) {
      if (this.bannerToqueDeltaX > LIMIAR_TROCA_PX) this.prevFeatured();
      else if (this.bannerToqueDeltaX < -LIMIAR_TROCA_PX) this.nextFeatured();
    }
    this.bannerToqueDeltaX = 0;
    this.retomarAutoplay();
    // O touchend dispara antes do "click" sintetico do browser no <a> do slide — mantem a flag
    // ligada mais um instante pra onBannerSlideClick conseguir cancelar a navegacao acidental.
    if (this.arrastandoBanner) {
      setTimeout(() => { this.arrastandoBanner = false; }, 100);
    }
  }

  onBannerSlideClick(event: MouseEvent) {
    if (this.arrastandoBanner) {
      event.preventDefault();
      event.stopPropagation();
    }
  }

  get bannerTrackTransform(): string {
    return `translateX(calc(-${this.featuredIndex * 100}% + ${this.bannerToqueDeltaX}px))`;
  }

  retomarAutoplay() {
    this.startAutoplay();
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
    this.carregarHistoricoSlideAtual();
  }

  private startAutoplay() {
    this.stopAutoplay();
    if (this.featuredDeals.length <= 1) return;
    this.autoplayTimer = setInterval(() => {
      this.featuredIndex = (this.featuredIndex + 1) % this.featuredDeals.length;
      this.carregarHistoricoSlideAtual();
      this.cdr.detectChanges();
    }, this.autoplayIntervalMs);
  }

  temHistoricoParaChart(slug: string): boolean {
    return temHistoricoParaGrafico(this.historicoPorSlug[slug] ?? []);
  }

  historicoMenorPreco(slug: string): number | null {
    const historico = this.historicoPorSlug[slug];
    return historico?.length ? Math.min(...historico.map(p => Number(p.price))) : null;
  }

  historicoMenorPrecoLoja(slug: string): string | null {
    const historico = this.historicoPorSlug[slug];
    if (!historico?.length) return null;
    return historico.reduce((menor, p) => Number(p.price) < Number(menor.price) ? p : menor).lojaNome;
  }

  private carregarHistoricoSlideAtual() {
    const slide = this.featuredDeals[this.featuredIndex];
    if (!slide || this.historicoPorSlug[slide.slug]) return;
    this.gameService.getPriceHistory(slide.slug).pipe(catchError(() => of([]))).subscribe(historico => {
      this.historicoPorSlug[slide.slug] = historico;
      this.cdr.detectChanges();
    });
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
