import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpEventType } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
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
    this.limparAnexos();
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
    this.progresso = null;
    this.cdr.detectChanges();
    try {
      const { data } = await supabase.auth.getSession();
      const token = data.session?.access_token;
      const corpo = new FormData();
      corpo.append('tipo', this.tipo);
      corpo.append('mensagem', texto);
      if (this.paginaOrigem) corpo.append('pagina', this.paginaOrigem);
      corpo.append('site', this.site);
      for (const anexo of this.anexos) corpo.append('anexos', anexo.arquivo, anexo.arquivo.name);
      await new Promise<void>((resolve, reject) => {
        this.http.post(`${URL_API}/contato`, corpo, {
          headers: token ? { Authorization: `Bearer ${token}` } : {},
          reportProgress: true,
          observe: 'events',
        }).subscribe({
          next: evento => {
            // Barra so faz sentido com anexo: sem ele a requisicao e instantanea.
            if (evento.type === HttpEventType.UploadProgress && this.anexos.length && evento.total) {
              this.progresso = Math.round((evento.loaded / evento.total) * 100);
              this.cdr.detectChanges();
            }
          },
          error: reject,
          complete: resolve,
        });
      });
      this.enviada = true;
      this.mensagem = '';
      this.limparAnexos();
      if (token) this.notificacoes.loadMensagens();
    } catch (erro) {
      this.erro = mensagemDaApi(erro, 'Não foi possível enviar agora. Tente de novo em instantes.');
    }
    this.enviando = false;
    this.progresso = null;
    this.cdr.detectChanges();
  }

  // ---------- Anexos ----------
  // Mesmos limites do backend (ArmazenamentoAnexos), conferidos aqui pra avisar antes de subir 50 MB
  // a toa. O backend confere de novo pelo conteudo do arquivo.
  readonly maxAnexos = 3;
  anexos: Array<{ arquivo: File; url: string; video: boolean }> = [];
  progresso: number | null = null;

  aoEscolherArquivos(evento: Event) {
    const input = evento.target as HTMLInputElement;
    const arquivos = Array.from(input.files ?? []);
    input.value = '';
    this.erro = '';
    for (const arquivo of arquivos) {
      if (this.anexos.length >= this.maxAnexos) { this.erro = `Envie no máximo ${this.maxAnexos} anexos.`; break; }
      const video = arquivo.type.startsWith('video/');
      const imagem = arquivo.type.startsWith('image/');
      if (!video && !imagem) { this.erro = `"${arquivo.name}" não é imagem nem vídeo.`; continue; }
      const limiteMb = video ? 50 : 8;
      if (arquivo.size > limiteMb * 1024 * 1024) { this.erro = `"${arquivo.name}" passa de ${limiteMb} MB.`; continue; }
      this.anexos.push({ arquivo, url: URL.createObjectURL(arquivo), video });
    }
    this.cdr.detectChanges();
  }

  removerAnexo(indice: number) {
    const [removido] = this.anexos.splice(indice, 1);
    if (removido) URL.revokeObjectURL(removido.url);
    this.cdr.detectChanges();
  }

  private limparAnexos() {
    this.anexos.forEach(a => URL.revokeObjectURL(a.url));
    this.anexos = [];
  }

  tamanho(bytes: number): string {
    return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  novaMensagem() {
    this.enviada = false;
    this.cdr.detectChanges();
  }
}
