import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../services/auth';
import { SeoService } from '../../services/seo';
import { supabase } from '../../services/supabase';
import { mensagemDaApi } from '../../services/mensagem-api';
import { URL_API } from '../../configuracao/url-api';

type TipoMensagem = 'elogio' | 'sugestao' | 'problema' | 'denuncia' | 'outro';

/**
 * Fale conosco: elogio, sugestao, problema, denuncia. Vai pra tabela contact_messages e aparece
 * em /admin/coleta (ControladorContato no backend).
 *
 * Funciona sem login de proposito — quem nao consegue entrar e quem mais precisa relatar. Logado,
 * o e-mail da conta ja vem preenchido e a mensagem fica ligada ao perfil.
 */
@Component({
  selector: 'app-contato',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './contato.html',
  styleUrl: './contato.scss',
})
export class Contato implements OnInit {
  readonly tipos: Array<{ id: TipoMensagem; rotulo: string; dica: string }> = [
    { id: 'sugestao', rotulo: 'Sugestão', dica: 'O que faria o site ser mais útil pra você?' },
    { id: 'problema', rotulo: 'Problema', dica: 'O que aconteceu e o que você esperava? Se puder, diga o jogo ou a página.' },
    { id: 'elogio', rotulo: 'Elogio', dica: 'Conta o que você curtiu — ajuda a saber o que manter.' },
    { id: 'denuncia', rotulo: 'Denúncia', dica: 'Preço errado, link suspeito, conteúdo impróprio... Diga onde está. Pra perfil, use também o ⚑ no próprio perfil.' },
    { id: 'outro', rotulo: 'Outro', dica: 'Pode escrever o que quiser.' },
  ];

  tipo: TipoMensagem = 'sugestao';
  mensagem = '';
  email = '';
  /** Campo isca: escondido de pessoas, bots preenchem. */
  site = '';
  enviando = false;
  enviada = false;
  erro = '';
  private paginaOrigem: string | null = null;

  constructor(
    private http: HttpClient,
    public auth: AuthService,
    private router: Router,
    private seo: SeoService,
    private cdr: ChangeDetectorRef
  ) {
    // Pagina de onde a pessoa veio, passada pelo link da sidebar via state (nao polui a URL).
    const de = this.router.getCurrentNavigation()?.extras.state?.['de'];
    this.paginaOrigem = typeof de === 'string' && de !== '/contato' ? de : null;
  }

  async ngOnInit() {
    this.seo.set({ title: 'Fale conosco', description: 'Mande sugestões, elogios, problemas ou denúncias sobre o Oferta Games.', path: '/contato' });
    await this.auth.aguardarSessaoInicial();
    if (!this.email && this.auth.user?.email) this.email = this.auth.user.email;
    this.cdr.detectChanges();
  }

  get dicaAtual(): string {
    return this.tipos.find(t => t.id === this.tipo)?.dica ?? '';
  }

  async enviar() {
    this.erro = '';
    const texto = this.mensagem.trim();
    if (texto.length < 10) { this.erro = 'Escreva pelo menos 10 caracteres.'; return; }
    this.enviando = true;
    this.cdr.detectChanges();
    try {
      const { data } = await supabase.auth.getSession();
      const token = data.session?.access_token;
      await firstValueFrom(this.http.post(`${URL_API}/contato`,
        { tipo: this.tipo, mensagem: texto, email: this.email.trim(), pagina: this.paginaOrigem, site: this.site },
        { headers: token ? { Authorization: `Bearer ${token}` } : {} }));
      this.enviada = true;
      this.mensagem = '';
    } catch (erro) {
      this.erro = mensagemDaApi(erro, 'Não foi possível enviar agora. Tente de novo em instantes.');
    }
    this.enviando = false;
    this.cdr.detectChanges();
  }

  novaMensagem() {
    this.enviada = false;
    this.cdr.detectChanges();
  }
}
