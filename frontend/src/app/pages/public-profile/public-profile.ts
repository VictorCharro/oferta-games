import { ChangeDetectorRef, Component, ElementRef, HostListener, Input, OnDestroy, OnInit, PendingTasks, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { DragDropModule } from '@angular/cdk/drag-drop';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, firstValueFrom } from 'rxjs';
import { CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { AuthService } from '../../services/auth';
import { ColecaoPerfil, ConquistaRecente, PerfilBloco, PerfilPublico, PerfisService } from '../../services/perfis';
import { TIPOS_COM_VISUALIZACAO, TIPOS_UNICOS, ajusteImagemDoBloco, blocosPadrao, novoBloco, visualizacaoDoBloco } from '../../services/perfil-blocos';
import { ColecoesPerfilService } from '../../services/colecoes-perfil';
import { ConexoesSteamService, JogoBibliotecaSteam } from '../../services/conexoes-steam';
import { GameService, GameSummary } from '../../services/game';
import { ProfileFavoritesService } from '../../services/profile-favorites';
import { supabase } from '../../services/supabase';
import { SeoService } from '../../services/seo';
import { StatusResposta } from '../../services/status-resposta';
import { mensagemDaApi } from '../../services/mensagem-api';
import { removerImagensOrfas } from '../../services/imagens-blocos';

@Component({
  selector: 'app-public-profile',
  imports: [CommonModule, FormsModule, RouterModule, DragDropModule],
  templateUrl: './public-profile.html',
  styleUrl: './public-profile.scss',
})
export class PublicProfile implements OnInit, OnDestroy {
  // Modo preview: a pagina /perfil/blocos embute este mesmo componente pra mostrar como o perfil
  // vai ficar com o rascunho de blocos, antes de salvar. Com previewHandle setado o componente
  // ignora a rota, nao mexe no SEO e se comporta como visitante (sem nenhum chrome de edicao).
  @Input() previewHandle = '';
  @Input() previewBlocos: PerfilBloco[] | null = null;

  profile: PerfilPublico | null = null;
  missing = false;
  /** A API falhou (nao foi 404/400): mostra "tente de novo" e o SSR responde 503 em vez de 404. */
  falhaCarregamento = false;
  private readonly statusResposta = inject(StatusResposta);
  private readonly pendingTasks = inject(PendingTasks);
  private readonly router = inject(Router);
  loading = true;
  activeTab: 'resumo' | 'jogosFavoritos' | 'biblioteca' | 'colecoes' = 'resumo';
  readonly capasSteamIndisponiveis = new Set<number>();
  readonly iconesConquistaIndisponiveis = new Set<string>();
  isOwner = false;
  ownerAvatar = '';
  editingBio = false;
  bioDraft = '';
  savingBio = false;
  refreshing = false;
  message = '';
  librarySearch = '';
  libraryOrder: 'tempo' | 'nome' | 'conquistas' = 'tempo';
  libraryPlatformFilter: 'todos' | 'steam' | 'xbox' = 'todos';
  librarySoPlatinados = false;
  editingLayout = false;
  savingLayout = false;
  editingFavorites = false;
  editingCollections = false;
  novaColecaoNome = '';
  criandoColecao = false;
  renomeandoColecaoId: number | null = null;
  renomearColecaoNome = '';
  // Colecoes vem recolhidas por padrao (mostram so o nome + contagem) - listas grandes deixavam a
  // aba "infinita". Guardado por id, nao por referencia do objeto: a lista e recarregada inteira
  // (objetos novos) apos qualquer alteracao, mas o id continua o mesmo.
  private readonly colecoesExpandidas = new Set<number>();
  gerenciandoColecao: ColecaoPerfil | null = null;
  fonteGerenciar: 'biblioteca' | 'catalogo' = 'biblioteca';
  buscaGerenciar = '';
  bibliotecaCompleta: JogoBibliotecaSteam[] = [];
  carregandoBiblioteca = false;
  resultadosCatalogo: GameSummary[] = [];
  buscandoCatalogo = false;
  private buscaCatalogoTimer?: ReturnType<typeof setTimeout>;
  layoutDraft: PerfilBloco[] = [];
  avatarZoom = 1;
  avatarPositionX = 50;
  avatarPositionY = 50;
  avatarPreview = '';
  avatarEditing = false;
  readonly avatarMinZoom = 1.15;
  @ViewChild('avatarCropFrame') avatarCropFrame?: ElementRef<HTMLDivElement>;
  private avatarDrag: { startX: number; startY: number; startPosX: number; startPosY: number } | null = null;
  private avatarPointerId: number | null = null;
  bannerZoom = 1;
  bannerPositionX = 50;
  bannerPositionY = 50;
  bannerPreview = '';
  bannerEditing = false;
  readonly bannerMinZoom = 1.15;
  @ViewChild('bannerCropFrame') bannerCropFrame?: ElementRef<HTMLDivElement>;
  private bannerFile: File | null = null;
  private bannerDrag: { startX: number; startY: number; startPosX: number; startPosY: number } | null = null;
  private bannerPointerId: number | null = null;
  blockMenuAberto: string | null = null;
  menuEdicaoAberto = false;
  denunciaAberta = false;
  denunciaEnviada = false;
  enviandoDenuncia = false;
  motivoDenuncia = '';
  erroDenuncia = '';
  globalColorMenuOpen = false;
  addBlockMenuOpen = false;
  globalBackgroundColor = '#121a2a';
  globalTextColor = '#f5f7fb';
  blockImageEditingId: string | null = null;
  blockImagePreview = '';
  blockImageFile: File | null = null;
  blockImageExternalUrl = '';
  blockImageZoom = 1;
  blockImagePositionX = 50;
  blockImagePositionY = 50;
  readonly blockImageMinZoom = 1.15;
  @ViewChild('blockCropFrame') blockCropFrame?: ElementRef<HTMLDivElement>;
  private blockImageNaturalWidth = 0;
  private blockImageNaturalHeight = 0;
  private blockImageDrag: { startX: number; startY: number; startPosX: number; startPosY: number } | null = null;
  private blockImagePointerId: number | null = null;
  private readonly customImageNaturalSize = new Map<string, { width: number; height: number }>();
  private readonly tamanhoIdealPorBloco = new Map<string, string>();
  readonly ajusteImagemOptions = [
    { value: 'proporcao', label: 'Manter proporção' },
    { value: 'redimensionar', label: 'Redimensionar' },
  ];
  readonly sizeOptions = [
    { value: 'pequeno', label: 'Pequeno' },
    { value: 'medio', label: 'Médio' },
    { value: 'largo', label: 'Largo' },
    { value: 'completo', label: 'Completo' },
  ];
  readonly backgroundOptions = [
    { value: 'padrao', label: 'Padrão' },
    { value: 'cor', label: 'Cor sólida' },
    { value: 'gradiente', label: 'Gradiente' },
    { value: 'imagem', label: 'Imagem' },
  ];
  private avatarFile: File | null = null;
  private routeSub?: Subscription;
  private profileRequest = 0;

  constructor(
    private route: ActivatedRoute,
    private perfis: PerfisService,
    private profileFavorites: ProfileFavoritesService,
    private colecoes: ColecoesPerfilService,
    private conexoesSteam: ConexoesSteamService,
    private games: GameService,
    public auth: AuthService,
    private cdr: ChangeDetectorRef,
    private seo: SeoService,
  ) {}

  ngOnInit() {
    if (this.previewHandle) {
      void this.loadProfile(this.previewHandle);
      return;
    }
    this.routeSub = this.route.paramMap.subscribe(params => {
      // PendingTasks: o app e zoneless, e o SSR so espera o que estiver registrado ali. O
      // loadProfile faz "await supabase.auth.getSession()" ANTES da chamada HTTP, e esse await nao
      // e rastreado — o servidor entregava o esqueleto de carregamento, com titulo generico
      // ("Oferta Games"), status 200 ate pra handle inexistente, e sem o card de compartilhamento
      // do perfil (issues #22 e #26).
      void this.pendingTasks.run(() => this.loadProfile(params.get('handle') || ''));
    });
  }

  ngOnDestroy() {
    this.routeSub?.unsubscribe();
    clearTimeout(this.buscaCatalogoTimer);
    if (!this.previewHandle) this.seo.reset();
  }

  /**
   * Descricao do card de compartilhamento: a bio, se existir; senao um resumo com os numeros que o
   * perfil deixa publicos (os ocultos ja chegam null da API, entao nada escondido vaza aqui).
   */
  private descricaoCompartilhamento(profile: PerfilPublico): string {
    const bio = profile.bio?.trim();
    if (bio) return bio;
    const partes: string[] = [];
    if (profile.totalJogosBiblioteca) partes.push(`${profile.totalJogosBiblioteca} jogos`);
    if (profile.totalMinutos) partes.push(`${Math.round(profile.totalMinutos / 60).toLocaleString('pt-BR')}h jogadas`);
    if (profile.jogosPlatinados) partes.push(`${profile.jogosPlatinados} platinados`);
    const resumo = partes.length ? `${partes.join(' · ')}. ` : '';
    return `${resumo}Veja a biblioteca e os jogos favoritos de ${profile.nomeExibicao} no Oferta Games.`;
  }

  private async loadProfile(handle: string) {
    const request = ++this.profileRequest;
    this.profile = null;
    this.missing = false;
    this.falhaCarregamento = false;
    this.loading = true;
    this.isOwner = false;
    this.ownerAvatar = '';
    this.editingBio = false;
    this.editingFavorites = false;
    this.editingCollections = false;
    this.renomeandoColecaoId = null;
    this.novaColecaoNome = '';
    this.gerenciandoColecao = null;
    this.bibliotecaCompleta = [];
    this.message = '';
    this.librarySearch = '';
    this.libraryOrder = 'tempo';
    this.libraryPlatformFilter = 'todos';
    this.librarySoPlatinados = false;
    this.activeTab = 'resumo';
    this.cdr.detectChanges();

    try {
      const profile = await this.perfis.publico(handle);
      if (request !== this.profileRequest) return;
      profile.favoritos ??= [];
      profile.colecoes ??= [];
      profile.atividades ??= [];
      profile.conquistasRecentes ??= [];
      profile.plataformasConectadas ??= [];
      profile.blocos = profile.blocos?.length
        ? profile.blocos
            .filter(block => !['resumo_favoritos', 'horas', 'conquistas'].includes(block.tipo as string))
            .map(block => ({ ...block, corTexto: block.corTexto ?? null, visualizacao: block.visualizacao ?? null }))
        : this.defaultBlocks();
      this.profile = profile;
      if (!this.previewHandle) this.seo.set({
        // Nome + @handle: e o que aparece no card quando o dono compartilha o link no Discord ou
        // no WhatsApp, o principal gancho social do perfil (issue #26).
        title: `${profile.nomeExibicao} (@${profile.handle})`,
        description: this.descricaoCompartilhamento(profile),
        image: profile.bannerUrl || profile.avatarUrl,
        path: `/${profile.handle}`,
      });
      this.bioDraft = profile.bio || '';
      this.avatarZoom = profile.avatarZoom || 1;
      this.avatarPositionX = profile.avatarPosicaoX ?? 50;
      this.avatarPositionY = profile.avatarPosicaoY ?? 50;
      this.bannerZoom = profile.bannerZoom || 1;
      this.bannerPositionX = profile.bannerPosicaoX ?? 50;
      this.bannerPositionY = profile.bannerPosicaoY ?? 50;

      try {
        // No preview o dono ve exatamente o que um visitante veria, entao nem consulta o /me.
        const own = this.previewHandle ? null : await this.perfis.proprio();
        if (request !== this.profileRequest) return;
        this.isOwner = Boolean(own?.handle && own.handle === this.profile?.handle);
        this.ownerAvatar = this.isOwner ? this.auth.avatarUrl : '';
        if (this.isOwner && this.profile) this.profile.favoritos = await this.profileFavorites.load();
        // ?editor=1: o editor de blocos manda pra ca quando o dono quer ajustar a imagem de um
        // bloco, que so da pra recortar em cima do bloco ja renderizado.
        if (this.isOwner && this.route.snapshot.queryParamMap.get('editor') === '1') this.startLayoutEdit();
      } catch {
        if (request !== this.profileRequest) return;
        this.isOwner = false;
      }
    } catch (erro) {
      if (request !== this.profileRequest) return;
      this.missing = true;
      // 404 (nao existe/privado) e 400 (texto que nem e um handle valido, ex. /pagina.html) sao
      // "nao encontrado"; qualquer outra falha e a API indisponivel e nao pode sair como 404.
      const status = (erro as { status?: number })?.status;
      this.falhaCarregamento = status !== 404 && status !== 400;
      if (!this.previewHandle) {
        if (this.falhaCarregamento) {
          this.statusResposta.indisponivel();
          this.seo.set({ title: 'Perfil indisponível', description: 'Não foi possível carregar este perfil agora.', noindex: true });
        } else {
          this.statusResposta.naoEncontrado();
          this.seo.naoEncontrado();
        }
      }
    } finally {
      if (request !== this.profileRequest) return;
      this.loading = false;
      this.cdr.detectChanges();
    }
  }

  get visibleBlocks(): PerfilBloco[] {
    // No preview os blocos vem do rascunho do editor, e os ocultos somem (e o que o visitante ve).
    if (this.previewBlocos) return [...this.previewBlocos].filter(block => block.visivel).sort((a, b) => a.posicao - b.posicao);
    const blocks = this.editingLayout ? this.layoutDraft : this.profile?.blocos || [];
    // O backend ja filtra os ocultos em blocosPublicos; filtra aqui tambem pra nao depender do
    // servidor (e porque no modo de edicao inline os blocos vem do rascunho, nao da API).
    return [...blocks].filter(block => block.visivel).sort((a, b) => a.posicao - b.posicao);
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    const alvo = event.target as HTMLElement;
    if (!alvo.closest('.editor-custom-select')) {
      this.globalColorMenuOpen = false;
      this.addBlockMenuOpen = false;
    }
    // O menu do bloco fecha ao clicar fora dele (o proprio menu para o clique no template).
    if (!alvo.closest('.block-menu-wrap')) this.blockMenuAberto = null;
    if (!alvo.closest('.hero-menu-wrap')) {
      this.menuEdicaoAberto = false;
      this.denunciaAberta = false;
    }
  }

  @HostListener('document:keydown.escape')
  closeEditorSelects() {
    this.globalColorMenuOpen = false;
    this.addBlockMenuOpen = false;
    this.blockMenuAberto = null;
    this.menuEdicaoAberto = false;
  }

  /** Denunciar exige conta (ver ControladorModeracao): deslogado vai pro login e volta pra ca. */
  abrirDenuncia(event: MouseEvent) {
    event.stopPropagation();
    if (!this.auth.isLoggedIn) {
      this.router.navigate(['/login'], { queryParams: { returnUrl: this.router.url, motivo: 'denunciar este perfil' } });
      return;
    }
    this.denunciaAberta = !this.denunciaAberta;
    this.erroDenuncia = '';
  }

  async enviarDenuncia() {
    if (!this.profile?.handle || this.motivoDenuncia.trim().length < 3) return;
    this.enviandoDenuncia = true;
    this.erroDenuncia = '';
    this.cdr.detectChanges();
    try {
      await this.perfis.denunciar(this.profile.handle, this.motivoDenuncia.trim());
      this.denunciaEnviada = true;
      this.motivoDenuncia = '';
    } catch (erro) {
      this.erroDenuncia = mensagemDaApi(erro, 'Não foi possível enviar agora. Tente de novo.');
    }
    this.enviandoDenuncia = false;
    this.cdr.detectChanges();
  }

  toggleMenuEdicao(event: MouseEvent) {
    event.stopPropagation();
    this.menuEdicaoAberto = !this.menuEdicaoAberto;
  }

  /** "Editar na pagina": o modo inline, sem sair do perfil. */
  editarNaPagina() {
    this.menuEdicaoAberto = false;
    this.startLayoutEdit();
  }

  /**
   * Menu "..." do bloco no editor inline. Substituiu a barra de controles que ficava empilhada
   * acima do bloco — mesma organizacao do menu da pagina /perfil/blocos.
   */
  isBlockMenuOpen(block: PerfilBloco) {
    return this.blockMenuAberto === block.id;
  }

  toggleBlockMenu(event: MouseEvent, block: PerfilBloco) {
    event.stopPropagation();
    this.blockMenuAberto = this.isBlockMenuOpen(block) ? null : block.id;
  }

  fecharMenuDoBloco() {
    this.blockMenuAberto = null;
  }

  /** Troca o tipo de fundo, semeando uma cor/gradiente inicial pra escolha nao cair em vazio. */
  definirFundo(block: PerfilBloco, value: string) {
    block.tipoFundo = value as PerfilBloco['tipoFundo'];
    if (value === 'cor' && !block.valorFundo?.match(/^#[0-9a-fA-F]{6}$/)) block.valorFundo = '#121a2a';
    if (value === 'gradiente' && !block.valorFundo?.startsWith('linear-gradient')) block.valorFundo = 'linear-gradient(135deg, #0ea5e9, #312e81)';
  }

  ajusteImagem(block: PerfilBloco): 'proporcao' | 'redimensionar' {
    return ajusteImagemDoBloco(block);
  }

  /**
   * Tamanho recomendado da imagem pra ela preencher a area sem sobrar espaco, em px reais (2x da
   * area, pra nao ficar borrada em tela retina). Calculado a partir do quadro renderizado e
   * guardado num Map: ler layout direto do template rodaria a cada ciclo de deteccao de mudanca
   * e devolveria valor diferente no meio do ciclo (NG0100).
   */
  tamanhoIdealImagem(block: PerfilBloco): string {
    return this.tamanhoIdealPorBloco.get(block.id) || '';
  }

  private medirTamanhoIdeal(block: PerfilBloco, quadro: HTMLElement | null) {
    const bloco = quadro?.closest('.profile-block') as HTMLElement | null;
    if (!quadro || !bloco) return;
    const estilo = getComputedStyle(bloco);
    const largura = quadro.clientWidth;
    // Altura que o quadro teria preenchendo o bloco: do topo dele ate o fim da area util.
    const altura = bloco.getBoundingClientRect().bottom - parseFloat(estilo.paddingBottom) - quadro.getBoundingClientRect().top;
    if (largura < 40 || altura < 40) return;
    this.tamanhoIdealPorBloco.set(block.id, `${Math.round(largura * 2)} × ${Math.round(altura * 2)} px`);
  }

  startLayoutEdit() {
    if (!this.profile || !this.isOwner || this.activeTab !== 'resumo') return;
    this.layoutDraft = structuredClone(this.profile.blocos?.length ? this.profile.blocos : this.defaultBlocks());
    this.editingLayout = true;
    this.globalColorMenuOpen = false;
    this.addBlockMenuOpen = false;
  }

  cancelLayoutEdit() { this.editingLayout = false; this.layoutDraft = []; this.globalColorMenuOpen = false; this.addBlockMenuOpen = false; }

  toggleAddBlockMenu(event: MouseEvent) {
    event.stopPropagation();
    this.addBlockMenuOpen = !this.addBlockMenuOpen;
    this.globalColorMenuOpen = false;
    this.blockMenuAberto = null;
  }

  // Bloqueia de novo o que ja foi adicionado (favoritos/biblioteca/atividade/platinados/wishlist
  // sao unicos por perfil - ver addBlock) e a wishlist quando o dono nao tem uma pra mostrar.
  addBlockOptionDisabled(tipo: PerfilBloco['tipo']): boolean {
    if (tipo === 'wishlist' && !this.profile?.temColecaoWishlistSteam) return true;
    if (tipo === 'conquistas-recentes' && !this.profile?.conquistasRecentes.length) return true;
    if (tipo === 'mais-jogados' && !this.profile?.biblioteca.length) return true;
    return ['favoritos', 'biblioteca', 'atividade', 'platinados', 'wishlist', 'conquistas-recentes', 'mais-jogados'].includes(tipo)
      && this.layoutDraft.some(block => block.tipo === tipo);
  }

  addBlockFromMenu(tipo: PerfilBloco['tipo']) {
    if (this.addBlockOptionDisabled(tipo)) return;
    this.addBlock(tipo);
    this.addBlockMenuOpen = false;
  }

  toggleGlobalColorMenu(event: MouseEvent) {
    event.stopPropagation();
    this.globalColorMenuOpen = !this.globalColorMenuOpen;
    this.addBlockMenuOpen = false;
    this.blockMenuAberto = null;
  }

  // Aplica de uma vez em todos os blocos do rascunho; nao fica salvo como preferencia global,
  // e so um atalho pra nao configurar bloco por bloco (o ajuste por bloco continua disponivel depois).
  applyGlobalBackground(color: string) {
    this.globalBackgroundColor = color;
    this.layoutDraft.forEach(block => { block.tipoFundo = 'cor'; block.valorFundo = color; });
  }

  resetGlobalBackground() {
    this.layoutDraft.forEach(block => { block.tipoFundo = 'padrao'; block.valorFundo = null; });
  }

  applyGlobalTextColor(color: string) {
    this.globalTextColor = color;
    this.layoutDraft.forEach(block => block.corTexto = color || null);
  }

  resetGlobalTextColor() {
    this.layoutDraft.forEach(block => block.corTexto = null);
  }

  dropBlock(event: CdkDragDrop<PerfilBloco[]>) {
    moveItemInArray(this.layoutDraft, event.previousIndex, event.currentIndex);
    this.layoutDraft.forEach((block, index) => block.posicao = index);
  }

  toggleFavoritesEdit() {
    this.editingFavorites = !this.editingFavorites;
    this.message = '';
  }

  async toggleCollectionsEdit() {
    const estavaEditando = this.editingCollections;
    this.editingCollections = !this.editingCollections;
    this.renomeandoColecaoId = null;
    this.novaColecaoNome = '';
    this.message = '';
    // Ao clicar "Concluir": o toggle "Mostrar lista de desejos Steam" muda a visibilidade no
    // /api/perfis/{handle} (visao publica), nao no /api/profile-collections (usado pelo
    // CRUD de colecoes) - sem recarregar por esse caminho, a colecao escondida continuava
    // aparecendo pro dono ate um F5.
    if (estavaEditando && !this.editingCollections && this.profile) {
      try {
        const atualizado = await this.perfis.publico(this.profile.handle);
        this.profile.colecoes = atualizado.colecoes;
      } catch { /* mantem a lista atual se o reload falhar */ }
      this.cdr.detectChanges();
    }
  }

  async alternarMostrarWishlistSteam(mostrar: boolean) {
    if (!this.isOwner || !this.profile) return;
    const anterior = this.profile.mostrarWishlistSteam;
    this.profile.mostrarWishlistSteam = mostrar;
    try {
      await this.perfis.atualizarMostrarWishlistSteam(mostrar);
    } catch {
      this.profile.mostrarWishlistSteam = anterior;
      this.message = 'Não foi possível salvar essa preferência.';
    }
    this.cdr.detectChanges();
  }

  async criarColecao() {
    const nome = this.novaColecaoNome.trim();
    if (!this.isOwner || !nome || this.criandoColecao) return;
    this.criandoColecao = true;
    try {
      await this.colecoes.criar(nome);
      await this.recarregarColecoes();
      this.novaColecaoNome = '';
    } catch {
      this.message = 'Não foi possível criar a coleção.';
    }
    this.criandoColecao = false;
    this.cdr.detectChanges();
  }

  iniciarRenomearColecao(colecao: ColecaoPerfil) {
    this.renomeandoColecaoId = colecao.id;
    this.renomearColecaoNome = colecao.nome;
  }

  cancelarRenomearColecao() {
    this.renomeandoColecaoId = null;
    this.renomearColecaoNome = '';
  }

  async salvarRenomearColecao(colecao: ColecaoPerfil) {
    const nome = this.renomearColecaoNome.trim();
    if (!this.isOwner || !nome) return;
    try {
      await this.colecoes.renomear(colecao.id, nome);
      await this.recarregarColecoes();
      this.cancelarRenomearColecao();
    } catch {
      this.message = 'Não foi possível renomear a coleção.';
    }
    this.cdr.detectChanges();
  }

  // Coleções de sistema (wishlist da Steam) sempre no topo, o resto na ordem que veio do backend.
  get colecoesOrdenadas(): ColecaoPerfil[] {
    if (!this.profile) return [];
    return [...this.profile.colecoes].sort((a, b) => Number(b.origemSistema) - Number(a.origemSistema));
  }

  colecaoExpandida(colecao: ColecaoPerfil): boolean {
    return this.colecoesExpandidas.has(colecao.id);
  }

  toggleColecaoExpandida(colecao: ColecaoPerfil) {
    if (this.colecoesExpandidas.has(colecao.id)) this.colecoesExpandidas.delete(colecao.id);
    else this.colecoesExpandidas.add(colecao.id);
  }

  async excluirColecao(colecao: ColecaoPerfil) {
    if (!this.isOwner) return;
    try {
      await this.colecoes.excluir(colecao.id);
      await this.recarregarColecoes();
    } catch {
      this.message = 'Não foi possível excluir a coleção.';
    }
    this.cdr.detectChanges();
  }

  private async recarregarColecoes() {
    if (!this.profile) return;
    const listas = await this.colecoes.listar();
    this.profile.colecoes = listas;
    // Mantem o modal apontando para a versao recem-carregada da colecao aberta.
    if (this.gerenciandoColecao) {
      this.gerenciandoColecao = listas.find(lista => lista.id === this.gerenciandoColecao?.id) ?? null;
    }
  }

  async abrirGerenciarJogos(colecao: ColecaoPerfil) {
    if (!this.isOwner) return;
    this.gerenciandoColecao = colecao;
    this.fonteGerenciar = 'biblioteca';
    this.buscaGerenciar = '';
    this.resultadosCatalogo = [];
    this.cdr.detectChanges();
    if (!this.bibliotecaCompleta.length) await this.carregarBibliotecaCompleta();
  }

  fecharGerenciarJogos() {
    this.gerenciandoColecao = null;
    this.buscaGerenciar = '';
    this.resultadosCatalogo = [];
  }

  trocarFonteGerenciar(fonte: 'biblioteca' | 'catalogo') {
    this.fonteGerenciar = fonte;
    this.buscaGerenciar = '';
    this.resultadosCatalogo = [];
  }

  private async carregarBibliotecaCompleta() {
    this.carregandoBiblioteca = true;
    this.cdr.detectChanges();
    try {
      this.bibliotecaCompleta = await this.conexoesSteam.biblioteca();
    } catch {
      this.bibliotecaCompleta = [];
      this.message = 'Não foi possível carregar sua biblioteca Steam.';
    }
    this.carregandoBiblioteca = false;
    this.cdr.detectChanges();
  }

  get bibliotecaFiltrada(): JogoBibliotecaSteam[] {
    const busca = this.buscaGerenciar.trim().toLocaleLowerCase('pt-BR');
    if (!busca) return this.bibliotecaCompleta;
    return this.bibliotecaCompleta.filter(jogo => jogo.titulo.toLocaleLowerCase('pt-BR').includes(busca));
  }

  // A busca do catalogo bate na API: espera o usuario parar de digitar.
  onBuscaCatalogoChange() {
    clearTimeout(this.buscaCatalogoTimer);
    const termo = this.buscaGerenciar.trim();
    if (termo.length < 2) {
      this.resultadosCatalogo = [];
      this.buscandoCatalogo = false;
      return;
    }
    this.buscandoCatalogo = true;
    this.buscaCatalogoTimer = setTimeout(() => void this.buscarNoCatalogo(termo), 350);
  }

  private async buscarNoCatalogo(termo: string) {
    try {
      this.resultadosCatalogo = await firstValueFrom(this.games.searchGames(termo));
    } catch {
      this.resultadosCatalogo = [];
    }
    this.buscandoCatalogo = false;
    this.cdr.detectChanges();
  }

  colecaoTemSteam(appId: number): boolean {
    return this.gerenciandoColecao?.jogos.some(jogo => jogo.steamAppId === appId) ?? false;
  }

  colecaoTemJogo(slug: string): boolean {
    return this.gerenciandoColecao?.jogos.some(jogo => jogo.slug === slug) ?? false;
  }

  async alternarSteamNaColecao(jogo: JogoBibliotecaSteam) {
    const colecao = this.gerenciandoColecao;
    if (!colecao || !this.isOwner) return;
    try {
      if (this.colecaoTemSteam(jogo.appId)) await this.colecoes.removerSteam(colecao.id, jogo.appId);
      else await this.colecoes.adicionarItem(colecao.id, { steamAppId: jogo.appId });
      await this.recarregarColecoes();
    } catch {
      this.message = 'Não foi possível atualizar a coleção.';
    }
    this.cdr.detectChanges();
  }

  async alternarCatalogoNaColecao(jogo: GameSummary) {
    const colecao = this.gerenciandoColecao;
    if (!colecao || !this.isOwner) return;
    try {
      if (this.colecaoTemJogo(jogo.slug)) await this.colecoes.removerJogo(colecao.id, jogo.slug);
      else await this.colecoes.adicionarItem(colecao.id, { slug: jogo.slug });
      await this.recarregarColecoes();
    } catch {
      this.message = 'Não foi possível atualizar a coleção.';
    }
    this.cdr.detectChanges();
  }

  steamCapaHorizontal(appId: number): string {
    return `https://cdn.akamai.steamstatic.com/steam/apps/${appId}/header.jpg`;
  }

  async dropFavorite(event: CdkDragDrop<unknown>) {
    if (!this.profile || !this.isOwner || (!this.editingFavorites && !this.editingLayout) || event.previousIndex === event.currentIndex) return;
    moveItemInArray(this.profile.favoritos, event.previousIndex, event.currentIndex);
    this.cdr.detectChanges();
    try {
      await this.profileFavorites.reorder(this.profile.favoritos.map(game => ({ slug: game.slug, steamAppId: game.steamAppId })));
    } catch {
      this.message = 'Não foi possível salvar a nova ordem dos favoritos.';
      this.profile.favoritos = await this.profileFavorites.load();
      this.cdr.detectChanges();
    }
  }

  addBlock(tipo: PerfilBloco['tipo']) {
    if (TIPOS_UNICOS.includes(tipo) && this.layoutDraft.some(block => block.tipo === tipo)) return;
    this.layoutDraft.push(novoBloco(tipo, this.layoutDraft.length));
  }

  removeBlock(index: number) { this.layoutDraft.splice(index, 1); this.layoutDraft.forEach((block, position) => block.posicao = position); }

  async saveLayout() {
    if (!this.profile) return;
    this.savingLayout = true;
    this.layoutDraft.forEach((block, position) => block.posicao = position);
    try {
      // O rascunho deste editor sai de profile.blocos, que vem do payload PUBLICO — e o publico ja
      // filtra os blocos Ocultos. Como o PUT substitui a lista inteira, salvar daqui apagava de vez
      // todo bloco que o dono tinha escondido pela pagina /perfil/blocos. Busca a lista completa e
      // devolve os ocultos (que este editor nem mostra) no fim, com as mesmas configuracoes.
      const todos = await this.perfis.meusBlocos();
      const ocultos = todos
        .filter(bloco => !bloco.visivel && !this.layoutDraft.some(rascunho => rascunho.id === bloco.id))
        .map((bloco, indice) => ({ ...bloco, posicao: this.layoutDraft.length + indice }));
      const completo = [...this.layoutDraft, ...ocultos];
      await this.perfis.salvarBlocos(completo);
      void removerImagensOrfas(todos, completo);
      this.profile.blocos = structuredClone(this.layoutDraft);
      this.editingLayout = false;
      this.message = 'Layout do perfil salvo.';
    } catch (erro) { this.message = mensagemDaApi(erro, 'Não foi possível salvar o layout.'); }
    this.savingLayout = false;
    this.cdr.detectChanges();
  }

  setTextColor(block: PerfilBloco, color: string) {
    block.corTexto = color.trim() || null;
  }

  gradientColors(block: PerfilBloco): [string, string] {
    const colors = block.valorFundo?.match(/#[0-9a-fA-F]{6}/g) || [];
    return [colors[0] || '#0ea5e9', colors[1] || '#312e81'];
  }

  setGradientColor(block: PerfilBloco, index: 0 | 1, color: string) {
    const colors = this.gradientColors(block);
    colors[index] = color;
    block.valorFundo = `linear-gradient(135deg, ${colors[0]}, ${colors[1]})`;
  }

  blockStyle(block: PerfilBloco): Record<string, string> {
    const style: Record<string, string> = {};
    if (block.tipoFundo === 'cor' && block.valorFundo) style['background'] = block.valorFundo;
    if (block.tipoFundo === 'imagem' && block.valorFundo) style['backgroundImage'] = `linear-gradient(rgba(8,13,24,${block.opacidade / 100}), rgba(8,13,24,${block.opacidade / 100})), url('${block.valorFundo}')`;
    if (block.tipoFundo === 'gradiente' && block.valorFundo) style['background'] = block.valorFundo;
    if (block.corTexto) style['--profile-block-text-color'] = block.corTexto;
    return style;
  }

  async onBlockImageSelected(event: Event, block: PerfilBloco, destino: 'conteudo' | 'fundo' = 'conteudo') {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file || !file.type.startsWith('image/') || !this.auth.user) return;
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
      this.message = 'Escolha uma imagem JPG, PNG ou WebP.';
      return;
    }
    if (file.size > 2 * 1024 * 1024) { this.message = 'Escolha uma imagem de até 2 MB.'; return; }

    if (destino === 'conteudo') {
      this.startBlockImageEdit(block, file);
      (event.target as HTMLInputElement).value = '';
      return;
    }

    const caminho = `${this.auth.user.id}/blocks/${block.id}-${Date.now()}`;
    const { error } = await supabase.storage.from('avatars').upload(caminho, file, { contentType: file.type, cacheControl: '3600' });
    if (error) { this.message = `Não foi possível enviar a imagem: ${error.message}`; return; }
    const { data } = supabase.storage.from('avatars').getPublicUrl(caminho);
    block.valorFundo = data.publicUrl;
    this.cdr.detectChanges();
  }

  // Nao existe mais "Usar URL" pra imagem de bloco (issue #25): imagem de outro site fazia todo
  // visitante do perfil carregar a URL (vazando IP) e permitia hotlink de conteudo improprio. So
  // upload pro bucket do proprio usuario; o backend recusa URL externa nova (ValidadorBlocos).
  blockImageUrl(block: PerfilBloco): string {
    return this.blockImageData(block).url;
  }

  onCustomImageLoad(event: Event, block: PerfilBloco) {
    const img = event.target as HTMLImageElement;
    this.customImageNaturalSize.set(block.id, { width: img.naturalWidth, height: img.naturalHeight });
    this.medirTamanhoIdeal(block, img.parentElement);
  }

  blockImageStyle(block: PerfilBloco, frame: HTMLElement): Record<string, string> {
    const image = this.blockImageData(block);
    const natural = this.customImageNaturalSize.get(block.id);
    return this.coverStyle(
      frame?.clientWidth || 0, frame?.clientHeight || 0, natural?.width || 0, natural?.height || 0,
      image.zoom, image.positionX, image.positionY,
      this.ajusteImagem(block) === 'proporcao' ? 'contain' : 'cover');
  }

  isEditingBlockImage(block: PerfilBloco) {
    return this.blockImageEditingId === block.id;
  }

  startBlockImageEdit(block: PerfilBloco, file: File | null = null, externalUrl = '') {
    const image = this.blockImageData(block);
    if (this.blockImagePreview.startsWith('blob:')) URL.revokeObjectURL(this.blockImagePreview);
    this.blockImageEditingId = block.id;
    this.blockImageFile = file;
    this.blockImageExternalUrl = externalUrl;
    this.blockImagePreview = file ? URL.createObjectURL(file) : externalUrl || image.url;
    this.blockImageZoom = Math.max(this.blockImageMinZoom, image.zoom);
    this.blockImagePositionX = image.positionX;
    this.blockImagePositionY = image.positionY;
    this.blockImageNaturalWidth = 0;
    this.blockImageNaturalHeight = 0;
  }

  cancelBlockImageEdit() {
    if (this.blockImagePreview.startsWith('blob:')) URL.revokeObjectURL(this.blockImagePreview);
    this.blockImageEditingId = null;
    this.blockImagePreview = '';
    this.blockImageFile = null;
    this.blockImageExternalUrl = '';
    this.blockImageDrag = null;
    this.blockImagePointerId = null;
  }

  onBlockCropImageLoad(event: Event) {
    const img = event.target as HTMLImageElement;
    this.blockImageNaturalWidth = img.naturalWidth;
    this.blockImageNaturalHeight = img.naturalHeight;
    this.cdr.detectChanges();
  }

  blockImageEditStyle(): Record<string, string> {
    const frame = this.blockCropFrame?.nativeElement;
    return this.coverStyle(
      frame?.clientWidth || 0,
      frame?.clientHeight || 0,
      this.blockImageNaturalWidth,
      this.blockImageNaturalHeight,
      this.blockImageZoom,
      this.blockImagePositionX,
      this.blockImagePositionY,
    );
  }

  private blockImageAspectRatio(tamanho: PerfilBloco['tamanho']): number {
    switch (tamanho) {
      case 'pequeno': return 1;
      case 'medio': return 4 / 3;
      case 'largo': return 16 / 9;
      case 'completo': return 21 / 9;
      default: return 4 / 3;
    }
  }

  blockImagePreviewSize(tamanho: PerfilBloco['tamanho']): Record<string, string> {
    const maxWidth = 576;
    const maxHeight = 420;
    const ratio = this.blockImageAspectRatio(tamanho);
    let width = maxWidth;
    let height = width / ratio;
    if (height > maxHeight) {
      height = maxHeight;
      width = height * ratio;
    }
    return { width: `${width}px`, height: `${height}px` };
  }

  onBlockImagePointerStart(event: PointerEvent) {
    event.preventDefault();
    const frame = event.currentTarget as HTMLDivElement;
    frame.setPointerCapture(event.pointerId);
    this.blockImagePointerId = event.pointerId;
    this.blockImageDrag = {
      startX: event.clientX,
      startY: event.clientY,
      startPosX: this.blockImagePositionX,
      startPosY: this.blockImagePositionY,
    };
  }

  onBlockImagePointerMove(event: PointerEvent) {
    if (!this.blockImageDrag || this.blockImagePointerId !== event.pointerId) return;
    event.preventDefault();
    const frame = this.blockCropFrame?.nativeElement;
    const geo = this.coverGeometry(frame?.clientWidth || 0, frame?.clientHeight || 0, this.blockImageNaturalWidth, this.blockImageNaturalHeight, this.blockImageZoom);
    const dx = event.clientX - this.blockImageDrag.startX;
    const dy = event.clientY - this.blockImageDrag.startY;
    this.blockImagePositionX = this.clampPercent(this.blockImageDrag.startPosX + this.pixelsToPercent(dx, geo.maxOffsetX));
    this.blockImagePositionY = this.clampPercent(this.blockImageDrag.startPosY + this.pixelsToPercent(dy, geo.maxOffsetY));
  }

  onBlockImagePointerEnd(event: PointerEvent) {
    if (this.blockImagePointerId !== event.pointerId) return;
    const frame = event.currentTarget as HTMLDivElement;
    if (frame.hasPointerCapture(event.pointerId)) frame.releasePointerCapture(event.pointerId);
    this.blockImageDrag = null;
    this.blockImagePointerId = null;
  }

  onBlockImageWheel(event: WheelEvent) {
    event.preventDefault();
    const delta = event.deltaY > 0 ? -0.1 : 0.1;
    this.blockImageZoom = Math.min(3, Math.max(this.blockImageMinZoom, +(this.blockImageZoom + delta).toFixed(2)));
  }

  @HostListener('window:resize')
  onWindowResize() {
    // A area do bloco muda com a largura da janela, entao a dica de tamanho ideal remede junto.
    document.querySelectorAll('.custom-image-frame').forEach(quadro => {
      const id = (quadro as HTMLElement).dataset['blocoId'];
      const bloco = id ? this.visibleBlocks.find(b => b.id === id) : null;
      if (bloco) this.medirTamanhoIdeal(bloco, quadro as HTMLElement);
    });
    this.cdr.detectChanges();
  }

  // ajuste 'cover' (padrao) preenche o quadro e recorta o excesso; 'contain' cabe inteira dentro
  // dele, sobrando faixa nas laterais ou em cima/embaixo. O quadro em si nao muda de tamanho nos
  // dois casos - e o que mantem a altura do bloco independente da imagem.
  private coverGeometry(frameW: number, frameH: number, naturalW: number, naturalH: number, zoom: number, ajuste: 'cover' | 'contain' = 'cover') {
    if (!frameW || !frameH || !naturalW || !naturalH) return { width: 0, height: 0, maxOffsetX: 0, maxOffsetY: 0 };
    const proporcional = ajuste === 'contain'
      ? Math.min(frameW / naturalW, frameH / naturalH)
      : Math.max(frameW / naturalW, frameH / naturalH);
    const scale = proporcional * Math.max(zoom, 1);
    const width = naturalW * scale;
    const height = naturalH * scale;
    return { width, height, maxOffsetX: Math.max(0, (width - frameW) / 2), maxOffsetY: Math.max(0, (height - frameH) / 2) };
  }

  private coverStyle(frameW: number, frameH: number, naturalW: number, naturalH: number, zoom: number, positionX: number, positionY: number, ajuste: 'cover' | 'contain' = 'cover'): Record<string, string> {
    const geo = this.coverGeometry(frameW, frameH, naturalW, naturalH, zoom, ajuste);
    if (!geo.width || !geo.height) {
      return { width: '100%', height: '100%', objectFit: ajuste, objectPosition: `${positionX}% ${positionY}%` };
    }
    const offsetX = ((positionX - 50) / 50) * geo.maxOffsetX;
    const offsetY = ((positionY - 50) / 50) * geo.maxOffsetY;
    return {
      position: 'absolute',
      left: '50%',
      top: '50%',
      width: `${geo.width}px`,
      height: `${geo.height}px`,
      transform: `translate(-50%, -50%) translate(${offsetX}px, ${offsetY}px)`,
    };
  }

  private pixelsToPercent(deltaPx: number, maxOffset: number): number {
    return maxOffset > 0 ? (deltaPx / maxOffset) * 50 : 0;
  }

  private clampPercent(value: number): number {
    return Math.min(100, Math.max(0, value));
  }

  // Estilo de crop do banner/avatar via object-fit + object-position nativos do CSS, em vez de
  // calcular width/height/transform na mao a partir das dimensoes naturais da imagem e do frame
  // (o que dependia do ViewChild do frame e do onload da imagem terem resolvido a tempo, e
  // ocasionalmente colapsava pra um estado em branco). O object-position usa a escala invertida
  // (100 - x) porque positionX/Y aqui sempre significaram "quanto revelar do lado esquerdo/topo",
  // e object-position 100% revela o lado direito/inferior - precisa inverter pra manter o mesmo
  // sentido de arraste e os valores ja salvos no banco compativeis.
  private objectCoverStyle(zoom: number, positionX: number, positionY: number): Record<string, string> {
    const posX = 100 - this.clampPercent(positionX);
    const posY = 100 - this.clampPercent(positionY);
    return {
      width: '100%',
      height: '100%',
      objectFit: 'cover',
      objectPosition: `${posX}% ${posY}%`,
      transform: zoom > 1 ? `scale(${zoom})` : 'none',
      transformOrigin: `${posX}% ${posY}%`,
    };
  }

  private dragDeltaToPercent(deltaPx: number, frameSize: number, zoom: number): number {
    return frameSize > 0 ? (deltaPx / frameSize) * (100 / Math.max(zoom, 1)) : 0;
  }

  async saveBlockImageEdit(block: PerfilBloco) {
    if (!this.auth.user) return;
    let imageUrl = this.blockImageExternalUrl || this.blockImageData(block).url;

    if (this.blockImageFile) {
      const caminho = `${this.auth.user.id}/blocks/${block.id}-${Date.now()}`;
      const { error } = await supabase.storage.from('avatars').upload(caminho, this.blockImageFile, {
        contentType: this.blockImageFile.type,
        cacheControl: '3600',
      });
      if (error) {
        this.message = `Não foi possível enviar a imagem: ${error.message}`;
        this.cdr.detectChanges();
        return;
      }
      imageUrl = supabase.storage.from('avatars').getPublicUrl(caminho).data.publicUrl;
    }

    if (!imageUrl) return;
    block.conteudo = JSON.stringify({
      url: imageUrl,
      zoom: this.blockImageZoom,
      positionX: this.blockImagePositionX,
      positionY: this.blockImagePositionY,
    });
    this.cancelBlockImageEdit();
    this.cdr.detectChanges();
  }

  private blockImageData(block: PerfilBloco): { url: string; zoom: number; positionX: number; positionY: number } {
    const raw = block.conteudo?.trim() || '';
    if (!raw) return { url: '', zoom: 1, positionX: 50, positionY: 50 };
    try {
      const image = JSON.parse(raw) as Partial<{ url: string; zoom: number; positionX: number; positionY: number }>;
      if (typeof image.url === 'string') {
        const zoom = Number(image.zoom);
        const positionX = Number(image.positionX);
        const positionY = Number(image.positionY);
        return {
          url: image.url,
          zoom: Math.min(Math.max(Number.isFinite(zoom) ? zoom : 1, 1), 3),
          positionX: Math.min(Math.max(Number.isFinite(positionX) ? positionX : 50, 0), 100),
          positionY: Math.min(Math.max(Number.isFinite(positionY) ? positionY : 50, 0), 100),
        };
      }
    } catch {
      // Blocos antigos armazenavam somente a URL no conteudo.
    }
    return { url: raw, zoom: 1, positionX: 50, positionY: 50 };
  }

  links(block: PerfilBloco): Array<{ nome: string; url: string }> {
    return (block.conteudo || '')
      .split('\n')
      .map(linha => {
        // Aceita o formato atual "Nome - url" e o antigo "Nome | url", para nao quebrar links ja salvos.
        const separador = linha.includes(' - ') ? linha.indexOf(' - ') : linha.indexOf('|');
        const tamanhoSeparador = linha.includes(' - ') ? 3 : 1;
        const nome = separador === -1 ? '' : linha.slice(0, separador).trim();
        const endereco = separador === -1 ? linha.trim() : linha.slice(separador + tamanhoSeparador).trim();
        const url = this.normalizarUrlLink(endereco);
        return url ? { nome: nome || url, url } : null;
      })
      .filter((link): link is { nome: string; url: string } => link !== null);
  }

  private normalizarUrlLink(endereco: string): string | null {
    const valor = endereco.trim();
    if (!valor) return null;
    const urlCompleta = /^https?:\/\//i.test(valor) ? valor : `https://${valor}`;
    try {
      const url = new URL(urlCompleta);
      return ['http:', 'https:'].includes(url.protocol) ? url.href : null;
    } catch {
      return null;
    }
  }

  // Layout de quem nunca editou o perfil. A ordem segue a hierarquia do redesign: destaque visual
  // primeiro (platinados em cards grandes), depois as listas compactas (favoritos e conquistas)
  // lado a lado, e por fim biblioteca e atividade. Quem ja salvou blocos mantem o que montou.
  // Compartilhado com a pagina /perfil/blocos (services/perfil-blocos.ts).
  private defaultBlocks(): PerfilBloco[] {
    return blocosPadrao();
  }

  /**
   * Quantos cards de jogo o bloco mostra, calibrado pra TODO bloco ficar com mais ou menos a
   * mesma altura (~515px) — no perfil so a largura muda com o tamanho, a altura e uniforme,
   * porque o grid estica os blocos da linha pela altura do mais alto.
   *
   * <p>Cada tamanho tem largura de coluna diferente, e capa 16/9 significa que coluna mais
   * estreita da card mais baixo: no pequeno a linha de cards mede ~112px, no medio ~145px. Por
   * isso o pequeno leva 4 linhas (8 cards) e o medio 3 (6 cards) pra chegar na mesma altura —
   * nao e o mesmo numero pra todos, e o numero que iguala a altura.
   *
   * <p>Antes eram 1/4/6/8 e o pequeno era o pior caso: 1 card unico gigante, com metade do bloco
   * vazio ao lado de qualquer bloco em lista.
   */
  previewLimit(size: PerfilBloco['tamanho']): number {
    return { pequeno: 8, medio: 6, largo: 9, completo: 12 }[size];
  }

  // Itens de atividade sao linhas de texto compactas, nao cards em grade:
  // o limite de cards deixaria o bloco pequeno quase vazio.
  activityPreviewLimit(size: PerfilBloco['tamanho']): number {
    return { pequeno: 12, medio: 10, largo: 14, completo: 20 }[size];
  }

  maxTitleLength(block: PerfilBloco): number {
    return { pequeno: 42, medio: 64, largo: 88, completo: 120 }[block.tamanho];
  }

  maxContentLength(block: PerfilBloco): number {
    return { pequeno: 180, medio: 420, largo: 800, completo: 1400 }[block.tamanho];
  }

  defaultBlockTitle(block: PerfilBloco): string {
    return {
      favoritos: 'Jogos favoritos',
      biblioteca: 'Biblioteca',
      atividade: 'Atividade recente',
      platinados: 'Platinados',
      wishlist: 'Lista de Desejos (Steam)',
      'conquistas-recentes': 'Conquistas recentes',
      'mais-jogados': 'Mais jogados',
      texto: 'Texto',
      imagem: 'Imagem',
      links: 'Links',
    }[block.tipo];
  }

  // Limite proprio (maior que previewLimit): favoritos viraram lista compacta, e cabem varias
  // linhas na altura que um card grande ocuparia - num bloco pequeno (coluna estreita do Figma)
  // o certo e mostrar ~5 jogos, nao 1.
  favoritePreview(block: PerfilBloco) {
    // Em cards cada jogo ocupa uma capa inteira, entao cabem menos que na lista compacta.
    const limite = this.visualizacaoBloco(block) === 'cards'
      ? this.previewLimit(block.tamanho)
      : this.limiteLista(block.tamanho);
    return this.profile?.favoritos.slice(0, limite) || [];
  }

  // Mesma ideia de capasSteamIndisponiveis: guarda a URL que falhou pra nao tentar de novo em
  // cada ciclo de render e pra a linha cair no 🏅 em vez de mostrar imagem quebrada com alt.
  temIconeConquista(conquista: ConquistaRecente): boolean {
    return !!conquista.iconeUrl && !this.iconesConquistaIndisponiveis.has(conquista.iconeUrl);
  }

  aoFalharIconeConquista(conquista: ConquistaRecente) {
    if (!conquista.iconeUrl) return;
    this.iconesConquistaIndisponiveis.add(conquista.iconeUrl);
    this.cdr.detectChanges();
  }

  visualizacaoBloco(block: PerfilBloco): 'cards' | 'lista' {
    return visualizacaoDoBloco(block);
  }

  /** Blocos de jogos que tem lista compacta e cards desenhados (ver TIPOS_COM_VISUALIZACAO). */
  temVisualizacao(block: PerfilBloco): boolean {
    return TIPOS_COM_VISUALIZACAO.includes(block.tipo);
  }

  // "Mais jogados" nao tem dado proprio: e a mesma biblioteca (Steam + Xbox) ordenada por horas.
  maisJogadosPreview(block: PerfilBloco) {
    if (!this.profile) return [];
    return [...this.profile.biblioteca]
      .sort((a, b) => (b.minutosJogadas || 0) - (a.minutosJogadas || 0))
      .slice(0, this.previewLimit(block.tamanho));
  }

  // A lista ja vem pronta do backend (nome e icone cruzados com o catalogo, ver
  // ServicoConexoesSteam.conquistasRecentes) - aqui so corta pelo tamanho do bloco.
  conquistasPreview(block: PerfilBloco) {
    return this.profile?.conquistasRecentes.slice(0, this.limiteLista(block.tamanho)) || [];
  }

  // Teto de itens das listas compactas (favoritos em lista, conquistas), NAO a quantidade exibida:
  // quem decide quantos aparecem e o CSS, que mostra so os que cabem inteiros na altura do bloco
  // (ver .profile-block .achievement-list em styles.scss). Aqui so precisa sobrar item pra encher
  // - 1 coluna nos tamanhos menores, 2 nos maiores. Antes era contagem exata calibrada pra 460px,
  // e quando a altura subiu o bloco ficou com um terco vazio.
  private limiteLista(size: PerfilBloco['tamanho']): number {
    return { pequeno: 12, medio: 12, largo: 20, completo: 20 }[size];
  }

  /** "há 2h", "há 3 dias" — a data vem em ISO do backend. */
  tempoRelativo(iso: string): string {
    const minutos = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
    if (minutos < 60) return minutos <= 1 ? 'agora há pouco' : `há ${minutos} min`;
    const horas = Math.round(minutos / 60);
    if (horas < 24) return `há ${horas}h`;
    const dias = Math.round(horas / 24);
    if (dias < 30) return dias === 1 ? 'há 1 dia' : `há ${dias} dias`;
    const meses = Math.round(dias / 30);
    if (meses < 12) return meses === 1 ? 'há 1 mês' : `há ${meses} meses`;
    const anos = Math.round(meses / 12);
    return anos === 1 ? 'há 1 ano' : `há ${anos} anos`;
  }

  // Sem estado proprio - reusa a colecao de sistema da wishlist (ver "Colecoes de sistema" no
  // backend), a mesma que aparece na aba Colecoes. Vazio quando o dono ainda nao conectou Steam
  // ou nao tem wishlist populada, ou quando o toggle "Mostrar lista de desejos Steam" esta off.
  wishlistPreview(block: PerfilBloco) {
    const colecao = this.profile?.colecoes.find(c => c.origemSistema);
    return colecao?.jogos.slice(0, this.previewLimit(block.tamanho)) || [];
  }

  libraryPreview(block: PerfilBloco) {
    // Nao usa a getter libraryGames aqui: ela reflete a busca/ordenacao da aba Biblioteca,
    // e essa preview aparece no resumo (fora dessa aba), entao nao deve ser afetada por elas.
    if (!this.profile) return [];
    return [...this.profile.biblioteca]
      .sort((a, b) => b.minutosJogadas - a.minutosJogadas)
      .slice(0, this.previewLimit(block.tamanho));
  }

  get platinumGames(): PerfilPublico['biblioteca'] {
    if (!this.profile) return [];
    return this.profile.biblioteca
      .filter(game => game.conquistasTotal > 0 && game.conquistasDesbloqueadas >= game.conquistasTotal)
      .sort((a, b) => {
        if (a.platinumPosition != null && b.platinumPosition != null) return a.platinumPosition - b.platinumPosition;
        if (a.platinumPosition != null) return -1;
        if (b.platinumPosition != null) return 1;
        return b.minutosJogadas - a.minutosJogadas;
      });
  }

  platinumPreview(block: PerfilBloco) {
    return this.platinumGames.slice(0, this.previewLimit(block.tamanho));
  }

  async dropPlatinum(event: CdkDragDrop<unknown>) {
    if (!this.profile || !this.isOwner || !this.editingLayout || event.previousIndex === event.currentIndex) return;
    const ordenados = this.platinumGames;
    moveItemInArray(ordenados, event.previousIndex, event.currentIndex);
    ordenados.forEach((game, indice) => { game.platinumPosition = indice; });
    this.cdr.detectChanges();
    try {
      // So persiste a ordem dos platinados Steam por enquanto: o endpoint de reordenar e
      // especifico da Steam, e appId/titleId sao so numeros indistinguiveis entre plataformas -
      // mandar titleIds do Xbox pra la gravaria posicoes erradas. Os itens do Xbox continuam
      // reordenaveis na tela (estado otimista), so nao persistem entre sessoes ainda.
      const idsSteam = ordenados.filter(game => game.plataforma === 'steam').map(game => game.appId);
      await this.conexoesSteam.reordenarPlatinados(idsSteam);
    } catch {
      this.message = 'Não foi possível salvar a nova ordem dos platinados.';
      try {
        const atualizado = await this.perfis.publico(this.profile.handle);
        this.profile.biblioteca = atualizado.biblioteca;
      } catch { /* mantem o estado otimista se o reload tambem falhar */ }
      this.cdr.detectChanges();
    }
  }

  activityPreview(block: PerfilBloco) {
    return this.profile?.atividades.slice(0, this.activityPreviewLimit(block.tamanho)) || [];
  }

  steamCover(game: PerfilPublico['biblioteca'][number]): string {
    return game.capaUrl || `https://cdn.akamai.steamstatic.com/steam/apps/${game.appId}/header.jpg`;
  }

  tentarCapaSteamAlternativa(evento: Event, jogo: PerfilPublico['biblioteca'][number]) {
    const imagem = evento.target as HTMLImageElement;
    if (jogo.plataforma === 'steam' && imagem.dataset['capaAlternativa'] !== 'true') {
      imagem.dataset['capaAlternativa'] = 'true';
      imagem.src = `https://cdn.akamai.steamstatic.com/steam/apps/${jogo.appId}/capsule_616x353.jpg`;
      return;
    }
    this.capasSteamIndisponiveis.add(jogo.appId);
  }

  achievementProgress(game: PerfilPublico['biblioteca'][number]): number | null {
    return game.conquistasTotal > 0 ? Math.round((game.conquistasDesbloqueadas / game.conquistasTotal) * 100) : null;
  }

  favoriteAchievementProgress(game: PerfilPublico['favoritos'][number]): number | null {
    return game.conquistasTotal != null && game.conquistasTotal > 0 && game.conquistasDesbloqueadas != null
      ? Math.round((game.conquistasDesbloqueadas / game.conquistasTotal) * 100)
      : null;
  }

  platformIcon(platform: PerfilPublico['plataformasConectadas'][number]): string {
    return platform === 'xbox' ? 'xbox-modo-escuro.png' : 'steam-modo-escuro.png';
  }

  /**
   * Abre a aba Biblioteca. `soPlatinados` vem do "Ver todos" do bloco de Platinados: sem ele, o
   * link levava pra biblioteca inteira, que e justamente o oposto do que o bloco mostra.
   */
  openLibrary(soPlatinados = false) {
    if (this.editingLayout) return;
    this.librarySoPlatinados = soPlatinados;
    this.activeTab = 'biblioteca';
    setTimeout(() => document.querySelector('.library-list-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  hours(minutes: number | null): string {
    if (minutes == null) return '';
    return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
  }

  // So pro card "Horas jogadas" do topo do perfil — "3906h 34m" quebrava em 2 linhas nesse
  // numero grande (23px bold) na largura estreita do card em mobile. Os cards de jogo (que usam
  // hours() acima, em fonte bem menor) nao tem esse problema, entao mantem minutos.
  hoursOnly(minutes: number | null): string {
    if (minutes == null) return '';
    return `${Math.round(minutes / 60)}h`;
  }

  icon(game: PerfilPublico['biblioteca'][number]): string {
    return game.iconeHash
      ? `https://media.steampowered.com/steamcommunity/public/images/apps/${game.appId}/${game.iconeHash}.jpg`
      : 'store-logos/steam.svg';
  }

  formatPrice(price: number | null): string {
    return price == null ? 'Preço indisponível' : price.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  async removePersonalFavorite(game: PerfilPublico['favoritos'][number]) {
    if (!this.profile || !this.isOwner) return;
    try {
      if (game.steamAppId != null) await this.profileFavorites.removeSteam(game.steamAppId);
      else if (game.slug) await this.profileFavorites.remove(game.slug);
      this.profile.favoritos = await this.profileFavorites.load();
      this.message = 'Jogo removido dos favoritos.';
    } catch {
      this.message = 'Não foi possível remover o jogo dos favoritos.';
    }
    this.cdr.detectChanges();
  }

  async toggleSteamFavorite(game: PerfilPublico['biblioteca'][number]) {
    if (!this.profile || !this.isOwner) return;
    try {
      await this.profileFavorites.toggleSteam(game.appId);
      this.profile.favoritos = await this.profileFavorites.load();
      this.message = this.isSteamFavorite(game.appId) ? 'Jogo adicionado aos favoritos.' : 'Jogo removido dos favoritos.';
    } catch {
      this.message = 'Não foi possível atualizar os favoritos.';
    }
    this.cdr.detectChanges();
  }

  isSteamFavorite(appId: number): boolean {
    return this.profile?.favoritos.some(game => game.steamAppId === appId) ?? false;
  }

  // capaUrl (resolvida via appdetails, ver AgendadorCapasBibliotecaSteam) vem primeiro: o padrao
  // antigo de URL (cdn.akamai.steamstatic.com/.../header.jpg) nao existe mais pra jogos recentes,
  // cujas imagens vivem num caminho com hash imprevisivel - so a API da Steam sabe a URL certa.
  favoriteImage(game: PerfilPublico['favoritos'][number]): string {
    if (game.capaUrl) return game.capaUrl;
    if (game.steamAppId != null) {
      return `https://cdn.akamai.steamstatic.com/steam/apps/${game.steamAppId}/header.jpg`;
    }
    return 'store-logos/steam.svg';
  }

  favoriteUrl(game: PerfilPublico['favoritos'][number]): string {
    return game.slug ? `/jogo/${game.slug}` : `https://store.steampowered.com/app/${game.steamAppId}`;
  }

  activityIcon(type: string): string {
    if (type.startsWith('MONITORAMENTO')) return 'jogos-monitorados-modo-escuro.png';
    if (type.startsWith('FAVORITO_PESSOAL')) return 'jogos-favoritos.png';
    if (type.startsWith('BIBLIOTECA')) return 'biblioteca.png';
    if (type.startsWith('CONQUISTA')) return 'conquistas.png';
    return 'store-logos/steam.svg';
  }

  activityTitle(type: string, gameTitle: string | null, detail: string | null): string {
    const game = gameTitle || 'um jogo';
    switch (type) {
      case 'FAVORITO_PESSOAL_ADICIONADO': return `Adicionou ${game} aos jogos favoritos`;
      case 'FAVORITO_PESSOAL_REMOVIDO': return `Removeu ${game} dos jogos favoritos`;
      case 'FAVORITO_PESSOAL_STEAM_ADICIONADO': return `Adicionou ${detail || game} aos jogos favoritos`;
      case 'FAVORITO_PESSOAL_STEAM_REMOVIDO': return `Removeu ${detail || game} dos jogos favoritos`;
      case 'MONITORAMENTO_ADICIONADO': return `Adicionou ${game} aos jogos monitorados`;
      case 'MONITORAMENTO_REMOVIDO': return `Removeu ${game} dos jogos monitorados`;
      case 'STEAM_CONECTADA': return 'Conectou a conta Steam';
      case 'BIBLIOTECA_STEAM_SINCRONIZADA': return `Sincronizou a biblioteca Steam${detail ? ` (${detail})` : ''}`;
      case 'JOGO_ADICIONADO_BIBLIOTECA_STEAM': return `Adicionou ${detail || game} a biblioteca Steam`;
      case 'CONQUISTAS_STEAM_SINCRONIZADAS': return `Sincronizou as conquistas Steam${detail ? ` (${detail})` : ''}`;
      case 'CONQUISTA_STEAM_DESBLOQUEADA': return `Desbloqueou ${detail || `uma conquista em ${game}`}`;
      default: return 'Atualizou o perfil';
    }
  }

  get libraryGames(): PerfilPublico['biblioteca'] {
    if (!this.profile) return [];
    const query = this.librarySearch.trim().toLocaleLowerCase('pt-BR');
    // Mesmo critério do bloco de Platinados (ver platinumGames), pra "Ver todos" mostrar a mesma
    // lista, só sem o corte de quantidade.
    const base = this.librarySoPlatinados ? this.platinumGames : this.profile.biblioteca;
    const games = base.filter(game =>
      (!query || game.titulo.toLocaleLowerCase('pt-BR').includes(query)) &&
      (this.libraryPlatformFilter === 'todos' || game.plataforma === this.libraryPlatformFilter)
    );
    return [...games].sort((a, b) => {
      if (this.libraryOrder === 'nome') return a.titulo.localeCompare(b.titulo, 'pt-BR');
      if (this.libraryOrder === 'conquistas') return (b.conquistasDesbloqueadas / Math.max(1, b.conquistasTotal)) - (a.conquistasDesbloqueadas / Math.max(1, a.conquistasTotal));
      return b.minutosJogadas - a.minutosJogadas;
    });
  }

  relativeTime(value: string): string {
    const elapsed = Math.max(0, Date.now() - new Date(value).getTime());
    const minutes = Math.floor(elapsed / 60000);
    if (minutes < 1) return 'agora';
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} h`;
    const days = Math.floor(hours / 24);
    return `${days} d`;
  }

  async refreshProfile() {
    if (!this.profile || this.refreshing) return;
    this.refreshing = true;
    this.message = '';
    try {
      const result = await this.perfis.atualizarPublico(this.profile.handle);
      if (result.status === 'agendada') this.message = 'Atualização iniciada. Os dados serão atualizados em alguns instantes.';
      if (result.status === 'aguarde') this.message = 'Este perfil foi atualizado recentemente. Tente novamente em alguns minutos.';
      if (result.status === 'sem_conexao') this.message = 'Este perfil não possui uma conta Steam conectada.';
    } catch {
      this.message = 'Não foi possível iniciar a atualização do perfil.';
    }
    this.refreshing = false;
    this.cdr.detectChanges();
  }

  editBio() {
    this.bioDraft = this.profile?.bio || '';
    this.editingBio = true;
    this.message = '';
  }

  cancelBio() {
    this.editingBio = false;
  }

  async saveBio() {
    if (!this.profile) return;

    this.savingBio = true;
    this.message = '';
    const bio = this.bioDraft.trim();
    const metadata = this.auth.user?.user_metadata ?? {};
    const { error } = await supabase.auth.updateUser({ data: { ...metadata, bio } });

    if (!error) {
      try {
        const own = await this.perfis.proprio();
        if (!own?.handle) throw new Error('Perfil não encontrado');

        await this.perfis.salvar({
          handle: own.handle,
          nomeExibicao: own.nomeExibicao,
          bio,
          publico: own.publico,
          mostrarHoras: own.mostrarHoras,
          mostrarConquistas: own.mostrarConquistas,
          mostrarBiblioteca: own.mostrarBiblioteca,
          mostrarFavoritos: own.mostrarFavoritos,
          mostrarAtividades: own.mostrarAtividades,
          mostrarColecoes: own.mostrarColecoes,
        });

        this.profile.bio = bio;
        this.editingBio = false;
        this.message = 'Bio atualizada.';
      } catch {
        this.message = 'Não foi possível salvar a bio.';
      }
    } else {
      this.message = 'Não foi possível salvar a bio.';
    }

    this.savingBio = false;
    this.cdr.detectChanges();
  }

  onAvatarSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !file.type.startsWith('image/')) return;
    if (file.size > 2 * 1024 * 1024) { this.message = 'Escolha uma imagem de até 2 MB.'; return; }
    if (!this.auth.user || !this.profile) return;

    if (this.avatarPreview) URL.revokeObjectURL(this.avatarPreview);
    this.avatarPreview = URL.createObjectURL(file);
    this.avatarFile = file;
    this.avatarZoom = this.avatarMinZoom;
    this.avatarPositionX = 50;
    this.avatarPositionY = 50;
    this.avatarEditing = true;
    input.value = '';
    this.cdr.detectChanges();
  }

  cancelAvatarEdit() {
    if (this.avatarPreview) URL.revokeObjectURL(this.avatarPreview);
    this.avatarPreview = '';
    this.avatarFile = null;
    this.avatarEditing = false;
    this.avatarDrag = null;
    this.avatarPointerId = null;
    this.avatarZoom = this.profile?.avatarZoom || 1;
    this.avatarPositionX = this.profile?.avatarPosicaoX ?? 50;
    this.avatarPositionY = this.profile?.avatarPosicaoY ?? 50;
  }

  avatarDisplayStyle(): Record<string, string> {
    // Usa sempre o zoom/posicao salvos (nao o rascunho ao vivo do crop): o avatar de fundo fica
    // visivel atras do modal semi-transparente/desfocado, entao se ele seguisse o rascunho ao
    // vivo do crop ficaria piscando atras do modal a cada movimento do mouse durante o ajuste.
    const zoom = this.profile?.avatarZoom ?? 1;
    const posX = this.profile?.avatarPosicaoX ?? 50;
    const posY = this.profile?.avatarPosicaoY ?? 50;
    return this.objectCoverStyle(zoom, posX, posY);
  }

  avatarEditStyle(): Record<string, string> {
    return this.objectCoverStyle(this.avatarZoom, this.avatarPositionX, this.avatarPositionY);
  }

  // Mesmo padrao de arraste do bloco de imagem custom (onBlockImagePointer*): pointer events
  // com setPointerCapture no proprio elemento, em vez de listeners no document. Isso evita o
  // @HostListener('document:mousemove') que disparava change detection da pagina inteira a
  // qualquer movimento do mouse (arrastando ou nao).
  onAvatarPointerStart(event: PointerEvent) {
    event.preventDefault();
    const frame = event.currentTarget as HTMLDivElement;
    frame.setPointerCapture(event.pointerId);
    this.avatarPointerId = event.pointerId;
    this.avatarDrag = {
      startX: event.clientX,
      startY: event.clientY,
      startPosX: this.avatarPositionX,
      startPosY: this.avatarPositionY,
    };
  }

  onAvatarPointerMove(event: PointerEvent) {
    if (!this.avatarDrag || this.avatarPointerId !== event.pointerId) return;
    event.preventDefault();
    const frame = this.avatarCropFrame?.nativeElement;
    const frameSize = frame?.clientWidth || 0;
    const dx = event.clientX - this.avatarDrag.startX;
    const dy = event.clientY - this.avatarDrag.startY;
    this.avatarPositionX = this.clampPercent(this.avatarDrag.startPosX + this.dragDeltaToPercent(dx, frameSize, this.avatarZoom));
    this.avatarPositionY = this.clampPercent(this.avatarDrag.startPosY + this.dragDeltaToPercent(dy, frameSize, this.avatarZoom));
  }

  onAvatarPointerEnd(event: PointerEvent) {
    if (this.avatarPointerId !== event.pointerId) return;
    const frame = event.currentTarget as HTMLDivElement;
    if (frame.hasPointerCapture(event.pointerId)) frame.releasePointerCapture(event.pointerId);
    this.avatarDrag = null;
    this.avatarPointerId = null;
  }

  onAvatarWheel(event: WheelEvent) {
    event.preventDefault();
    const delta = event.deltaY > 0 ? -0.1 : 0.1;
    this.avatarZoom = Math.min(3, Math.max(this.avatarMinZoom, +(this.avatarZoom + delta).toFixed(2)));
  }

  async saveAvatarEdit() {
    const file = this.avatarFile;
    if (!file || !this.auth.user || !this.profile) return;

    this.message = '';
    const caminho = `${this.auth.user.id}/avatar`;
    const { error: erroUpload } = await supabase.storage.from('avatars').upload(caminho, file, { upsert: true, contentType: file.type, cacheControl: '3600' });
    if (erroUpload) { this.message = 'Não foi possível enviar a foto.'; this.cdr.detectChanges(); return; }

    const { data } = supabase.storage.from('avatars').getPublicUrl(caminho);
    const avatarUrl = `${data.publicUrl}?v=${Date.now()}`;
    const metadata = this.auth.user.user_metadata ?? {};
    const { error: erroAuth } = await supabase.auth.updateUser({ data: { ...metadata, avatar_url: avatarUrl } });
    if (erroAuth) { this.message = 'Não foi possível salvar a foto.'; this.cdr.detectChanges(); return; }

    try {
      await this.perfis.atualizarAvatar(avatarUrl, this.avatarZoom, this.avatarPositionX, this.avatarPositionY);
      this.ownerAvatar = avatarUrl;
      this.profile.avatarUrl = avatarUrl;
      this.profile.avatarZoom = this.avatarZoom;
      this.profile.avatarPosicaoX = this.avatarPositionX;
      this.profile.avatarPosicaoY = this.avatarPositionY;
      this.auth.updateAvatar(avatarUrl);
      this.cancelAvatarEdit();
    } catch {
      this.message = 'A foto foi enviada, mas não foi possível vinculá-la ao perfil.';
    }
    this.cdr.detectChanges();
  }

  onBannerSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !file.type.startsWith('image/')) return;
    if (file.size > 2 * 1024 * 1024) { this.message = 'Escolha uma imagem de até 2 MB.'; return; }
    if (!this.auth.user || !this.profile) return;

    if (this.bannerPreview) URL.revokeObjectURL(this.bannerPreview);
    this.bannerPreview = URL.createObjectURL(file);
    this.bannerFile = file;
    this.bannerZoom = this.bannerMinZoom;
    this.bannerPositionX = 50;
    this.bannerPositionY = 50;
    this.bannerEditing = true;
    input.value = '';
    this.cdr.detectChanges();
  }

  cancelBannerEdit() {
    if (this.bannerPreview) URL.revokeObjectURL(this.bannerPreview);
    this.bannerPreview = '';
    this.bannerFile = null;
    this.bannerEditing = false;
    this.bannerDrag = null;
    this.bannerPointerId = null;
    this.bannerZoom = this.profile?.bannerZoom || 1;
    this.bannerPositionX = this.profile?.bannerPosicaoX ?? 50;
    this.bannerPositionY = this.profile?.bannerPosicaoY ?? 50;
  }

  bannerDisplayStyle(): Record<string, string> {
    // Usa sempre o zoom/posicao salvos (nao o rascunho ao vivo do crop): o banner de fundo
    // fica visivel atras do modal semi-transparente/desfocado, entao se ele seguisse
    // bannerZoom/bannerPositionX/Y (que o drag do crop atualiza em tempo real) ficava piscando
    // atras do modal a cada movimento do mouse durante o ajuste.
    const zoom = this.profile?.bannerZoom ?? 1;
    const posX = this.profile?.bannerPosicaoX ?? 50;
    const posY = this.profile?.bannerPosicaoY ?? 50;
    return this.objectCoverStyle(zoom, posX, posY);
  }

  bannerEditStyle(): Record<string, string> {
    return this.objectCoverStyle(this.bannerZoom, this.bannerPositionX, this.bannerPositionY);
  }

  // Mesmo padrao de arraste do bloco de imagem custom (onBlockImagePointer*).
  onBannerPointerStart(event: PointerEvent) {
    event.preventDefault();
    const frame = event.currentTarget as HTMLDivElement;
    frame.setPointerCapture(event.pointerId);
    this.bannerPointerId = event.pointerId;
    this.bannerDrag = {
      startX: event.clientX,
      startY: event.clientY,
      startPosX: this.bannerPositionX,
      startPosY: this.bannerPositionY,
    };
  }

  onBannerPointerMove(event: PointerEvent) {
    if (!this.bannerDrag || this.bannerPointerId !== event.pointerId) return;
    event.preventDefault();
    const frame = this.bannerCropFrame?.nativeElement;
    const dx = event.clientX - this.bannerDrag.startX;
    const dy = event.clientY - this.bannerDrag.startY;
    this.bannerPositionX = this.clampPercent(this.bannerDrag.startPosX + this.dragDeltaToPercent(dx, frame?.clientWidth || 0, this.bannerZoom));
    this.bannerPositionY = this.clampPercent(this.bannerDrag.startPosY + this.dragDeltaToPercent(dy, frame?.clientHeight || 0, this.bannerZoom));
  }

  onBannerPointerEnd(event: PointerEvent) {
    if (this.bannerPointerId !== event.pointerId) return;
    const frame = event.currentTarget as HTMLDivElement;
    if (frame.hasPointerCapture(event.pointerId)) frame.releasePointerCapture(event.pointerId);
    this.bannerDrag = null;
    this.bannerPointerId = null;
  }

  onBannerWheel(event: WheelEvent) {
    event.preventDefault();
    const delta = event.deltaY > 0 ? -0.1 : 0.1;
    this.bannerZoom = Math.min(3, Math.max(this.bannerMinZoom, +(this.bannerZoom + delta).toFixed(2)));
  }

  async saveBannerEdit() {
    const file = this.bannerFile;
    if (!file || !this.auth.user || !this.profile) return;

    this.message = '';
    const caminho = `${this.auth.user.id}/banner`;
    const { error: erroUpload } = await supabase.storage.from('avatars').upload(caminho, file, { upsert: true, contentType: file.type, cacheControl: '3600' });
    if (erroUpload) { this.message = 'Não foi possível enviar o banner.'; this.cdr.detectChanges(); return; }

    const { data } = supabase.storage.from('avatars').getPublicUrl(caminho);
    const bannerUrl = `${data.publicUrl}?v=${Date.now()}`;

    try {
      await this.perfis.atualizarBanner(bannerUrl, this.bannerZoom, this.bannerPositionX, this.bannerPositionY);
      this.profile.bannerUrl = bannerUrl;
      this.profile.bannerZoom = this.bannerZoom;
      this.profile.bannerPosicaoX = this.bannerPositionX;
      this.profile.bannerPosicaoY = this.bannerPositionY;
      this.cancelBannerEdit();
    } catch {
      this.message = 'O banner foi enviado, mas não foi possível vinculá-lo ao perfil.';
    }
    this.cdr.detectChanges();
  }

}
