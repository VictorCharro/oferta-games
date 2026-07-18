import { Component, OnInit, OnDestroy, ChangeDetectorRef, HostListener } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { of, Subscription } from 'rxjs';
import { catchError, distinctUntilChanged, filter, map, switchMap, tap } from 'rxjs/operators';
import { GameService, GameDetail as GameDetailModel, GameSummary, Offer } from '../../services/game';
import { FavoritesService } from '../../services/favorites';
import { ProfileFavoritesService } from '../../services/profile-favorites';
import { ColecoesPerfilService } from '../../services/colecoes-perfil';
import { ColecaoPerfil } from '../../services/perfis';
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
  menuColecoesAberto = false;
  colecoes: ColecaoPerfil[] = [];
  carregandoColecoes = false;
  novaListaNome = '';
  criandoLista = false;
  private routeSub?: Subscription;
  private favoriteSub?: Subscription;
  private profileFavoriteSub?: Subscription;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private gameService: GameService,
    private favoritesService: FavoritesService,
    private profileFavoritesService: ProfileFavoritesService,
    private colecoesService: ColecoesPerfilService,
    private auth: AuthService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    this.favoriteSub = this.favoritesService.slugs$.subscribe(() => this.cdr.detectChanges());
    this.profileFavoriteSub = this.profileFavoritesService.slugs$.subscribe(() => this.cdr.detectChanges());
    this.profileFavoritesService.load().catch(() => {});
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
    this.profileFavoriteSub?.unsubscribe();
  }

  get monitoring(): boolean {
    return this.game ? this.favoritesService.isFavorited(this.game.slug) : false;
  }

  toggleMonitoring(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    if (!this.game) return;
    if (!this.auth.isLoggedIn) {
      this.router.navigate(['/login']);
      return;
    }
    this.favoritesService.toggle(this.gameSummary(this.game));
  }

  get personalFavorite(): boolean {
    return this.game ? this.profileFavoritesService.isFavorite(this.game.slug) : false;
  }

  async togglePersonalFavorite(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    if (!this.game) return;
    if (!this.auth.isLoggedIn) { this.router.navigate(['/login']); return; }
    await this.profileFavoritesService.toggle(this.gameSummary(this.game));
    this.cdr.detectChanges();
  }

  async toggleMenuColecoes(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    if (!this.auth.isLoggedIn) { this.router.navigate(['/login']); return; }
    this.menuColecoesAberto = !this.menuColecoesAberto;
    if (this.menuColecoesAberto) await this.carregarColecoes();
    this.cdr.detectChanges();
  }

  fecharMenuColecoes() {
    this.menuColecoesAberto = false;
    this.novaListaNome = '';
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (this.menuColecoesAberto && !(event.target as HTMLElement).closest('.detail-collections')) {
      this.fecharMenuColecoes();
    }
  }

  private async carregarColecoes() {
    this.carregandoColecoes = true;
    this.cdr.detectChanges();
    try {
      this.colecoes = await this.colecoesService.listar();
    } catch {
      this.colecoes = [];
    }
    this.carregandoColecoes = false;
    this.cdr.detectChanges();
  }

  jogoNaColecao(colecao: ColecaoPerfil): boolean {
    return this.game ? colecao.jogos.some(jogo => jogo.slug === this.game!.slug) : false;
  }

  async alternarColecao(colecao: ColecaoPerfil) {
    if (!this.game) return;
    try {
      if (this.jogoNaColecao(colecao)) await this.colecoesService.removerJogo(colecao.id, this.game.slug);
      else await this.colecoesService.adicionarItem(colecao.id, { slug: this.game.slug });
      await this.carregarColecoes();
    } catch { /* silencioso: o menu apenas nao reflete a mudanca */ }
    this.cdr.detectChanges();
  }

  async criarListaComJogo() {
    const nome = this.novaListaNome.trim();
    if (!this.game || !nome || this.criandoLista) return;
    this.criandoLista = true;
    try {
      const idsAntes = new Set(this.colecoes.map(lista => lista.id));
      await this.colecoesService.criar(nome);
      const listas = await this.colecoesService.listar();
      // A colecao recem-criada e a que ainda nao existia antes de criar.
      const nova = listas.find(lista => !idsAntes.has(lista.id));
      if (nova) await this.colecoesService.adicionarItem(nova.id, { slug: this.game.slug });
      this.novaListaNome = '';
      await this.carregarColecoes();
    } catch { /* silencioso */ }
    this.criandoLista = false;
    this.cdr.detectChanges();
  }

  get isLoggedIn(): boolean {
    return this.auth.isLoggedIn;
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
