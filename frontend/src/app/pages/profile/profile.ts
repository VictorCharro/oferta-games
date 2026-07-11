import { Component, ChangeDetectorRef, OnDestroy, OnInit } from '@angular/core';
import { AuthService } from '../../services/auth';
import { supabase } from '../../services/supabase';
import { FavoriteGame, FavoritesService } from '../../services/favorites';
import { Subscription } from 'rxjs';
import { ConexoesSteamService, StatusSteam } from '../../services/conexoes-steam';

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
  private favoritesSub?: Subscription;

  constructor(
    public auth: AuthService,
    private favoritesService: FavoritesService,
    private steamService: ConexoesSteamService,
    private cdr: ChangeDetectorRef
  ) {
    this.name = auth.displayName;
    this.email = auth.user?.email ?? '';
    this.bio = auth.user?.user_metadata?.['bio'] || '';
    this.bioDraft = this.bio;
    this.avatarUrl = auth.avatarUrl;
  }

  ngOnInit() {
    this.favoritesSub = this.favoritesService.list$.subscribe(list => {
      this.favorites = list;
      this.cdr.detectChanges();
    });
    this.favoritesSub.add(this.auth.avatar$.subscribe(avatarUrl => {
      this.avatarUrl = avatarUrl;
      this.cdr.detectChanges();
    }));
    this.favoritesService.load();
    this.loadSteam();
  }

  async loadSteam() {
    try {
      this.steamStatus = await this.steamService.status();
      if (this.steamStatus.conectada) this.platformHours = [{ name: 'Steam', logo: 'store-logos/steam.svg', hours: this.formatHours(this.steamStatus.totalMinutos) }];
    } catch { this.steamStatus = null; }
    this.cdr.detectChanges();
  }

  formatHours(minutes: number): string { return `${Math.floor(minutes / 60)}h ${minutes % 60}m`; }

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
      icon: 'favorito.png',
      title: `Adicionou ${game.title} aos jogos monitorados`,
      time: index === 0 ? 'recentemente' : 'monitorado',
      tone: 'favorite',
    }));

    if (items.length) return items;

    return [
      {
        icon: 'favorito.png',
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
