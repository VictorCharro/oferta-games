import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom, Subscription } from 'rxjs';
import { AuthService } from '../../services/auth';
import { SeoService } from '../../services/seo';
import { supabase } from '../../services/supabase';
import { mensagemDaApi } from '../../services/mensagem-api';
import { MinhaMensagem, NotificationsService } from '../../services/notifications';
import { irParaLogin } from '../../services/ir-para-login';
import { URL_API } from '../../configuracao/url-api';

type TipoMensagem = MinhaMensagem['tipo'];

/**
 * Fale conosco: elogio, sugestao, problema, denuncia. Vai pra tabela contact_messages e aparece
 * em /admin/coleta (ControladorContato no backend).
 *
 * Funciona sem login de proposito — quem nao consegue entrar e quem mais precisa relatar. Mas a
 * resposta so chega pra quem mandou logado: ela e entregue no site (sino de notificacoes e "Minhas
 * mensagens" aqui), ja que ainda nao ha envio de e-mail.
 */
@Component({
  selector: 'app-contato',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './contato.html',
  styleUrl: './contato.scss',
})
export class Contato implements OnInit, OnDestroy {
  readonly tipos: Array<{ id: TipoMensagem; rotulo: string; dica: string }> = [
    { id: 'sugestao', rotulo: 'Sugestão', dica: 'O que faria o site ser mais útil pra você?' },
    { id: 'problema', rotulo: 'Problema', dica: 'O que aconteceu e o que você esperava? Se puder, diga o jogo ou a página.' },
    { id: 'elogio', rotulo: 'Elogio', dica: 'Conta o que você curtiu, ajuda a saber o que manter.' },
    { id: 'denuncia', rotulo: 'Denúncia', dica: 'Preço errado, link suspeito, conteúdo impróprio.\nPra perfil, use também o ⚑ no próprio perfil.' },
    { id: 'outro', rotulo: 'Outro', dica: 'Pode escrever o que quiser.' },
  ];
  readonly rotuloTipo = Object.fromEntries(this.tipos.map(t => [t.id, t.rotulo])) as Record<TipoMensagem, string>;

  tipo: TipoMensagem = 'sugestao';
  mensagem = '';
  /** Campo isca: escondido de pessoas, bots preenchem. */
  site = '';
  enviando = false;
  enviada = false;
  erro = '';
  sessaoPronta = false;

  minhas: MinhaMensagem[] = [];
  /** Respostas que estavam nao lidas ao abrir a pagina: ficam destacadas mesmo depois de marcadas. */
  novas = new Set<number>();

  private paginaOrigem: string | null = null;
  private sub?: Subscription;
  private fragmentoSub?: Subscription;

  constructor(
    private http: HttpClient,
    public auth: AuthService,
    private notificacoes: NotificationsService,
    private route: ActivatedRoute,
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
    this.sessaoPronta = true;
    this.sub = this.notificacoes.mensagens$.subscribe(lista => { this.minhas = lista; this.cdr.detectChanges(); });
    if (this.auth.isLoggedIn) {
      await this.notificacoes.loadMensagens();
      await this.verRespostas();
      // Clicar na notificacao estando ja em /contato so muda o fragmento: a pagina nao recarrega.
      this.fragmentoSub = this.route.fragment.subscribe(f => { if (f === 'minhas') this.verRespostas(true); });
    }
    this.cdr.detectChanges();
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    this.fragmentoSub?.unsubscribe();
  }

  private async verRespostas(rolar = false) {
    for (const m of this.notificacoes.respostasNaoLidas) this.novas.add(m.id);
    await this.notificacoes.marcarRespostasLidas().catch(() => undefined);
    this.cdr.detectChanges();
    if (rolar) setTimeout(() => document.getElementById('minhas')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  get dicaAtual(): string {
    return this.tipos.find(t => t.id === this.tipo)?.dica ?? '';
  }

  entrar() {
    irParaLogin(this.router, 'receber a resposta no site');
  }

  async enviar() {
    this.erro = '';
    const texto = this.mensagem.trim();
    if (texto.length < 10) {
      this.erro = 'Escreva pelo menos 10 caracteres pra gente entender a mensagem.';
      this.cdr.detectChanges();
      return;
    }
    this.enviando = true;
    this.cdr.detectChanges();
    try {
      const { data } = await supabase.auth.getSession();
      const token = data.session?.access_token;
      await firstValueFrom(this.http.post(`${URL_API}/contato`,
        { tipo: this.tipo, mensagem: texto, pagina: this.paginaOrigem, site: this.site },
        { headers: token ? { Authorization: `Bearer ${token}` } : {} }));
      this.enviada = true;
      this.mensagem = '';
      if (token) this.notificacoes.loadMensagens();
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
