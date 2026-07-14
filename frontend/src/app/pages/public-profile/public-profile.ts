import { ChangeDetectorRef, Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { AuthService } from '../../services/auth';
import { PerfilBloco, PerfilPublico, PerfisService } from '../../services/perfis';
import { ProfileFavoritesService } from '../../services/profile-favorites';
import { supabase } from '../../services/supabase';

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
  activeTab: 'resumo' | 'jogosFavoritos' | 'biblioteca' = 'resumo';
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
  editingLayout = false;
  savingLayout = false;
  layoutDraft: PerfilBloco[] = [];
  avatarZoom = 1;
  avatarPositionX = 50;
  avatarPositionY = 50;
  avatarPreview = '';
  avatarEditing = false;
  editorSelectOpen: { blockId: string; campo: 'tamanho' | 'tipoFundo' } | null = null;
  textColorMenuBlockId: string | null = null;
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
    public auth: AuthService,
    private cdr: ChangeDetectorRef,
  ) {}

  ngOnInit() {
    this.routeSub = this.route.paramMap.subscribe(params => {
      void this.loadProfile(params.get('handle') || '');
    });
  }

  ngOnDestroy() {
    this.routeSub?.unsubscribe();
  }

  private async loadProfile(handle: string) {
    const request = ++this.profileRequest;
    this.profile = null;
    this.missing = false;
    this.loading = true;
    this.isOwner = false;
    this.ownerAvatar = '';
    this.editingBio = false;
    this.message = '';
    this.librarySearch = '';
    this.libraryOrder = 'tempo';
    this.activeTab = 'resumo';
    this.cdr.detectChanges();

    try {
      const profile = await this.perfis.publico(handle);
      if (request !== this.profileRequest) return;
      profile.favoritos ??= [];
      profile.atividades ??= [];
      profile.plataformasConectadas ??= [];
      profile.blocos = profile.blocos?.length
        ? profile.blocos
            .filter(block => !['resumo_favoritos', 'horas', 'conquistas'].includes(block.tipo as string))
            .map(block => ({ ...block, corTexto: block.corTexto ?? null }))
        : this.defaultBlocks();
      this.profile = profile;
      this.bioDraft = profile.bio || '';
      this.avatarZoom = profile.avatarZoom || 1;
      this.avatarPositionX = profile.avatarPosicaoX ?? 50;
      this.avatarPositionY = profile.avatarPosicaoY ?? 50;

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
    }
  }

  @HostListener('document:keydown.escape')
  closeEditorSelects() {
    this.editorSelectOpen = null;
    this.textColorMenuBlockId = null;
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
    if (!this.profile || !this.isOwner) return;
    this.layoutDraft = structuredClone(this.profile.blocos?.length ? this.profile.blocos : this.defaultBlocks());
    this.editingLayout = true;
  }

  cancelLayoutEdit() { this.editingLayout = false; this.layoutDraft = []; }

  dropBlock(event: CdkDragDrop<PerfilBloco[]>) {
    moveItemInArray(this.layoutDraft, event.previousIndex, event.currentIndex);
    this.layoutDraft.forEach((block, index) => block.posicao = index);
  }

  addBlock(tipo: PerfilBloco['tipo']) {
    if (['favoritos', 'biblioteca', 'atividade'].includes(tipo) && this.layoutDraft.some(block => block.tipo === tipo)) return;
    const id = `custom-${crypto.randomUUID()}`;
    this.layoutDraft.push({ id: ['favoritos', 'biblioteca', 'atividade'].includes(tipo) ? tipo : id, tipo, titulo: tipo === 'texto' ? 'Novo texto' : tipo === 'imagem' ? 'Imagem' : tipo === 'links' ? 'Links' : null, conteudo: tipo === 'texto' ? 'Escreva algo sobre você.' : '', posicao: this.layoutDraft.length, tamanho: 'medio', visivel: true, tipoFundo: 'padrao', valorFundo: null, opacidade: 0, corTexto: null });
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
    if (file.size > 2 * 1024 * 1024) { this.message = 'Escolha uma imagem de até 2 MB.'; return; }
    const caminho = `${this.auth.user.id}/blocks/${block.id}-${Date.now()}`;
    const { error } = await supabase.storage.from('avatars').upload(caminho, file, { contentType: file.type, cacheControl: '3600' });
    if (error) { this.message = 'Não foi possível enviar a imagem.'; return; }
    const { data } = supabase.storage.from('avatars').getPublicUrl(caminho);
    if (destino === 'fundo') block.valorFundo = data.publicUrl;
    else block.conteudo = data.publicUrl;
    this.cdr.detectChanges();
  }

  links(block: PerfilBloco): Array<{ nome: string; url: string }> {
    return (block.conteudo || '').split('\n').map(linha => linha.split('|').map(valor => valor.trim()))
      .filter(([, url]) => /^https:\/\//i.test(url || ''))
      .map(([nome, url]) => ({ nome: nome || url, url }));
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

  favoritePreview(block: PerfilBloco) {
    return this.profile?.favoritos.slice(0, this.previewLimit(block.tamanho)) || [];
  }

  libraryPreview(block: PerfilBloco) {
    return this.libraryGames.slice(0, this.previewLimit(block.tamanho));
  }

  steamCover(game: PerfilPublico['biblioteca'][number]): string {
    return `https://cdn.akamai.steamstatic.com/steam/apps/${game.appId}/header.jpg`;
  }

  tentarCapaSteamAlternativa(evento: Event, jogo: PerfilPublico['biblioteca'][number]) {
    const imagem = evento.target as HTMLImageElement;
    if (imagem.dataset['capaAlternativa'] !== 'true') {
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

  favoriteImage(game: PerfilPublico['favoritos'][number]): string {
    if (game.steamAppId != null) {
      return `https://cdn.akamai.steamstatic.com/steam/apps/${game.steamAppId}/header.jpg`;
    }
    if (game.capaUrl) return game.capaUrl;
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
    const games = this.profile.biblioteca.filter(game => !query || game.titulo.toLocaleLowerCase('pt-BR').includes(query));
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

  async onAvatarSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || !file.type.startsWith('image/')) return;
    if (file.size > 2 * 1024 * 1024) { this.message = 'Escolha uma imagem de ate 2 MB.'; return; }
    if (!this.auth.user || !this.profile) return;

    this.avatarPreview = URL.createObjectURL(file);
    this.avatarFile = file;
    this.avatarZoom = 1;
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
    this.avatarZoom = this.profile?.avatarZoom || 1;
    this.avatarPositionX = this.profile?.avatarPosicaoX ?? 50;
    this.avatarPositionY = this.profile?.avatarPosicaoY ?? 50;
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

}
