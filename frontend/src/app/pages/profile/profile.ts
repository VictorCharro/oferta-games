import { Component, ChangeDetectorRef, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth';
import { supabase } from '../../services/supabase';
import { FavoriteGame, FavoritesService } from '../../services/favorites';
import { Subscription } from 'rxjs';
import { ConexoesSteamService, JogoBibliotecaSteam, StatusSteam } from '../../services/conexoes-steam';
import { PerfisService } from '../../services/perfis';

@Component({
  selector: 'app-profile',
  standalone: false,
  templateUrl: './profile.html',
  styleUrl: './profile.scss',
})
export class Profile implements OnInit, OnDestroy {
  name = '';
  email = '';
  bio = '';
  bioDraft = '';
  editingBio = false;
  avatarUrl = '';
  saving = false;
  savingBio = false;
  success = '';
  error = '';
  activeTab: 'resumo' | 'jogosFavoritos' | 'biblioteca' = 'resumo';
  favorites: FavoriteGame[] = [];
  platformHours: Array<{ name: string; logo: string; hours: string }> = [];
  steamStatus: StatusSteam | null = null;
  libraryGames: JogoBibliotecaSteam[] = [];
  publicProfileUrl = '';
  private favoritesSub?: Subscription;

  constructor(
    public auth: AuthService,
    private favoritesService: FavoritesService,
    private steamService: ConexoesSteamService,
    private perfisService: PerfisService,
    private router: Router,
    private cdr: ChangeDetectorRef
  ) {
    this.name = auth.displayName;
    this.email = auth.user?.email ?? '';
    this.bio = auth.user?.user_metadata?.['bio'] || '';
    this.bioDraft = this.bio;
    this.avatarUrl = auth.avatarUrl;
  }

  async ngOnInit() {
    this.favoritesSub = this.favoritesService.list$.subscribe(list => {
      this.favorites = list;
      this.cdr.detectChanges();
    });
    this.favoritesSub.add(this.auth.avatar$.subscribe(avatarUrl => {
      this.avatarUrl = avatarUrl;
      this.cdr.detectChanges();
    }));
    this.favoritesService.load();
    if (await this.redirecionarParaPerfilCanonico()) return;
    this.loadSteam();
    this.loadPublicProfile();
  }

  private async redirecionarParaPerfilCanonico(): Promise<boolean> {
    try {
      let profile = await this.perfisService.proprio();
      if (!profile?.handle) {
        const base = this.auth.displayName.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'jogador';
        const handle = `${base}-${this.auth.user?.id.slice(0, 4) || 'user'}`;
        await this.perfisService.salvar({ handle, nomeExibicao: this.auth.displayName, bio: this.bio, publico: true, mostrarHoras: true, mostrarConquistas: true, mostrarBiblioteca: true, mostrarFavoritos: true, mostrarAtividades: true });
        profile = await this.perfisService.proprio();
      }
      if (profile?.handle) { await this.router.navigateByUrl(`/${profile.handle}`, { replaceUrl: true }); return true; }
    } catch { /* Mantem /perfil como fallback caso a configuracao ainda nao exista. */ }
    return false;
  }

  async loadSteam() {
    try {
      this.steamStatus = await this.steamService.status();
      if (this.steamStatus.conectada) {
        this.platformHours = [{ name: 'Steam', logo: 'store-logos/steam.svg', hours: this.formatHours(this.steamStatus.totalMinutos) }];
        this.libraryGames = await this.steamService.biblioteca();
      }
    } catch { this.steamStatus = null; }
    this.cdr.detectChanges();
  }

  async loadPublicProfile() {
    try {
      const profile = await this.perfisService.proprio();
      this.publicProfileUrl = profile?.publico && profile.handle ? `${window.location.origin}/${profile.handle}` : '';
    } catch { this.publicProfileUrl = ''; }
    this.cdr.detectChanges();
  }

  async copyPublicProfileUrl() {
    if (!this.publicProfileUrl) return;
    try {
      await navigator.clipboard.writeText(this.publicProfileUrl);
      this.success = 'Link do perfil copiado.';
    } catch {
      this.error = 'Não foi possível copiar o link.';
    }
    this.cdr.detectChanges();
  }

  formatHours(minutes: number): string { return `${Math.floor(minutes / 60)}h ${minutes % 60}m`; }

  steamIcon(game: JogoBibliotecaSteam): string { return game.iconeHash ? `https://media.steampowered.com/steamcommunity/public/images/apps/${game.appId}/${game.iconeHash}.jpg` : 'store-logos/steam.svg'; }

  ngOnDestroy() {
    this.favoritesSub?.unsubscribe();
  }

  get initial(): string {
    return (this.auth.displayName || this.name || 'U').charAt(0).toUpperCase();
  }

  get bioLabel(): string {
    return this.bio || 'Adicionar uma bio';
  }

  get latestMonitoredGames(): FavoriteGame[] {
    return this.favorites.slice(0, 3);
  }

  get monitoredCount(): number {
    return this.favorites.length;
  }

  get activityItems(): Array<{ icon: string; title: string; time: string; tone: string }> {
    const items = this.latestMonitoredGames.map((game, index) => ({
      icon: 'jogos-monitorados.png',
      title: `Adicionou ${game.title} aos jogos monitorados`,
      time: index === 0 ? 'recentemente' : 'monitorado',
      tone: 'favorite',
    }));

    if (items.length) return items;

    return [
      {
        icon: 'jogos-monitorados.png',
        title: 'Seus jogos monitorados aparecerão aqui',
        time: 'comece pelo catálogo',
        tone: 'muted',
      },
    ];
  }

  async save() {
    this.saving = true; this.success = ''; this.error = '';
    const { error } = await supabase.auth.updateUser({ data: { name: this.name } });
    this.saving = false;
    if (error) { this.error = 'Erro ao salvar. Tente novamente.'; }
    else { this.success = 'Perfil atualizado com sucesso!'; }
    this.cdr.detectChanges();
  }

  editBio() {
    this.bioDraft = this.bio;
    this.editingBio = true;
  }

  cancelBio() {
    this.bioDraft = this.bio;
    this.editingBio = false;
  }

  async saveBio() {
    const nextBio = this.bioDraft.trim();
    this.savingBio = true;
    this.success = '';
    this.error = '';

    const currentMetadata = this.auth.user?.user_metadata ?? {};
    const { error } = await supabase.auth.updateUser({
      data: { ...currentMetadata, bio: nextBio },
    });

    this.savingBio = false;
    if (error) {
      this.error = 'Erro ao salvar a bio. Tente novamente.';
    } else {
      this.bio = nextBio;
      this.bioDraft = nextBio;
      this.editingBio = false;
      this.success = 'Bio atualizada com sucesso!';
    }
    this.cdr.detectChanges();
  }

  onAvatarSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    if (!file.type.startsWith('image/')) {
      this.error = 'Selecione um arquivo de imagem.';
      input.value = '';
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      this.avatarUrl = String(reader.result || '');
      this.auth.updateAvatar(this.avatarUrl);
      this.success = '';
      this.error = '';
      input.value = '';
      this.cdr.detectChanges();
    };
    reader.readAsDataURL(file);
  }

  formatPrice(price: number | string | null | undefined): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return 'Preço indisponível';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }
}
