import { Component, OnInit, OnDestroy, ChangeDetectorRef, HostListener, ViewChild, ElementRef } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
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
  RespostaConquistas,
  PontoHistoricoPreco
} from '../../services/game';
import { FavoritesService } from '../../services/favorites';
import { ProfileFavoritesService } from '../../services/profile-favorites';
import { ColecoesPerfilService } from '../../services/colecoes-perfil';
import { ColecaoPerfil } from '../../services/perfis';
import { GameReviewsService, RespostaAvaliacoes } from '../../services/game-reviews';
import { AuthService } from '../../services/auth';
import { PlatformBrand, storeBrand, storePlatforms } from '../../services/store-brand';
import { SeoService } from '../../services/seo';

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
  cooldownSegundos = 0;
  private cooldownInterval?: ReturnType<typeof setInterval>;
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
  priceHistory: PontoHistoricoPreco[] = [];
  heroCoverWidth = 400;
  capaCarregada = false;
  private imagemCapaCarregada = false;
  private fichaTecnicaResolvida = false;
  // O link "Ver na Steam" (dentro da ficha tecnica) so aparece depois que avaliacoesSteam chega -
  // uma chamada separada de "detalhes" (ver linkSteam getter e carregarAvaliacoesSteam) - sem
  // esperar isso tambem, o cartao ganhava mais uma linha e crescia depois da capa ja revelada.
  private avaliacoesSteamResolvida = false;
  // Nao reseta por navegacao (as fontes carregam uma vez por sessao, nao por jogo) - evita esperar
  // de novo em toda troca de jogo.
  private fontesProntas = false;
  // Chute inicial pra 1a pintura, antes do <img> real carregar e onCoverLoad() corrigir pro
  // formato exato - a maioria das capas do catalogo e mais larga que 16:9, entao ~2.1:1 erra menos
  // (fica mais perto do formato final, reduzindo o quanto a caixa muda quando a imagem chega).
  private coverNaturalRatio = 2.1;
  private heroFactsHeight = 0;
  private resizeObserver?: ResizeObserver;
  private factsEl?: HTMLElement;
  readonly chartWidth = 700;
  readonly chartHeight = 220;
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
    private cdr: ChangeDetectorRef,
    private seo: SeoService
  ) {}

  // A capa acompanha a altura do cartao de ficha tecnica (medida em runtime, ja que o conteudo do
  // cartao varia por jogo) mantendo a proporcao real da propria imagem - so assim da pra encher a
  // caixa inteira sem cortar nada nem sobrar borda (uma proporcao fixa tipo 16:9 nao bate com o
  // formato real das capas, que sao mais largas, tipo 2.1:1).
  //
  // O JS aqui calcula SO a largura ideal (altura da ficha x proporcao). O limite pra capa nao
  // espremer o bloco de titulo/preco/botoes numa tela estreita e feito 100% em CSS, pelo proprio
  // grid (ver .hero-content/.hero-cover-wrap no .scss): a coluna da capa encolhe e a altura vem de
  // aspect-ratio. Isso e proposital - a versao anterior media a largura de .hero-content pra
  // calcular esse limite, e essa medida e circular: a altura da capa muda a altura da pagina, que
  // faz a barra de rolagem aparecer/sumir, que muda a largura disponivel, que muda a largura da
  // capa... O navegador abortava esse ciclo no meio ("ResizeObserver loop completed with
  // undelivered notifications"), deixando a capa num tamanho diferente a cada vez - era isso que
  // fazia ela mudar de tamanho ao trocar de aba. Sem medir largura, o ciclo nao existe.
  @ViewChild('factsRef') set factsRefSetter(ref: ElementRef<HTMLElement> | undefined) {
    if (this.factsEl) this.resizeObserver?.unobserve(this.factsEl);
    this.factsEl = ref?.nativeElement;
    if (this.factsEl) {
      this.ensureResizeObserver();
      this.resizeObserver?.observe(this.factsEl);
    } else {
      this.heroFactsHeight = 0;
    }
    this.medirEAtualizarCapa();
  }

  private ensureResizeObserver() {
    if (this.resizeObserver || typeof ResizeObserver === 'undefined') return;
    // So observa a ficha tecnica, e so a altura dela. A ficha vive numa coluna de largura fixa
    // (260px) e com align-self:start, entao a altura dela nao depende em nada do tamanho da capa -
    // nao ha como realimentar.
    this.resizeObserver = new ResizeObserver(entries => {
      for (const entry of entries) {
        if (entry.target !== this.factsEl) continue;
        const altura = entry.contentRect.height;
        if (Math.round(altura) === Math.round(this.heroFactsHeight)) continue;
        this.heroFactsHeight = altura;
        this.recomputarTamanhoCapa();
      }
    });
  }

  // ResizeObserver cobre a maioria dos casos (o cartao crescer depois que os detalhes do jogo
  // chegam), mas seu callback e assincrono/atrelado ao ciclo de renderizacao do navegador - le
  // direto com getBoundingClientRect tambem, de forma sincrona, como reforco pra pegar o tamanho
  // certo já na primeira medida (e nao depender so do observer disparar a tempo).
  private medirEAtualizarCapa() {
    // No SSR o DOM e simulado (domino) e nao implementa getBoundingClientRect - sem essa guarda a
    // medicao lancava excecao nao tratada toda vez que a pagina do jogo era aberta direto (sem vir
    // de navegacao dentro do site), quebrando a renderizacao no servidor e so se corrigindo quando
    // o JS do cliente assumia - o que na pratica aparecia pro usuario como a capa "pulando" de
    // tamanho ~1-2s depois de a pagina abrir.
    if (this.factsEl?.getBoundingClientRect) this.heroFactsHeight = this.factsEl.getBoundingClientRect().height;
    this.recomputarTamanhoCapa();
  }

  private recomputarTamanhoCapa() {
    const alturaAlvo = Math.max(160, this.heroFactsHeight || 225);
    const novaLargura = Math.round(alturaAlvo * this.coverNaturalRatio);
    if (novaLargura === this.heroCoverWidth) return;
    this.heroCoverWidth = novaLargura;
    this.cdr.detectChanges();
  }

  get coverAspectRatioCss(): string {
    return String(this.coverNaturalRatio);
  }

  onCoverLoad(event: Event) {
    const img = event.target as HTMLImageElement;
    if (img.naturalWidth && img.naturalHeight) {
      this.coverNaturalRatio = img.naturalWidth / img.naturalHeight;
      this.recomputarTamanhoCapa();
    }
    this.imagemCapaCarregada = true;
    this.atualizarCapaVisivel();
  }

  // So revela a capa quando IMAGEM e ficha tecnica (que muda a altura-alvo) ja estao no tamanho
  // final - senao o fade some o salto da proporcao mas nao o salto de altura que vem depois, quando
  // "detalhes" chega e o cartao de ficha tecnica muda de tamanho (ver carregarDetalhesEConquistasEReviews).
  private atualizarCapaVisivel() {
    this.capaCarregada = this.imagemCapaCarregada && this.fichaTecnicaResolvida
      && this.avaliacoesSteamResolvida && this.fontesProntas;
  }

  // Fontes web ainda carregando trocam a altura do texto da ficha tecnica (FOUT/FOIT) depois que
  // imagem e "detalhes" ja pareciam prontos - sem esperar isso tambem, a capa podia revelar 2x
  // (uma vez cedo demais, outra quando a fonte troca e o cartao reflui). So roda uma vez por sessao.
  private aguardarFontesEEntao(cb: () => void) {
    const fonts = typeof document !== 'undefined' ? (document as any).fonts : undefined;
    if (this.fontesProntas || !fonts || fonts.status === 'loaded') {
      this.fontesProntas = true;
      cb();
      return;
    }
    fonts.ready.then(() => {
      this.fontesProntas = true;
      cb();
    });
  }

  ngOnInit() {
    this.aguardarFontesEEntao(() => {
      this.atualizarCapaVisivel();
      this.cdr.detectChanges();
    });
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
        this.pararCooldown();
        this.activeTab = 'precos';
        this.subTabReview = 'steam';
        this.midiaAtiva = 0;
        this.pararTrailer();
        this.resetarFormularioAvaliacao();
        this.filtroConquistas = 'todas';
        this.priceHistory = [];
        this.coverNaturalRatio = 2.1;
        this.capaCarregada = false;
        this.imagemCapaCarregada = false;
        this.fichaTecnicaResolvida = false;
        this.avaliacoesSteamResolvida = false;
        this.heroFactsHeight = 0;
        this.recomputarTamanhoCapa();
        this.cdr.detectChanges();
        this.carregarDetalhesEConquistasEReviews(slug);
        this.carregarHistoricoPrecos(slug);
      }),
      switchMap(slug => this.gameService.getGame(slug).pipe(catchError(() => of(null))))
    ).subscribe(data => {
      this.game = data;
      this.loading = false;
      if (data) {
        const melhorPreco = this.bestPrice(data.offers);
        const descricao = melhorPreco != null
          ? `Compare o preço de ${data.title} nas melhores lojas. Menor preço encontrado: ${this.formatPrice(melhorPreco)}.`
          : `Compare o preço de ${data.title} nas melhores lojas de jogos.`;
        this.seo.set({
          title: data.title,
          description: descricao,
          image: data.coverUrl,
          path: `/jogo/${data.slug}`,
        });
      } else {
        this.seo.reset();
      }
      this.cdr.detectChanges();
    });
  }

  ngOnDestroy() {
    this.routeSub?.unsubscribe();
    this.favoriteSub?.unsubscribe();
    this.profileFavoriteSub?.unsubscribe();
    this.hls?.destroy();
    this.resizeObserver?.disconnect();
    if (this.cooldownInterval) clearInterval(this.cooldownInterval);
    this.seo.reset();
  }

  private carregarDetalhesEConquistasEReviews(slug: string) {
    this.gameService.getGameDetails(slug).pipe(catchError(() => of(null))).subscribe(detalhes => {
      this.detalhes = detalhes;
      this.cdr.detectChanges();
      // O cartao de ficha tecnica so existe/muda de tamanho depois que "detalhes" chega; espera o
      // DOM assentar (setTimeout 0) antes de medir de novo e so entao liberar a capa (evita
      // mostrar a capa e, alguns instantes depois, ela pular de tamanho quando o cartao chegar).
      setTimeout(() => {
        this.medirEAtualizarCapa();
        this.fichaTecnicaResolvida = true;
        this.atualizarCapaVisivel();
        this.cdr.detectChanges();
      });
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

  private carregarHistoricoPrecos(slug: string) {
    this.gameService.getPriceHistory(slug).pipe(catchError(() => of([]))).subscribe(historico => {
      this.priceHistory = historico;
      this.cdr.detectChanges();
    });
  }

  get temHistoricoSuficiente(): boolean {
    return this.priceHistory.length >= 2;
  }

  get historicoMenorPreco(): number | null {
    return this.priceHistory.length ? Math.min(...this.priceHistory.map(p => Number(p.price))) : null;
  }

  get historicoMenorPrecoLoja(): string | null {
    if (!this.priceHistory.length) return null;
    return this.priceHistory.reduce((menor, p) => Number(p.price) < Number(menor.price) ? p : menor).lojaNome;
  }

  private historicoPontosXY(): { x: number; y: number; price: number; data: Date }[] {
    const pontos = this.priceHistory;
    if (pontos.length < 2) return [];
    const precos = pontos.map(p => Number(p.price));
    const min = Math.min(...precos);
    const max = Math.max(...precos);
    const faixa = max - min || 1;
    const paddingX = 8;
    const paddingTop = 16;
    const paddingBottom = 28;
    const larguraUtil = this.chartWidth - paddingX * 2;
    const alturaUtil = this.chartHeight - paddingTop - paddingBottom;
    return pontos.map((ponto, i) => ({
      x: paddingX + (i / (pontos.length - 1)) * larguraUtil,
      y: paddingTop + alturaUtil - ((Number(ponto.price) - min) / faixa) * alturaUtil,
      price: Number(ponto.price),
      data: new Date(ponto.capturadoEm),
    }));
  }

  get historicoLinhaPath(): string {
    return this.historicoPontosXY().map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ');
  }

  get historicoAreaPath(): string {
    const pts = this.historicoPontosXY();
    if (!pts.length) return '';
    const base = this.chartHeight - 28;
    return `${this.historicoLinhaPath} L ${pts[pts.length - 1].x.toFixed(1)} ${base} L ${pts[0].x.toFixed(1)} ${base} Z`;
  }

  get historicoUltimoPonto() {
    const pts = this.historicoPontosXY();
    return pts.length ? pts[pts.length - 1] : null;
  }

  get historicoRotulosEixoX(): string[] {
    const pts = this.historicoPontosXY();
    if (pts.length < 2) return [];
    const indices = [...new Set([0, Math.floor((pts.length - 1) / 2), pts.length - 1])];
    return indices.map(i => pts[i].data.toLocaleDateString('pt-BR', { day: 'numeric', month: 'short' }));
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

  get temFichaTecnica(): boolean {
    const d = this.detalhes;
    if (!d) return false;
    return !!(d.dataLancamento || d.desenvolvedores?.length || d.publicadoras?.length || d.generos?.length || this.modoJogo || this.linkSteam);
  }

  // A Steam nao manda um campo "modo de jogo" separado; aproxima pelas categorias (mesma fonte
  // usada em about-badges), procurando palavras-chave conhecidas de single/multiplayer.
  get modoJogo(): string | null {
    const categorias = (this.detalhes?.categorias ?? []).map(c => c.toLowerCase());
    if (!categorias.length) return null;
    const multiplayer = categorias.some(c => c.includes('multi') || c.includes('co-op') || c.includes('coop') || c.includes('mmo') || c.includes('pvp'));
    const umJogador = categorias.some(c => c.includes('single'));
    if (multiplayer && umJogador) return 'Um jogador e multiplayer';
    if (multiplayer) return 'Multiplayer';
    if (umJogador) return 'Um jogador';
    return null;
  }

  // steamAppId vem da resposta de reviews (ja buscada pra aba Review), sem precisar de outra
  // chamada so pra montar esse link.
  get linkSteam(): string | null {
    const appId = this.avaliacoesSteam?.steamAppId;
    return appId ? `https://store.steampowered.com/app/${appId}` : null;
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
      if (!this.avaliacoesSteamResolvida) {
        // O link "Ver na Steam" da ficha tecnica depende de avaliacoesSteam.steamAppId (linkSteam
        // getter) - espera o DOM assentar (setTimeout 0) antes de medir de novo, mesmo padrao do
        // "detalhes" (ver carregarDetalhesEConquistasEReviews).
        setTimeout(() => {
          this.medirEAtualizarCapa();
          this.avaliacoesSteamResolvida = true;
          this.atualizarCapaVisivel();
          this.cdr.detectChanges();
        });
      }
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

  // Cooldown de 5min por jogo no backend (ver ServicoCatalogo.COOLDOWN_REFRESH_MANUAL): ao ter
  // sucesso ja sabemos que o proximo clique vai ser barrado, entao inicia a contagem sem esperar
  // pelo 429. O botao mostra a contagem regressiva em vez de uma mensagem separada embaixo.
  private static readonly COOLDOWN_PADRAO_SEGUNDOS = 300;

  refresh() {
    if (!this.game || this.refreshing || this.cooldownSegundos > 0) return;
    this.refreshing = true;
    this.refreshMsg = '';
    this.cdr.detectChanges();
    const slug = this.game.slug;
    this.gameService.refreshGame(slug).subscribe({
      next: (res) => {
        this.refreshMsg = 'Ofertas atualizadas';
        this.refreshing = false;
        this.iniciarCooldown(GameDetail.COOLDOWN_PADRAO_SEGUNDOS);
        this.cdr.detectChanges();
        this.gameService.getGame(slug).subscribe({
          next: (d) => { this.game = d; this.cdr.detectChanges(); },
          error: () => {}
        });
        this.carregarHistoricoPrecos(slug);
      },
      error: (erro: HttpErrorResponse) => {
        this.refreshing = false;
        if (erro.status === 429) {
          this.iniciarCooldown(erro.error?.segundosRestantes ?? GameDetail.COOLDOWN_PADRAO_SEGUNDOS);
        } else {
          this.refreshMsg = 'Erro ao atualizar. Tente novamente.';
        }
        this.cdr.detectChanges();
      }
    });
  }

  private iniciarCooldown(segundos: number) {
    this.pararCooldown();
    this.cooldownSegundos = Math.max(0, Math.round(segundos));
    if (this.cooldownSegundos === 0) return;
    this.cooldownInterval = setInterval(() => {
      this.cooldownSegundos--;
      if (this.cooldownSegundos <= 0) {
        this.pararCooldown();
      }
      this.cdr.detectChanges();
    }, 1000);
  }

  private pararCooldown() {
    if (this.cooldownInterval) clearInterval(this.cooldownInterval);
    this.cooldownInterval = undefined;
    this.cooldownSegundos = 0;
  }

  get rotuloBotaoRefresh(): string {
    if (this.refreshing) return 'Atualizando...';
    if (this.cooldownSegundos > 0) {
      const minutos = Math.floor(this.cooldownSegundos / 60);
      const segundos = this.cooldownSegundos % 60;
      return `Aguarde ${minutos}:${segundos.toString().padStart(2, '0')}`;
    }
    return 'Atualizar preços';
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
