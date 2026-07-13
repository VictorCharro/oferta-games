import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AuthService } from '../../services/auth';
import { PerfilPublico, PerfisService } from '../../services/perfis';
import { ProfileFavoritesService } from '../../services/profile-favorites';
import { supabase } from '../../services/supabase';

@Component({
  selector: 'app-public-profile',
  standalone: false,
  templateUrl: './public-profile.html',
  styleUrl: './public-profile.scss',
})
export class PublicProfile implements OnInit {
  profile: PerfilPublico | null = null;
  missing = false;
  loading = true;
  activeTab: 'resumo' | 'jogosFavoritos' | 'biblioteca' = 'resumo';
  isOwner = false;
  ownerAvatar = '';
  editingBio = false;
  bioDraft = '';
  savingBio = false;
  refreshing = false;
  message = '';
  librarySearch = '';
  libraryOrder: 'tempo' | 'nome' | 'conquistas' = 'tempo';

  constructor(
    private route: ActivatedRoute,
    private perfis: PerfisService,
    private profileFavorites: ProfileFavoritesService,
    public auth: AuthService,
    private cdr: ChangeDetectorRef,
  ) {}

  async ngOnInit() {
    try {
      this.profile = await this.perfis.publico(this.route.snapshot.paramMap.get('handle') || '');
      this.profile.favoritos ??= [];
      this.profile.atividades ??= [];
      this.bioDraft = this.profile.bio || '';

      try {
        const own = await this.perfis.proprio();
        this.isOwner = Boolean(own?.handle && own.handle === this.profile?.handle);
        this.ownerAvatar = this.isOwner ? this.auth.avatarUrl : '';
        if (this.isOwner && this.profile) this.profile.favoritos = await this.profileFavorites.load();
      } catch {
        this.isOwner = false;
      }
    } catch {
      this.missing = true;
    } finally {
      this.loading = false;
      this.cdr.detectChanges();
    }
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
    if (game.capaUrl) return game.capaUrl;
    return game.steamAppId != null && game.iconeHash
      ? `https://media.steampowered.com/steamcommunity/public/images/apps/${game.steamAppId}/${game.iconeHash}.jpg`
      : 'store-logos/steam.svg';
  }

  favoriteUrl(game: PerfilPublico['favoritos'][number]): string {
    return game.slug ? `/jogo/${game.slug}` : `https://store.steampowered.com/app/${game.steamAppId}`;
  }

  activityIcon(type: string): string {
    if (type.startsWith('MONITORAMENTO')) return 'jogos-monitorados.png';
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
      await this.perfis.atualizarAvatar(avatarUrl);
      this.ownerAvatar = avatarUrl;
      this.profile.avatarUrl = avatarUrl;
      this.auth.updateAvatar(avatarUrl);
      input.value = '';
    } catch {
      this.message = 'A foto foi enviada, mas nao foi possivel vincula-la ao perfil.';
    }
    this.cdr.detectChanges();
  }

}
