import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../services/auth';
import { SeoService } from '../../services/seo';

@Component({
  selector: 'app-login',
  imports: [CommonModule, FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login implements OnInit {
  email = '';
  password = '';
  name = '';
  error = '';
  success = '';
  loading = false;
  mode: 'login' | 'register' = 'login';

  constructor(
    private auth: AuthService,
    private router: Router,
    private route: ActivatedRoute,
    private cdr: ChangeDetectorRef,
    private seo: SeoService
  ) {}

  ngOnInit() {
    if (this.auth.isLoggedIn) this.router.navigate(['/']);
    this.route.queryParamMap.subscribe(p => {
      if (p.get('mode') === 'register') this.mode = 'register';
      this.seo.set({
        title: this.mode === 'register' ? 'Criar conta' : 'Entrar',
        description: 'Entre ou crie sua conta no Oferta Games para monitorar preços e montar seu perfil gamer.',
        noindex: true,
      });
    });
  }

  loginWithGoogle() { this.auth.loginWithGoogle(); }
  loginWithDiscord() { this.auth.loginWithDiscord(); }

  async submit() {
    this.error = '';
    this.success = '';
    if (!this.email || !this.password) { this.error = 'Preencha todos os campos.'; return; }
    if (this.mode === 'register' && !this.name) { this.error = 'Informe seu nome.'; return; }

    this.loading = true;
    this.cdr.detectChanges();

    if (this.mode === 'login') {
      const err = await this.auth.login(this.email, this.password);
      if (err) { this.error = this.translateError(err); this.loading = false; this.cdr.detectChanges(); return; }
      this.router.navigate(['/']);
    } else {
      const err = await this.auth.register(this.email, this.password, this.name);
      if (err) { this.error = this.translateError(err); this.loading = false; this.cdr.detectChanges(); return; }
      this.success = 'Conta criada! Verifique seu e-mail para confirmar.';
      this.loading = false;
      this.cdr.detectChanges();
    }
  }

  private translateError(msg: string): string {
    if (msg.includes('Invalid login')) return 'E-mail ou senha incorretos.';
    if (msg.includes('already registered')) return 'Este e-mail já está cadastrado.';
    if (msg.includes('Password should')) return 'A senha deve ter pelo menos 6 caracteres.';
    if (msg.includes('valid email')) return 'Informe um e-mail válido.';
    return msg;
  }
}
