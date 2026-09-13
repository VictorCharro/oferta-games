import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth';
import { SeoService } from '../../services/seo';

type ModoLogin = 'login' | 'register' | 'recuperar';

/**
 * Caminho interno pra onde voltar depois de entrar. So aceita caminho relativo ao proprio site
 * ("/jogo/x"): "//outro.com" ou "https://..." viraria redirecionamento aberto pra phishing.
 */
export function destinoSeguro(valor: string | null | undefined): string {
  if (!valor || !valor.startsWith('/') || valor.startsWith('//') || valor.startsWith('/\\')) return '/';
  if (valor.startsWith('/login')) return '/';
  return valor;
}

@Component({
  selector: 'app-login',
  imports: [CommonModule, FormsModule, RouterLink],
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
  mode: ModoLogin = 'login';
  /** Cadastro feito agora: mostra "reenviar e-mail" enquanto a pessoa nao confirma. */
  aguardandoConfirmacao = false;
  /** Pra onde voltar depois de entrar (ex.: o jogo que a pessoa tentou favoritar). */
  destino = '/';
  /** Motivo de ter caido no login, vindo da acao que exigiu conta ("favoritar este jogo"). */
  motivo = '';

  constructor(
    private auth: AuthService,
    private router: Router,
    private route: ActivatedRoute,
    private cdr: ChangeDetectorRef,
    private seo: SeoService
  ) {}

  ngOnInit() {
    this.route.queryParamMap.subscribe(p => {
      this.destino = destinoSeguro(p.get('returnUrl'));
      this.motivo = (p.get('motivo') ?? '').slice(0, 80);
      if (this.auth.isLoggedIn) { this.router.navigateByUrl(this.destino); return; }
      if (p.get('mode') === 'register') this.mode = 'register';
      this.seo.set({
        title: this.mode === 'register' ? 'Criar conta' : 'Entrar',
        description: 'Entre ou crie sua conta no Oferta Games para monitorar preços e montar seu perfil gamer.',
        noindex: true,
      });
    });
  }

  trocarModo(modo: ModoLogin) {
    this.mode = modo;
    this.error = '';
    this.success = '';
    this.aguardandoConfirmacao = false;
  }

  loginWithGoogle() { this.auth.loginWithGoogle(this.destino); }
  loginWithDiscord() { this.auth.loginWithDiscord(this.destino); }

  async submit() {
    this.error = '';
    this.success = '';
    if (this.mode === 'recuperar') return this.recuperar();
    if (!this.email || !this.password) { this.error = 'Preencha todos os campos.'; return; }
    if (this.mode === 'register' && !this.name) { this.error = 'Informe seu nome.'; return; }

    this.loading = true;
    this.cdr.detectChanges();

    if (this.mode === 'login') {
      const err = await this.auth.login(this.email, this.password);
      if (err) {
        this.error = this.translateError(err);
        // Conta criada mas nao confirmada: oferece o reenvio ali mesmo, sem mandar criar de novo.
        this.aguardandoConfirmacao = err.includes('Email not confirmed');
        this.loading = false;
        this.cdr.detectChanges();
        return;
      }
      this.router.navigateByUrl(this.destino);
    } else {
      const err = await this.auth.register(this.email, this.password, this.name);
      if (err) { this.error = this.translateError(err); this.loading = false; this.cdr.detectChanges(); return; }
      this.success = 'Conta criada! Enviamos um link de confirmação para o seu e-mail.';
      this.aguardandoConfirmacao = true;
      this.loading = false;
      this.cdr.detectChanges();
    }
  }

  private async recuperar() {
    if (!this.email) { this.error = 'Informe o e-mail da sua conta.'; return; }
    this.loading = true;
    this.cdr.detectChanges();
    const err = await this.auth.solicitarRedefinicaoSenha(this.email);
    this.loading = false;
    // Mesmo texto pra e-mail cadastrado ou nao: a tela nao pode confirmar quem tem conta. So erro
    // de limite/formato aparece, porque ai a pessoa precisa fazer algo diferente.
    if (err && !err.includes('User not found')) this.error = this.translateError(err);
    else this.success = 'Se existir uma conta com esse e-mail, você vai receber um link para criar uma nova senha em alguns minutos.';
    this.cdr.detectChanges();
  }

  async reenviarConfirmacao() {
    if (!this.email) return;
    this.loading = true;
    this.cdr.detectChanges();
    const err = await this.auth.reenviarConfirmacao(this.email);
    this.loading = false;
    if (err) this.error = this.translateError(err);
    else this.success = 'Enviamos o link de confirmação de novo. Confira também a caixa de spam.';
    this.cdr.detectChanges();
  }

  private translateError(msg: string): string {
    if (msg.includes('Invalid login')) return 'E-mail ou senha incorretos.';
    if (msg.includes('Email not confirmed')) return 'Confirme seu e-mail antes de entrar.';
    if (msg.includes('already registered')) return 'Este e-mail já está cadastrado.';
    if (msg.includes('Password should')) return 'A senha deve ter pelo menos 6 caracteres.';
    if (msg.includes('valid email') || msg.includes('invalid format')) return 'Informe um e-mail válido.';
    if (msg.includes('rate limit') || msg.includes('only request this after') || msg.includes('Too many')) {
      return 'Muitas tentativas em pouco tempo. Aguarde alguns minutos e tente de novo.';
    }
    if (msg.includes('weak') || msg.includes('pwned') || msg.includes('leaked')) {
      return 'Essa senha é fraca ou já apareceu em vazamentos. Escolha outra.';
    }
    // Mensagem crua do Supabase vem em ingles e sem contexto pro usuario: genérico em pt-BR.
    return 'Não foi possível concluir agora. Tente novamente em instantes.';
  }
}
