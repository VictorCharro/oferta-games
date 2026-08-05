import { Component, OnInit, OnDestroy, ChangeDetectorRef, HostListener, ViewChild, ElementRef } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { of, Subscription } from 'rxjs';
import { catchError, distinctUntilChanged, filter, map, switchMap, tap } from 'rxjs/operators';
import type Hls from 'hls.js';
import {
  GameService,
  GameDetail as GameDetailModel,
  GameSummary,
  Offer,
  GameDetails,
  RespostaAvaliacoesSteam,
  RespostaConquistas
} from '../../services/game';
import { FavoritesService } from '../../services/favorites';
import { ProfileFavoritesService } from '../../services/profile-favorites';
import { ColecoesPerfilService } from '../../services/colecoes-perfil';
import { ColecaoPerfil } from '../../services/perfis';
import { GameReviewsService, RespostaAvaliacoes } from '../../services/game-reviews';
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
  activeTab: 'precos' | 'sobre' | 'review' | 'conquistas' = 'precos';
  detalhes: GameDetails | null = null;
  conquistas: RespostaConquistas | null = null;
  midiaAtiva = 0;
  trailerTocando = false;
  @ViewChild('trailerVideo') trailerVideoRef?: ElementRef<HTMLVideoElement>;
  private hls?: Hls;
  menuColecoesAberto = false;
  colecoes: ColecaoPerfil[] = [];
  carregandoColecoes = false;
  novaListaNome = '';
  criandoLista = false;
  subTabReview: 'steam' | 'ofertagames' = 'steam';
  avaliacoes: RespostaAvaliacoes | null = null;
  avaliacoesSteam: RespostaAvaliacoesSteam | null = null;
  carregandoAvaliacoesSteam = false;
  carregandoMaisAvaliacoesSteam = false;
  ordenacaoAvaliacoesSteam: 'recent' | 'all' | 'updated' = 'recent';
  minhaNota = 0;
  estrelaEmFoco = 0;
  meuComentario = '';
  enviandoAvaliacao = false;
  filtroConquistas: 'todas' | 'desbloqueadas' | 'bloqueadas' = 'todas';
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
    private reviewsService: GameReviewsService,
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
      tap(slug => {
        this.game = null;
        this.detalhes = null;
        this.conquistas = null;
        this.avaliacoes = null;
        this.avaliacoesSteam = null;
        this.carregandoAvaliacoesSteam = true;
        this.carregandoMaisAvaliacoesSteam = false;
        this.ordenacaoAvaliacoesSteam = 'recent';
        this.loading = true;
        this.refreshing = false;
        this.refreshMsg = '';
        this.activeTab = 'precos';
        this.subTabReview = 'steam';
        this.midiaAtiva = 0;
        this.pararTrailer();
        this.resetarFormularioAvaliacao();
        this.filtroConquistas = 'todas';
        this.cdr.detectChanges();
        this.carregarDetalhesEConquistasEReviews(slug);
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
    this.hls?.destroy();
  }

  private carregarDetalhesEConquistasEReviews(slug: string) {
    this.gameService.getGameDetails(slug).pipe(catchError(() => of(null))).subscribe(detalhes => {
      this.detalhes = detalhes;
      this.cdr.detectChanges();
    });
    this.gameService.getGameAchievements(slug).then(conquistas => {
      this.conquistas = conquistas;
      this.cdr.detectChanges();
    }).catch(() => {});
    this.reviewsService.listar(slug).then(avaliacoes => {
      this.avaliacoes = avaliacoes;
      this.minhaNota = avaliacoes.minha?.nota ?? 0;
      this.meuComentario = avaliacoes.minha?.comentario ?? '';
      this.cdr.detectChanges();
    }).catch(() => {});
    this.carregarAvaliacoesSteam(slug);
  }

  // So mostra a aba quando ha conteudo real (destaques ou trailer): descricao/screenshots sozinhos
  // tambem existem pra trilhas sonoras e outros itens que nao sao jogo de fato.
  get temSobre(): boolean {
    const d = this.detalhes;
    return !!d && ((d.destaques?.length ?? 0) > 0 || !!d.trailerUrl);
  }

  // A aba precisa existir mesmo sem avaliacoes para que alguem possa publicar a primeira.
  get temReview(): boolean {
    return !!this.game;
  }

  get temConquistas(): boolean {
    return (this.conquistas?.total ?? 0) > 0;
  }

  get conquistasFiltradas() {
    const lista = this.conquistas?.conquistas ?? [];
    if (this.filtroConquistas === 'desbloqueadas') return lista.filter(c => c.desbloqueada);
    if (this.filtroConquistas === 'bloqueadas') return lista.filter(c => !c.desbloqueada);
    return lista;
  }

  definirFiltroConquistas(filtro: 'todas' | 'desbloqueadas' | 'bloqueadas') {
    this.filtroConquistas = filtro;
  }

  // A Steam nao classifica raridade: aproximamos pelo percentual global de quem desbloqueou.
  raridade(percentualGlobal: number | null): string {
    if (percentualGlobal == null) return 'Raro';
    if (percentualGlobal >= 20) return 'Comum';
    if (percentualGlobal >= 5) return 'Incomum';
    return 'Raro';
  }

  get midias(): { tipo: 'trailer' | 'imagem'; url: string; thumb: string }[] {
    const d = this.detalhes;
    if (!d) return [];
    const itens: { tipo: 'trailer' | 'imagem'; url: string; thumb: string }[] = [];
    if (d.trailerUrl) {
      itens.push({ tipo: 'trailer', url: d.trailerUrl, thumb: d.trailerThumbnail || d.screenshots[0] || '' });
    }
    for (const url of d.screenshots) {
      itens.push({ tipo: 'imagem', url, thumb: url });
    }
    return itens;
  }

  get midiaAtual() {
    return this.midias[this.midiaAtiva] ?? null;
  }

  selecionarMidia(indice: number) {
    this.midiaAtiva = indice;
    this.pararTrailer();
  }

  proximaMidia() {
    const total = this.midias.length;
    if (total) this.midiaAtiva = (this.midiaAtiva + 1) % total;
    this.pararTrailer();
  }

  midiaAnterior() {
    const total = this.midias.length;
    if (total) this.midiaAtiva = (this.midiaAtiva - 1 + total) % total;
    this.pararTrailer();
  }

  async tocarTrailer() {
    const midia = this.midiaAtual;
    if (!midia || midia.tipo !== 'trailer') return;
    this.trailerTocando = true;
    this.cdr.detectChanges();
    const video = this.trailerVideoRef?.nativeElement;
    if (!video) return;

    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = midia.url;
      video.play().catch(() => {});
      return;
    }

    const { default: HlsImpl } = await import('hls.js');
    if (!HlsImpl.isSupported()) return;
    this.hls?.destroy();
    this.hls = new HlsImpl();
    this.hls.loadSource(midia.url);
    this.hls.attachMedia(video);
    this.hls.on(HlsImpl.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
  }

  private pararTrailer() {
    this.trailerTocando = false;
    this.hls?.destroy();
    this.hls = undefined;
  }

  reviewPositividade(): number | null {
    const d = this.detalhes;
    if (!d) return null;
    const total = (d.reviewsPositivas ?? 0) + (d.reviewsNegativas ?? 0);
    return total > 0 ? Math.round(((d.reviewsPositivas ?? 0) / total) * 100) : null;
  }

  totalReviewsSteam(): number {
    return (this.detalhes?.reviewsPositivas ?? 0) + (this.detalhes?.reviewsNegativas ?? 0);
  }

  horasReviewSteam(minutos: number | null): string {
    if (!minutos) return 'Sem tempo de jogo público';
    const horas = Math.floor(minutos / 60);
    const resto = minutos % 60;
    return resto ? `${horas}h ${resto}min jogadas` : `${horas}h jogadas`;
  }

  perfilAutorSteam(steamId: string | null): string | null {
    return steamId ? `https://steamcommunity.com/profiles/${steamId}` : null;
  }

  get temMaisAvaliacoesSteam(): boolean {
    return !!this.avaliacoesSteam?.temMais && !!this.avaliacoesSteam.proximoCursor;
  }

  mostrarMaisAvaliacoesSteam() {
    const slug = this.game?.slug;
    const cursor = this.avaliacoesSteam?.proximoCursor;
    if (!slug || !cursor || this.carregandoMaisAvaliacoesSteam) return;

    this.carregandoMaisAvaliacoesSteam = true;
    this.gameService.getSteamReviews(slug, {
      cursor,
      ordenacao: this.ordenacaoAvaliacoesSteam,
      idioma: this.avaliacoesSteam?.idiomaConsulta ?? 'all',
    }).pipe(
      catchError(() => of(null))
    ).subscribe(proximaPagina => {
      if (proximaPagina && this.avaliacoesSteam) {
        const existentes = new Set(this.avaliacoesSteam.avaliacoes.map(item => item.id));
        const novas = proximaPagina.avaliacoes.filter(item => !existentes.has(item.id));
        this.avaliacoesSteam = {
          ...proximaPagina,
          avaliacoes: [...this.avaliacoesSteam.avaliacoes, ...novas],
        };
      }
      this.carregandoMaisAvaliacoesSteam = false;
      this.cdr.detectChanges();
    });
  }

  alterarOrdenacaoAvaliacoesSteam() {
    const slug = this.game?.slug;
    if (!slug) return;
    this.carregandoAvaliacoesSteam = true;
    this.avaliacoesSteam = null;
    this.carregarAvaliacoesSteam(slug);
  }

  private carregarAvaliacoesSteam(slug: string) {
    this.gameService.getSteamReviews(slug, {
      ordenacao: this.ordenacaoAvaliacoesSteam,
      idioma: 'brazilian',
    }).pipe(
      catchError(() => of({
        steamAppId: null,
        avaliacoes: [],
        proximoCursor: null,
        temMais: false,
        idiomaConsulta: 'brazilian' as const,
        ordenacao: this.ordenacaoAvaliacoesSteam,
      }))
    ).subscribe(avaliacoes => {
      this.avaliacoesSteam = avaliacoes;
      this.carregandoAvaliacoesSteam = false;
      this.cdr.detectChanges();
    });
  }

  reviewDescricaoSteam(): string {
    const descricao = this.detalhes?.notaReviews?.trim().toLowerCase();
    const traducoes: Record<string, string> = {
      'overwhelmingly positive': 'Extremamente positivas',
      'very positive': 'Muito positivas',
      'mostly positive': 'Majoritariamente positivas',
      'positive': 'Positivas',
      'mixed': 'Neutras',
      'mostly negative': 'Majoritariamente negativas',
      'negative': 'Negativas',
      'very negative': 'Muito negativas',
      'overwhelmingly negative': 'Extremamente negativas',
    };
    return descricao ? (traducoes[descricao] ?? this.detalhes?.notaReviews ?? '') : '';
  }

  trocarSubTabReview(tab: 'steam' | 'ofertagames') {
    this.subTabReview = tab;
  }

  readonly estrelas = [1, 2, 3, 4, 5];

  distribuicaoPercentual(nota: number): number {
    const resumo = this.avaliacoes?.resumo;
    if (!resumo || !resumo.total) return 0;
    return Math.round(((resumo.distribuicao[nota] ?? 0) / resumo.total) * 100);
  }

  private resetarFormularioAvaliacao() {
    this.minhaNota = 0;
    this.estrelaEmFoco = 0;
    this.meuComentario = '';
  }

  definirEstrela(nota: number) {
    if (!this.auth.isLoggedIn) { this.router.navigate(['/login']); return; }
    this.minhaNota = nota;
  }

  async enviarAvaliacao() {
    if (!this.auth.isLoggedIn) { this.router.navigate(['/login']); return; }
    if (!this.game || !this.minhaNota || this.enviandoAvaliacao) return;
    this.enviandoAvaliacao = true;
    this.cdr.detectChanges();
    try {
      await this.reviewsService.avaliar(this.game.slug, this.minhaNota, this.meuComentario.trim());
      this.avaliacoes = await this.reviewsService.listar(this.game.slug);
    } catch { /* silencioso: formulario permanece pro usuario tentar de novo */ }
    this.enviandoAvaliacao = false;
    this.cdr.detectChanges();
  }

  async excluirAvaliacao() {
    if (!this.game) return;
    try {
      await this.reviewsService.remover(this.game.slug);
      this.avaliacoes = await this.reviewsService.listar(this.game.slug);
      this.resetarFormularioAvaliacao();
    } catch { /* silencioso */ }
    this.cdr.detectChanges();
  }

  relativeTime(value: string): string {
    const elapsed = Math.max(0, Date.now() - new Date(value).getTime());
    const minutos = Math.floor(elapsed / 60000);
    if (minutos < 1) return 'agora mesmo';
    if (minutos < 60) return `há ${minutos} min`;
    const horas = Math.floor(minutos / 60);
    if (horas < 24) return `há ${horas} ${horas === 1 ? 'hora' : 'horas'}`;
    const dias = Math.floor(horas / 24);
    if (dias < 30) return `há ${dias} ${dias === 1 ? 'dia' : 'dias'}`;
    const meses = Math.floor(dias / 30);
    return `há ${meses} ${meses === 1 ? 'mês' : 'meses'}`;
  }

  async votarUtil(reviewId: number, util: boolean) {
    if (!this.game) return;
    if (!this.auth.isLoggedIn) { this.router.navigate(['/login']); return; }
    try {
      await this.reviewsService.votar(this.game.slug, reviewId, util);
      this.avaliacoes = await this.reviewsService.listar(this.game.slug);
      this.cdr.detectChanges();
    } catch { /* silencioso */ }
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

  bestOffer(offers: Offer[]): Offer | null {
    return offers.reduce<Offer | null>((best, offer) => !best || offer.price < best.price ? offer : best, null);
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
