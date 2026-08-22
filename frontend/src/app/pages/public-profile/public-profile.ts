import { ChangeDetectorRef, Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Subscription, firstValueFrom } from 'rxjs';
import { CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { AuthService } from '../../services/auth';
import { ColecaoPerfil, PerfilBloco, PerfilPublico, PerfisService } from '../../services/perfis';
import { ColecoesPerfilService } from '../../services/colecoes-perfil';
import { ConexoesSteamService, JogoBibliotecaSteam } from '../../services/conexoes-steam';
import { GameService, GameSummary } from '../../services/game';
import { ProfileFavoritesService } from '../../services/profile-favorites';
import { supabase } from '../../services/supabase';
import { SeoService } from '../../services/seo';

@Component({
  selector: 'app-public-profile',
  standalone: false,
  templateUrl: './public-profile.html',
  styleUrl: './public-profile.scss',
})
export class PublicProfile implements OnInit, OnDestroy {
  profile: PerfilPublico | null = null;
  missing = false;
  loading = true;
  activeTab: 'resumo' | 'jogosFavoritos' | 'biblioteca' | 'colecoes' = 'resumo';
  readonly capasSteamIndisponiveis = new Set<number>();
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
  editingLayout = false;
  savingLayout = false;
  editingFavorites = false;
  editingCollections = false;
  novaColecaoNome = '';
  criandoColecao = false;
  renomeandoColecaoId: number | null = null;
  renomearColecaoNome = '';
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
  editorSelectOpen: { blockId: string; campo: 'tamanho' | 'tipoFundo' } | null = null;
  textColorMenuBlockId: string | null = null;
  globalColorMenuOpen = false;
  globalBackgroundColor = '#121a2a';
  globalTextColor = '#f5f7fb';
  blockImageEditingId: string | null = null;
  blockImagePreview = '';
  blockImageFile: File | null = null;
  blockImageExternalUrl = '';
  blockImageUrlInput: { blockId: string; destino: 'conteudo' | 'fundo' } | null = null;
  blockImageUrlDraft = '';
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
    this.routeSub = this.route.paramMap.subscribe(params => {
      void this.loadProfile(params.get('handle') || '');
    });
  }

  ngOnDestroy() {
    this.routeSub?.unsubscribe();
    clearTimeout(this.buscaCatalogoTimer);
    this.seo.reset();
  }

  private async loadProfile(handle: string) {
    const request = ++this.profileRequest;
    this.profile = null;
    this.missing = false;
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
    this.activeTab = 'resumo';
    this.cdr.detectChanges();

    try {
      const profile = await this.perfis.publico(handle);
      if (request !== this.profileRequest) return;
      profile.favoritos ??= [];
      profile.colecoes ??= [];
      profile.atividades ??= [];
      profile.plataformasConectadas ??= [];
      profile.blocos = profile.blocos?.length
        ? profile.blocos
            .filter(block => !['resumo_favoritos', 'horas', 'conquistas'].includes(block.tipo as string))
            .map(block => ({ ...block, corTexto: block.corTexto ?? null }))
        : this.defaultBlocks();
      this.profile = profile;
      this.seo.set({
        title: `Perfil de ${profile.nomeExibicao}`,
        description: profile.bio?.trim() || `Veja a biblioteca e os jogos favoritos de ${profile.nomeExibicao} no Oferta Games.`,
        image: profile.avatarUrl,
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
        const own = await this.perfis.proprio();
        if (request !== this.profileRequest) return;
        this.isOwner = Boolean(own?.handle && own.handle === this.profile?.handle);
        this.ownerAvatar = this.isOwner ? this.auth.avatarUrl : '';
        if (this.isOwner && this.profile) this.profile.favoritos = await this.profileFavorites.load();
      } catch {
        if (request !== this.profileRequest) return;
        this.isOwner = false;
      }
    } catch {
      if (request !== this.profileRequest) return;
      this.missing = true;
      this.seo.reset();
    } finally {
      if (request !== this.profileRequest) return;
      this.loading = false;
      this.cdr.detectChanges();
    }
  }

  get visibleBlocks(): PerfilBloco[] {
    const blocks = this.editingLayout ? this.layoutDraft : this.profile?.blocos || [];
    return [...blocks].sort((a, b) => a.posicao - b.posicao);
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (!(event.target as HTMLElement).closest('.editor-custom-select')) {
      this.editorSelectOpen = null;
      this.textColorMenuBlockId = null;
      this.globalColorMenuOpen = false;
    }
  }

  @HostListener('document:keydown.escape')
  closeEditorSelects() {
    this.editorSelectOpen = null;
    this.textColorMenuBlockId = null;
    this.globalColorMenuOpen = false;
  }

  isEditorSelectOpen(block: PerfilBloco, campo: 'tamanho' | 'tipoFundo') {
    return this.editorSelectOpen?.blockId === block.id && this.editorSelectOpen.campo === campo;
  }

  toggleEditorSelect(event: MouseEvent, block: PerfilBloco, campo: 'tamanho' | 'tipoFundo') {
    event.stopPropagation();
    this.editorSelectOpen = this.isEditorSelectOpen(block, campo) ? null : { blockId: block.id, campo };
    this.textColorMenuBlockId = null;
  }

  selectEditorOption(block: PerfilBloco, campo: 'tamanho' | 'tipoFundo', value: string) {
    if (campo === 'tamanho') block.tamanho = value as PerfilBloco['tamanho'];
    else {
      block.tipoFundo = value as PerfilBloco['tipoFundo'];
      if (value === 'cor' && !block.valorFundo?.match(/^#[0-9a-fA-F]{6}$/)) block.valorFundo = '#121a2a';
      if (value === 'gradiente' && !block.valorFundo?.startsWith('linear-gradient')) block.valorFundo = 'linear-gradient(135deg, #0ea5e9, #312e81)';
    }
    this.editorSelectOpen = null;
  }

  isTextColorMenuOpen(block: PerfilBloco) {
    return this.textColorMenuBlockId === block.id;
  }

  toggleTextColorMenu(event: MouseEvent, block: PerfilBloco) {
    event.stopPropagation();
    this.textColorMenuBlockId = this.isTextColorMenuOpen(block) ? null : block.id;
    this.editorSelectOpen = { blockId: block.id, campo: 'tipoFundo' };
  }

  selectedEditorOption(block: PerfilBloco, campo: 'tamanho' | 'tipoFundo') {
    const options = campo === 'tamanho' ? this.sizeOptions : this.backgroundOptions;
    const value = campo === 'tamanho' ? block.tamanho : block.tipoFundo;
    return options.find(option => option.value === value)?.label || '';
  }

  startLayoutEdit() {
    if (!this.profile || !this.isOwner || this.activeTab !== 'resumo') return;
    this.layoutDraft = structuredClone(this.profile.blocos?.length ? this.profile.blocos : this.defaultBlocks());
    this.editingLayout = true;
    this.globalColorMenuOpen = false;
  }

  cancelLayoutEdit() { this.editingLayout = false; this.layoutDraft = []; this.globalColorMenuOpen = false; }

  toggleGlobalColorMenu(event: MouseEvent) {
    event.stopPropagation();
    this.globalColorMenuOpen = !this.globalColorMenuOpen;
    this.editorSelectOpen = null;
    this.textColorMenuBlockId = null;
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

  toggleCollectionsEdit() {
    this.editingCollections = !this.editingCollections;
    this.renomeandoColecaoId = null;
    this.novaColecaoNome = '';
    this.message = '';
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
      this.message = 'Nao foi possivel criar a colecao.';
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
      this.message = 'Nao foi possivel renomear a colecao.';
    }
    this.cdr.detectChanges();
  }

  async excluirColecao(colecao: ColecaoPerfil) {
    if (!this.isOwner) return;
    try {
      await this.colecoes.excluir(colecao.id);
      await this.recarregarColecoes();
    } catch {
      this.message = 'Nao foi possivel excluir a colecao.';
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
      this.message = 'Nao foi possivel carregar sua biblioteca Steam.';
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
      this.message = 'Nao foi possivel atualizar a colecao.';
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
      this.message = 'Nao foi possivel atualizar a colecao.';
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
      this.message = 'Nao foi possivel salvar a nova ordem dos favoritos.';
      this.profile.favoritos = await this.profileFavorites.load();
      this.cdr.detectChanges();
    }
  }

  addBlock(tipo: PerfilBloco['tipo']) {
    if (['favoritos', 'biblioteca', 'atividade', 'platinados'].includes(tipo) && this.layoutDraft.some(block => block.tipo === tipo)) return;
    const id = `custom-${crypto.randomUUID()}`;
    this.layoutDraft.push({ id: ['favoritos', 'biblioteca', 'atividade', 'platinados'].includes(tipo) ? tipo : id, tipo, titulo: tipo === 'texto' ? 'Novo texto' : tipo === 'imagem' ? 'Imagem' : tipo === 'links' ? 'Links' : null, conteudo: tipo === 'texto' ? 'Escreva algo sobre você.' : '', posicao: this.layoutDraft.length, tamanho: 'medio', visivel: true, tipoFundo: 'padrao', valorFundo: null, opacidade: 0, corTexto: null });
  }

  removeBlock(index: number) { this.layoutDraft.splice(index, 1); this.layoutDraft.forEach((block, position) => block.posicao = position); }

  async saveLayout() {
    if (!this.profile) return;
    this.savingLayout = true;
    this.layoutDraft.forEach((block, position) => block.posicao = position);
    try {
      await this.perfis.salvarBlocos(this.layoutDraft);
      this.profile.blocos = structuredClone(this.layoutDraft);
      this.editingLayout = false;
      this.message = 'Layout do perfil salvo.';
    } catch { this.message = 'Não foi possível salvar o layout.'; }
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

  isBlockImageUrlInputOpen(block: PerfilBloco, destino: 'conteudo' | 'fundo') {
    return this.blockImageUrlInput?.blockId === block.id && this.blockImageUrlInput.destino === destino;
  }

  toggleBlockImageUrlInput(block: PerfilBloco, destino: 'conteudo' | 'fundo') {
    if (this.isBlockImageUrlInputOpen(block, destino)) {
      this.blockImageUrlInput = null;
      return;
    }
    this.blockImageUrlInput = { blockId: block.id, destino };
    this.blockImageUrlDraft = '';
  }

  useBlockImageUrl(block: PerfilBloco, destino: 'conteudo' | 'fundo') {
    const url = this.normalizarUrlImagem(this.blockImageUrlDraft);
    if (!url) {
      this.message = 'Informe uma URL de imagem valida, com http:// ou https://.';
      return;
    }
    this.blockImageUrlInput = null;
    if (destino === 'fundo') {
      block.valorFundo = url;
      return;
    }
    this.startBlockImageEdit(block, null, url);
  }

  blockImageUrl(block: PerfilBloco): string {
    return this.blockImageData(block).url;
  }

  onCustomImageLoad(event: Event, block: PerfilBloco) {
    const img = event.target as HTMLImageElement;
    this.customImageNaturalSize.set(block.id, { width: img.naturalWidth, height: img.naturalHeight });
  }

  blockImageStyle(block: PerfilBloco, frame: HTMLElement): Record<string, string> {
    const image = this.blockImageData(block);
    const natural = this.customImageNaturalSize.get(block.id);
    return this.coverStyle(frame?.clientWidth || 0, frame?.clientHeight || 0, natural?.width || 0, natural?.height || 0, image.zoom, image.positionX, image.positionY);
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
    this.cdr.detectChanges();
  }

  private coverGeometry(frameW: number, frameH: number, naturalW: number, naturalH: number, zoom: number) {
    if (!frameW || !frameH || !naturalW || !naturalH) return { width: 0, height: 0, maxOffsetX: 0, maxOffsetY: 0 };
    const scale = Math.max(frameW / naturalW, frameH / naturalH) * Math.max(zoom, 1);
    const width = naturalW * scale;
    const height = naturalH * scale;
    return { width, height, maxOffsetX: Math.max(0, (width - frameW) / 2), maxOffsetY: Math.max(0, (height - frameH) / 2) };
  }

  private coverStyle(frameW: number, frameH: number, naturalW: number, naturalH: number, zoom: number, positionX: number, positionY: number): Record<string, string> {
    const geo = this.coverGeometry(frameW, frameH, naturalW, naturalH, zoom);
    if (!geo.width || !geo.height) {
      return { width: '100%', height: '100%', objectFit: 'cover', objectPosition: `${positionX}% ${positionY}%` };
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

  private normalizarUrlImagem(endereco: string): string | null {
    return this.normalizarUrlLink(endereco);
  }

  private defaultBlocks(): PerfilBloco[] {
    return [
      { id: 'favoritos', tipo: 'favoritos', titulo: null, conteudo: null, posicao: 0, tamanho: 'largo', visivel: true, tipoFundo: 'padrao', valorFundo: null, opacidade: 0, corTexto: null },
      { id: 'biblioteca', tipo: 'biblioteca', titulo: null, conteudo: null, posicao: 1, tamanho: 'medio', visivel: true, tipoFundo: 'padrao', valorFundo: null, opacidade: 0, corTexto: null },
      { id: 'atividade', tipo: 'atividade', titulo: null, conteudo: null, posicao: 2, tamanho: 'medio', visivel: true, tipoFundo: 'padrao', valorFundo: null, opacidade: 0, corTexto: null },
    ];
  }

  previewLimit(size: PerfilBloco['tamanho']): number {
    return { pequeno: 1, medio: 4, largo: 6, completo: 8 }[size];
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
      texto: 'Texto',
      imagem: 'Imagem',
      links: 'Links',
    }[block.tipo];
  }

  favoritePreview(block: PerfilBloco) {
    return this.profile?.favoritos.slice(0, this.previewLimit(block.tamanho)) || [];
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
      this.message = 'Nao foi possivel salvar a nova ordem dos platinados.';
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

  openLibrary() {
    if (this.editingLayout) return;
    this.activeTab = 'biblioteca';
    setTimeout(() => document.querySelector('.library-list-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  hours(minutes: number | null): string {
    if (minutes == null) return '';
    return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
  }

  icon(game: PerfilPublico['biblioteca'][number]): string {
    return game.iconeHash
      ? `https://media.steampowered.com/steamcommunity/public/images/apps/${game.appId}/${game.iconeHash}.jpg`
      : 'store-logos/steam.svg';
  }

  formatPrice(price: number | null): string {
    return price == null ? 'Preco indisponivel' : price.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  async removePersonalFavorite(game: PerfilPublico['favoritos'][number]) {
    if (!this.profile || !this.isOwner) return;
    try {
      if (game.steamAppId != null) await this.profileFavorites.removeSteam(game.steamAppId);
      else if (game.slug) await this.profileFavorites.remove(game.slug);
      this.profile.favoritos = await this.profileFavorites.load();
      this.message = 'Jogo removido dos favoritos.';
    } catch {
      this.message = 'Nao foi possivel remover o jogo dos favoritos.';
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
    const games = this.profile.biblioteca.filter(game =>
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
      if (result.status === 'agendada') this.message = 'Atualizacao iniciada. Os dados serao atualizados em alguns instantes.';
      if (result.status === 'aguarde') this.message = 'Este perfil foi atualizado recentemente. Tente novamente em alguns minutos.';
      if (result.status === 'sem_conexao') this.message = 'Este perfil nao possui uma conta Steam conectada.';
    } catch {
      this.message = 'Nao foi possivel iniciar a atualizacao do perfil.';
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
        if (!own?.handle) throw new Error('Perfil nao encontrado');

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
        this.message = 'Nao foi possivel salvar a bio.';
      }
    } else {
      this.message = 'Nao foi possivel salvar a bio.';
    }

    this.savingBio = false;
    this.cdr.detectChanges();
  }

  onAvatarSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !file.type.startsWith('image/')) return;
    if (file.size > 2 * 1024 * 1024) { this.message = 'Escolha uma imagem de ate 2 MB.'; return; }
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
    if (erroUpload) { this.message = 'Nao foi possivel enviar a foto.'; this.cdr.detectChanges(); return; }

    const { data } = supabase.storage.from('avatars').getPublicUrl(caminho);
    const avatarUrl = `${data.publicUrl}?v=${Date.now()}`;
    const metadata = this.auth.user.user_metadata ?? {};
    const { error: erroAuth } = await supabase.auth.updateUser({ data: { ...metadata, avatar_url: avatarUrl } });
    if (erroAuth) { this.message = 'Nao foi possivel salvar a foto.'; this.cdr.detectChanges(); return; }

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
      this.message = 'A foto foi enviada, mas nao foi possivel vincula-la ao perfil.';
    }
    this.cdr.detectChanges();
  }

  onBannerSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !file.type.startsWith('image/')) return;
    if (file.size > 2 * 1024 * 1024) { this.message = 'Escolha uma imagem de ate 2 MB.'; return; }
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
    if (erroUpload) { this.message = 'Nao foi possivel enviar o banner.'; this.cdr.detectChanges(); return; }

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
      this.message = 'O banner foi enviado, mas nao foi possivel vincula-lo ao perfil.';
    }
    this.cdr.detectChanges();
  }

}
