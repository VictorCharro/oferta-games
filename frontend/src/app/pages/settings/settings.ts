import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AuthService } from '../../services/auth';
import { supabase } from '../../services/supabase';
import {
  PreferencesService,
  PrivacyPreferences,
  UserPreferences,
} from '../../services/preferences';
import { ConexoesSteamService, StatusSteam } from '../../services/conexoes-steam';
import { PerfisService } from '../../services/perfis';

type SettingsTab = 'conta' | 'conexoes' | 'preferencias' | 'privacidade';

@Component({
  selector: 'app-settings',
  standalone: false,
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class Settings implements OnInit {
  activeTab: SettingsTab = 'conta';
  name = '';
  bio = '';
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  preferences!: UserPreferences;
  privacy!: PrivacyPreferences;
  saving = false;
  success = '';
  error = '';
  steamStatus: StatusSteam | null = null;
  steamLoading = false;
  profileHandle = '';

  readonly tabs: Array<{ id: SettingsTab; label: string }> = [
    { id: 'conta', label: 'Conta' },
    { id: 'conexoes', label: 'Conexões' },
    { id: 'preferencias', label: 'Preferências' },
    { id: 'privacidade', label: 'Privacidade' },
  ];

  constructor(
    public auth: AuthService,
    private preferencesService: PreferencesService,
    private steamService: ConexoesSteamService,
    private perfisService: PerfisService,
    private route: ActivatedRoute,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    const metadata = this.auth.user?.user_metadata ?? {};
    this.name = metadata['name'] || this.auth.displayName;
    this.bio = metadata['bio'] || '';
    this.preferences = { ...this.preferencesService.preferences };
    this.privacy = { ...this.preferencesService.privacy };
    if (this.route.snapshot.queryParamMap.has('steam')) this.activeTab = 'conexoes';
    this.loadSteam();
    this.loadPublicProfile();
  }

  selectTab(tab: SettingsTab) {
    this.activeTab = tab;
    this.clearMessages();
  }

  async loadSteam() { try { this.steamStatus = await this.steamService.status(); } catch { this.steamStatus = null; } this.cdr.detectChanges(); }
  async connectSteam() { this.steamLoading = true; try { await this.steamService.conectar(); } catch { this.error = 'Nao foi possivel iniciar a conexao com a Steam.'; this.steamLoading = false; this.cdr.detectChanges(); } }
  async syncSteam() { this.steamLoading = true; try { await this.steamService.sincronizar(); this.success = 'Sincronizacao da biblioteca iniciada.'; } catch { this.error = 'Nao foi possivel iniciar a sincronizacao da Steam.'; } this.steamLoading = false; this.cdr.detectChanges(); }
  async disconnectSteam() { this.steamLoading = true; try { await this.steamService.desconectar(); this.steamStatus = null; } catch { this.error = 'Nao foi possivel desconectar a Steam.'; } this.steamLoading = false; this.cdr.detectChanges(); }

  async loadPublicProfile() { try { const perfil = await this.perfisService.proprio(); if (perfil) { this.profileHandle = perfil.handle || ''; this.privacy = { publicProfile: perfil.publico, showGameHours: perfil.mostrarHoras, showAchievements: perfil.mostrarConquistas, showLibrary: perfil.mostrarBiblioteca, showFavoriteGames: perfil.mostrarFavoritos, showRecentActivity: perfil.mostrarAtividades }; } } catch {} this.cdr.detectChanges(); }
  async salvarPrivacidade() { try { await this.salvarPerfilPublico(); this.success = 'Preferencias de privacidade salvas.'; this.error = ''; } catch { this.error = 'Defina uma URL valida e disponivel para publicar o perfil.'; } this.cdr.detectChanges(); }
  perfilPublicoUrl(): string { return this.profileHandle ? `${window.location.origin}/${this.profileHandle}` : ''; }
  private async salvarPerfilPublico() { await this.perfisService.salvar({ handle: this.profileHandle, nomeExibicao: this.name.trim(), bio: this.bio.trim(), publico: this.privacy.publicProfile, mostrarHoras: this.privacy.showGameHours, mostrarConquistas: this.privacy.showAchievements, mostrarBiblioteca: this.privacy.showLibrary, mostrarFavoritos: this.privacy.showFavoriteGames, mostrarAtividades: this.privacy.showRecentActivity }); }

  async saveAccount() {
    this.clearMessages();
    const name = this.name.trim();
    if (!name) { this.error = 'Informe seu nome.'; return; }
    this.saving = true;
    const metadata = this.auth.user?.user_metadata ?? {};
    const { error } = await supabase.auth.updateUser({
      data: { ...metadata, name, bio: this.bio.trim() },
    });
    if (!error) await this.salvarPerfilPublico();
    this.saving = false;
    this.success = error ? '' : 'Informações salvas com sucesso.';
    this.error = error ? 'Não foi possível salvar as informações.' : '';
    this.cdr.detectChanges();
  }

  savePreferences() {
    this.preferencesService.savePreferences(this.preferences);
    this.success = 'Preferências salvas. Elas já serão aplicadas na home e no catálogo.';
    this.error = '';
  }

  savePrivacy() {
    this.preferencesService.savePrivacy(this.privacy);
    this.salvarPrivacidade();
  }

  async changePassword() {
    this.clearMessages();
    if (!this.currentPassword || !this.newPassword || !this.confirmPassword) {
      this.error = 'Preencha todos os campos.';
      return;
    }
    if (this.newPassword !== this.confirmPassword) {
      this.error = 'As senhas não coincidem.';
      return;
    }
    if (this.newPassword.length < 6) {
      this.error = 'A senha deve ter pelo menos 6 caracteres.';
      return;
    }

    this.saving = true;
    const email = this.auth.user?.email;
    const { error: authError } = await supabase.auth.signInWithPassword({
      email: email!,
      password: this.currentPassword,
    });
    if (authError) {
      this.saving = false;
      this.error = 'Senha atual incorreta.';
      this.cdr.detectChanges();
      return;
    }

    const { error } = await supabase.auth.updateUser({ password: this.newPassword });
    this.saving = false;
    if (error) {
      this.error = 'Erro ao alterar senha. Tente novamente.';
    } else {
      this.success = 'Senha alterada com sucesso.';
      this.currentPassword = '';
      this.newPassword = '';
      this.confirmPassword = '';
    }
    this.cdr.detectChanges();
  }

  logout() { this.auth.logout(); }

  private clearMessages() {
    this.success = '';
    this.error = '';
  }
}
