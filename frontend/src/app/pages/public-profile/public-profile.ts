import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AuthService } from '../../services/auth';
import { PerfilPublico, PerfisService } from '../../services/perfis';
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
  message = '';

  constructor(
    private route: ActivatedRoute,
    private perfis: PerfisService,
    public auth: AuthService,
    private cdr: ChangeDetectorRef,
  ) {}

  async ngOnInit() {
    try {
      this.profile = await this.perfis.publico(this.route.snapshot.paramMap.get('handle') || '');
      this.bioDraft = this.profile.bio || '';

      try {
        const own = await this.perfis.proprio();
        this.isOwner = Boolean(own?.handle && own.handle === this.profile?.handle);
        this.ownerAvatar = this.isOwner ? this.auth.avatarUrl : '';
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

    const reader = new FileReader();
    reader.onload = () => {
      this.ownerAvatar = String(reader.result || '');
      this.auth.updateAvatar(this.ownerAvatar);
      input.value = '';
      this.message = '';
      this.cdr.detectChanges();
    };
    reader.readAsDataURL(file);
  }

  get publicUrl(): string {
    return this.profile ? `${window.location.origin}/${this.profile.handle}` : '';
  }

  async copyPublicUrl() {
    try {
      await navigator.clipboard.writeText(this.publicUrl);
      this.message = 'Link do perfil copiado.';
    } catch {
      this.message = 'Nao foi possivel copiar o link.';
    }
    this.cdr.detectChanges();
  }
}
