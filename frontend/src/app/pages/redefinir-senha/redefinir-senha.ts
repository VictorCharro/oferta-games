import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth';
import { SeoService } from '../../services/seo';
import { supabase } from '../../services/supabase';

/**
 * Destino do link de "Esqueci minha senha" (issue #17).
 *
 * O supabase-js le o token da URL sozinho e abre uma sessao de recuperacao; aqui so falta pedir a
 * senha nova. Sem sessao (link expirado, ja usado, ou aberto em outro navegador) nao ha como trocar
 * a senha, e a tela diz isso em vez de mostrar um formulario que falharia.
 */
@Component({
  selector: 'app-redefinir-senha',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './redefinir-senha.html',
  styleUrl: '../login/login.scss',
})
export class RedefinirSenha implements OnInit {
  senha = '';
  confirmacao = '';
  erro = '';
  sucesso = false;
  salvando = false;
  /** null = ainda conferindo a sessao do link. */
  linkValido: boolean | null = null;

  constructor(
    private auth: AuthService,
    private router: Router,
    private seo: SeoService,
    private cdr: ChangeDetectorRef
  ) {}

  async ngOnInit() {
    this.seo.set({ title: 'Criar nova senha', description: 'Defina uma nova senha para sua conta.', noindex: true });
    await this.auth.aguardarSessaoInicial();
    // O token do link pode levar um instante pra virar sessao depois do carregamento.
    const { data } = await supabase.auth.getSession();
    this.linkValido = !!data.session;
    this.cdr.detectChanges();
  }

  // Enquanto a recuperacao esta aberta o site prende nesta tela (ver AuthService): esta e a saida
  // pra quem clicou no link sem querer e lembrou da senha atual.
  async sair() {
    await this.auth.logout();
    this.router.navigate(['/login']);
  }

  async salvar() {
    this.erro = '';
    if (this.senha.length < 8) { this.erro = 'A nova senha precisa ter pelo menos 8 caracteres.'; return; }
    if (this.senha !== this.confirmacao) { this.erro = 'As senhas não conferem.'; return; }
    this.salvando = true;
    this.cdr.detectChanges();
    const falha = await this.auth.definirNovaSenha(this.senha);
    this.salvando = false;
    if (falha) {
      this.erro = falha.includes('different from the old')
        ? 'A nova senha precisa ser diferente da anterior.'
        : 'Não foi possível salvar a nova senha. Peça um novo link e tente de novo.';
    } else {
      this.sucesso = true;
      setTimeout(() => this.router.navigateByUrl('/'), 2500);
    }
    this.cdr.detectChanges();
  }
}
