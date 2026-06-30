import { Component, ChangeDetectorRef } from '@angular/core';
import { AuthService } from '../../services/auth';
import { supabase } from '../../services/supabase';

@Component({
  selector: 'app-profile',
  standalone: false,
  templateUrl: './profile.html',
  styleUrl: './profile.scss',
})
export class Profile {
  name = '';
  email = '';
  saving = false;
  success = '';
  error = '';

  constructor(public auth: AuthService, private cdr: ChangeDetectorRef) {
    this.name = auth.displayName;
    this.email = auth.user?.email ?? '';
  }

  async save() {
    this.saving = true; this.success = ''; this.error = '';
    const { error } = await supabase.auth.updateUser({ data: { name: this.name } });
    this.saving = false;
    if (error) { this.error = 'Erro ao salvar. Tente novamente.'; }
    else { this.success = 'Perfil atualizado com sucesso!'; }
    this.cdr.detectChanges();
  }
}
