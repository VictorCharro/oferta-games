import { Component, ChangeDetectorRef } from '@angular/core';
import { AuthService } from '../../services/auth';
import { supabase } from '../../services/supabase';

@Component({
  selector: 'app-settings',
  standalone: false,
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class Settings {
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  saving = false;
  success = '';
  error = '';

  constructor(public auth: AuthService, private cdr: ChangeDetectorRef) {}

  async changePassword() {
    this.success = ''; this.error = '';
    if (!this.currentPassword || !this.newPassword || !this.confirmPassword) { this.error = 'Preencha todos os campos.'; return; }
    if (this.newPassword !== this.confirmPassword) { this.error = 'As senhas não coincidem.'; return; }
    if (this.newPassword.length < 6) { this.error = 'A senha deve ter pelo menos 6 caracteres.'; return; }

    this.saving = true;

    const email = this.auth.user?.email;
    const { error: authError } = await supabase.auth.signInWithPassword({ email: email!, password: this.currentPassword });
    if (authError) {
      this.saving = false;
      this.error = 'Senha atual incorreta.';
      this.cdr.detectChanges();
      return;
    }

    const { error } = await supabase.auth.updateUser({ password: this.newPassword });
    this.saving = false;
    if (error) { this.error = 'Erro ao alterar senha. Tente novamente.'; }
    else {
      this.success = 'Senha alterada com sucesso!';
      this.currentPassword = ''; this.newPassword = ''; this.confirmPassword = '';
    }
    this.cdr.detectChanges();
  }

  logout() { this.auth.logout(); }
}
