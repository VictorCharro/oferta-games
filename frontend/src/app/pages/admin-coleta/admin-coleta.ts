import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import {
  AdministracaoService,
  AnexoContato,
  DenunciaAberta,
  ErroRegistrado,
  MensagemContato,
  PerfilBloqueado,
  ResultadoPreenchimentoJogo,
  StatusAdministrativoColeta,
  StatusColeta,
  TipoColeta,
} from '../../services/administracao';
import { GameService, GameSummary } from '../../services/game';

type Aba = 'geral' | 'moderacao' | 'mensagens' | 'erros' | 'coleta' | 'ferramentas';
type GrupoColeta = 'precos-steam' | 'detalhes-conquistas' | 'instant-gaming';
type FiltroMensagem = 'todas' | MensagemContato['tipo'] | 'lidas';

interface CartaoColeta {
  titulo: string;
  tipo: TipoColeta;
  coleta: StatusColeta;
}

/** Denuncias do mesmo perfil juntas: moderar e decidir uma vez por perfil, nao por denuncia. */
interface GrupoDenuncias {
  handle: string | null;
  nomeExibicao: string | null;
  perfilBloqueado: boolean;
  denuncias: DenunciaAberta[];
  ultimaEm: string;
}

interface ItemAtencao {
  tipo: 'erro' | 'denuncia' | 'mensagem';
  titulo: string;
  detalhe: string;
  aba: Aba;
  acao: string;
}

const ABAS: Aba[] = ['geral', 'moderacao', 'mensagens', 'erros', 'coleta', 'ferramentas'];

/**
 * Painel de administracao (/admin/coleta): resumo no topo e abas por area — visao geral,
 * moderacao de perfis, mensagens do Fale conosco, coletas do catalogo e ferramentas.
 * A aba ativa fica na URL (?aba=) pra recarregar sem perder o lugar.
 */
@Component({
  selector: 'app-admin-coleta',
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-coleta.html',
  styleUrl: './admin-coleta.scss',
})
export class AdminColeta implements OnInit, OnDestroy {
  readonly abas: Array<{ id: Aba; rotulo: string }> = [
    { id: 'geral', rotulo: 'Visão geral' },
    { id: 'moderacao', rotulo: 'Moderação' },
    { id: 'mensagens', rotulo: 'Mensagens' },
    { id: 'erros', rotulo: 'Erros' },
    { id: 'coleta', rotulo: 'Coleta' },
    { id: 'ferramentas', rotulo: 'Ferramentas' },
  ];
  abaAtiva: Aba = 'geral';

  status: StatusAdministrativoColeta | null = null;
  loading = true;
  error = '';
  aviso = '';
  atualizadoEm: Date | null = null;
  flashAtualizado = false;

  // Moderacao
  denuncias: DenunciaAberta[] = [];
  bloqueados: PerfilBloqueado[] = [];
  mostrarBloqueados = false;
  moderandoHandle: string | null = null;

  // Mensagens
  mensagens: MensagemContato[] = [];
  mensagensLidas: MensagemContato[] | null = null;
  filtroMensagem: FiltroMensagem = 'todas';
  resolvendoMensagemId: number | null = null;
  readonly rotuloTipo: Record<MensagemContato['tipo'], string> = {
    elogio: 'Elogio', sugestao: 'Sugestão', problema: 'Problema', denuncia: 'Denúncia', outro: 'Outro',
  };

  // Coleta
  grupoColeta: GrupoColeta = 'precos-steam';
  disparando: TipoColeta | null = null;

  // Ferramentas
  buscaJogo = '';
  sugestoesJogo: GameSummary[] = [];
  jogoEscolhido: GameSummary | null = null;
  preenchendo = false;
  erroPreenchimento = '';
  resultadoPreenchimento: ResultadoPreenchimentoJogo | null = null;

  private destruido = false;
  private atualizador?: ReturnType<typeof setTimeout>;
  private flashTimer?: ReturnType<typeof setTimeout>;
  private buscaTimer?: ReturnType<typeof setTimeout>;
  private buscaSeq = 0;

  constructor(
    private administracao: AdministracaoService,
    private games: GameService,
    private route: ActivatedRoute,
    private router: Router,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    const aba = this.route.snapshot.queryParamMap.get('aba') as Aba | null;
    if (aba && ABAS.includes(aba)) this.abaAtiva = aba;
    this.carregar().then(() => this.agendarProximaAtualizacao());
  }

  ngOnDestroy() {
    this.destruido = true;
    for (const t of [this.atualizador, this.flashTimer, this.buscaTimer]) if (t) clearTimeout(t);
    this.urlsAnexos.forEach(url => URL.revokeObjectURL(url));
  }

  selecionarAba(aba: Aba) {
    this.abaAtiva = aba;
    this.aviso = '';
    this.router.navigate([], { queryParams: { aba: aba === 'geral' ? null : aba }, replaceUrl: true });
    if (aba === 'moderacao' && this.mostrarBloqueados) this.carregarBloqueados();
  }

  // ---------- Carregamento ----------

  // Enquanto algum job roda, atualiza o status a cada 5s; parado, a cada 15s. Listas de moderacao e
  // mensagens so recarregam no Atualizar manual ou depois de uma acao, pra nao mexer no que se le.
  private agendarProximaAtualizacao() {
    if (this.destruido) return;
    const atraso = this.status && this.algumEmExecucao(this.status) ? 5000 : 15000;
    this.atualizador = setTimeout(() => this.carregarStatus().then(() => this.agendarProximaAtualizacao()), atraso);
  }

  private algumEmExecucao(dados: StatusAdministrativoColeta): boolean {
    return this.todasColetas(dados).some(c => c.coleta.emExecucao);
  }

  async carregar() {
    this.loading = true;
    this.cdr.detectChanges();
    const [status, denuncias, mensagens, erros] = await Promise.allSettled([
      this.administracao.consultarColeta(),
      this.administracao.listarDenuncias(),
      this.administracao.listarMensagensContato(),
      this.administracao.listarErros(),
    ]);
    if (status.status === 'fulfilled') this.status = status.value;
    if (denuncias.status === 'fulfilled') this.denuncias = denuncias.value;
    if (mensagens.status === 'fulfilled') this.mensagens = mensagens.value;
    if (erros.status === 'fulfilled') this.erros = erros.value;
    const falhou = [status, denuncias, mensagens, erros].some(r => r.status === 'rejected');
    if (this.errosResolvidos) this.errosResolvidos = await this.administracao.listarErros(true).catch(() => this.errosResolvidos);
    this.error = falhou ? 'Parte do painel não carregou. Tente atualizar de novo.' : '';
    if (!falhou) this.atualizadoEm = new Date();
    if (this.mensagensLidas) this.mensagensLidas = await this.administracao.listarMensagensContato(true).catch(() => this.mensagensLidas);
    if (this.mostrarBloqueados) await this.carregarBloqueados();
    this.loading = false;
    this.cdr.detectChanges();
  }

  private async carregarStatus() {
    try {
      this.status = await this.administracao.consultarColeta();
      this.atualizadoEm = new Date();
    } catch { /* o proximo ciclo tenta de novo; erro persistente aparece no Atualizar manual */ }
    this.cdr.detectChanges();
  }

  // A resposta costuma voltar em ~100ms, rapido demais pra perceber: o "Atualizando..." fica no
  // minimo 600ms e depois pisca "Atualizado agora" por 2s.
  async atualizarManual() {
    if (this.loading) return;
    this.aviso = '';
    const inicio = Date.now();
    await this.carregar();
    const restante = 600 - (Date.now() - inicio);
    if (restante > 0) {
      this.loading = true;
      this.cdr.detectChanges();
      await new Promise(r => setTimeout(r, restante));
      this.loading = false;
    }
    if (!this.error) {
      this.flashAtualizado = true;
      if (this.flashTimer) clearTimeout(this.flashTimer);
      this.flashTimer = setTimeout(() => { this.flashAtualizado = false; this.cdr.detectChanges(); }, 2000);
    }
    this.cdr.detectChanges();
  }

  // ---------- Resumo e visao geral ----------

  get coletasRodando(): number {
    return this.status ? this.todasColetas(this.status).filter(c => c.coleta.emExecucao).length : 0;
  }

  get coletasComErro(): CartaoColeta[] {
    return this.status ? this.todasColetas(this.status).filter(c => !!c.coleta.ultimoErro && !c.coleta.emExecucao) : [];
  }

  get totalColetas(): number {
    return this.status ? this.todasColetas(this.status).length : 0;
  }

  get itensAtencao(): ItemAtencao[] {
    const itens: ItemAtencao[] = this.coletasComErro.map(c => ({
      tipo: 'erro', titulo: c.titulo, detalhe: c.coleta.ultimoErro ?? '', aba: 'coleta', acao: 'Ver',
    }));
    for (const e of this.errosRecentes.slice(0, 3)) {
      itens.push({
        tipo: 'erro',
        titulo: `Erro no ${e.origem}: ${this.resumir(e.mensagem, 70)}`,
        detalhe: `${e.ocorrencias}x · última ${this.tempoRelativo(e.ultimaEm)}${e.pagina ? ' · ' + e.pagina : ''}`,
        aba: 'erros',
        acao: 'Ver',
      });
    }
    for (const g of this.gruposDenuncias.slice(0, 3)) {
      itens.push({
        tipo: 'denuncia',
        titulo: `${g.handle ? '/' + g.handle : 'Perfil sem URL'} recebeu ${g.denuncias.length} denúncia${g.denuncias.length > 1 ? 's' : ''}`,
        detalhe: `Última ${this.tempoRelativo(g.ultimaEm)}`,
        aba: 'moderacao',
        acao: 'Moderar',
      });
    }
    for (const m of this.mensagens.filter(m => m.tipo === 'problema' || m.tipo === 'denuncia').slice(0, 3)) {
      itens.push({
        tipo: 'mensagem',
        titulo: `${this.rotuloTipo[m.tipo]}: "${this.resumir(m.mensagem, 70)}"`,
        detalhe: `${m.pagina ? 'veio de ' + m.pagina + ' · ' : ''}${this.tempoRelativo(m.criadaEm)}`,
        aba: 'mensagens',
        acao: 'Abrir',
      });
    }
    return itens;
  }

  // ---------- Moderacao ----------

  get gruposDenuncias(): GrupoDenuncias[] {
    const grupos = new Map<string, GrupoDenuncias>();
    for (const d of this.denuncias) {
      const chave = d.handle ?? `sem-url-${d.id}`;
      const grupo = grupos.get(chave) ?? { handle: d.handle, nomeExibicao: d.nomeExibicao, perfilBloqueado: d.perfilBloqueado, denuncias: [], ultimaEm: d.criadaEm };
      grupo.denuncias.push(d);
      if (d.criadaEm > grupo.ultimaEm) grupo.ultimaEm = d.criadaEm;
      grupos.set(chave, grupo);
    }
    return [...grupos.values()].sort((a, b) => b.denuncias.length - a.denuncias.length || b.ultimaEm.localeCompare(a.ultimaEm));
  }

  chaveGrupo(grupo: GrupoDenuncias): string {
    return grupo.handle ?? String(grupo.denuncias[0].id);
  }

  async moderarGrupo(grupo: GrupoDenuncias, bloquear: boolean) {
    if (bloquear && !window.confirm(`Bloquear /${grupo.handle}? O perfil some pra todo mundo até ser desbloqueado.`)) return;
    this.moderandoHandle = this.chaveGrupo(grupo);
    this.aviso = '';
    this.cdr.detectChanges();
    try {
      if (bloquear && grupo.handle) await this.administracao.definirBloqueio(grupo.handle, true);
      await Promise.all(grupo.denuncias.map(d => this.administracao.resolverDenuncia(d.id)));
      this.aviso = bloquear ? `Perfil /${grupo.handle} bloqueado.` : 'Denúncias descartadas.';
      this.denuncias = await this.administracao.listarDenuncias();
      if (this.mostrarBloqueados) await this.carregarBloqueados();
    } catch {
      this.error = 'Não foi possível moderar esse perfil.';
    }
    this.moderandoHandle = null;
    this.cdr.detectChanges();
  }

  async alternarBloqueados() {
    this.mostrarBloqueados = !this.mostrarBloqueados;
    if (this.mostrarBloqueados) await this.carregarBloqueados();
    this.cdr.detectChanges();
  }

  private async carregarBloqueados() {
    this.bloqueados = await this.administracao.listarPerfisBloqueados().catch(() => this.bloqueados);
    this.cdr.detectChanges();
  }

  async desbloquear(handle: string) {
    if (!window.confirm(`Desbloquear /${handle}? O perfil volta a ficar visível.`)) return;
    try {
      await this.administracao.definirBloqueio(handle, false);
      this.aviso = `Perfil /${handle} desbloqueado.`;
      await this.carregarBloqueados();
    } catch {
      this.error = 'Não foi possível desbloquear o perfil.';
    }
    this.cdr.detectChanges();
  }

  // ---------- Mensagens ----------

  async selecionarFiltroMensagem(filtro: FiltroMensagem) {
    this.filtroMensagem = filtro;
    if (filtro === 'lidas' && !this.mensagensLidas) {
      this.mensagensLidas = await this.administracao.listarMensagensContato(true).catch(() => []);
    }
    this.cdr.detectChanges();
  }

  contarTipo(tipo: MensagemContato['tipo']): number {
    return this.mensagens.filter(m => m.tipo === tipo).length;
  }

  get tiposComMensagem(): Array<MensagemContato['tipo']> {
    return (Object.keys(this.rotuloTipo) as Array<MensagemContato['tipo']>).filter(t => this.contarTipo(t) > 0);
  }

  get mensagensFiltradas(): MensagemContato[] {
    if (this.filtroMensagem === 'lidas') return this.mensagensLidas ?? [];
    if (this.filtroMensagem === 'todas') return this.mensagens;
    return this.mensagens.filter(m => m.tipo === this.filtroMensagem);
  }

  // Anexos: baixados com o token sob demanda (video pode ter 50 MB) e mostrados via blob URL.
  urlsAnexos = new Map<number, string>();
  carregandoAnexo: number | null = null;

  async verAnexo(anexo: AnexoContato) {
    if (this.urlsAnexos.has(anexo.id) || this.carregandoAnexo !== null) return;
    this.carregandoAnexo = anexo.id;
    this.cdr.detectChanges();
    try {
      const blob = await this.administracao.baixarAnexoContato(anexo.id);
      this.urlsAnexos.set(anexo.id, URL.createObjectURL(blob));
    } catch {
      this.error = 'Não foi possível abrir o anexo.';
    }
    this.carregandoAnexo = null;
    this.cdr.detectChanges();
  }

  ehVideo(anexo: AnexoContato): boolean {
    return anexo.contentType.startsWith('video/');
  }

  tamanhoArquivo(bytes: number): string {
    return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  // Resposta entregue no site (sino e "Minhas mensagens" de quem enviou), sem e-mail por enquanto.
  // Enviar a resposta ja tira a mensagem da fila.
  respondendoId: number | null = null;
  textoResposta = '';
  enviandoResposta = false;

  abrirResposta(m: MensagemContato) {
    this.respondendoId = m.id;
    this.textoResposta = m.resposta ?? '';
  }

  cancelarResposta() {
    this.respondendoId = null;
    this.textoResposta = '';
  }

  async enviarResposta(m: MensagemContato) {
    const resposta = this.textoResposta.trim();
    if (!resposta || this.enviandoResposta) return;
    this.enviandoResposta = true;
    this.aviso = '';
    this.cdr.detectChanges();
    try {
      await this.administracao.responderMensagemContato(m.id, resposta);
      const respondida: MensagemContato = { ...m, resposta, respondidaEm: new Date().toISOString() };
      this.mensagens = this.mensagens.filter(x => x.id !== m.id);
      if (this.mensagensLidas) this.mensagensLidas = [respondida, ...this.mensagensLidas.filter(x => x.id !== m.id)];
      if (this.filtroMensagem !== 'todas' && this.filtroMensagem !== 'lidas' && !this.contarTipo(this.filtroMensagem)) this.filtroMensagem = 'todas';
      this.aviso = 'Resposta enviada. Ela aparece nas notificações de quem mandou a mensagem.';
      this.cancelarResposta();
    } catch (erro: any) {
      this.error = erro?.status === 404
        ? 'Essa mensagem foi enviada sem conta, não tem pra quem entregar a resposta.'
        : 'Não foi possível enviar a resposta.';
    }
    this.enviandoResposta = false;
    this.cdr.detectChanges();
  }

  async marcarLida(m: MensagemContato) {
    this.resolvendoMensagemId = m.id;
    this.cdr.detectChanges();
    try {
      await this.administracao.resolverMensagemContato(m.id);
      this.mensagens = this.mensagens.filter(x => x.id !== m.id);
      if (this.mensagensLidas) this.mensagensLidas = [m, ...this.mensagensLidas];
      if (this.filtroMensagem !== 'todas' && this.filtroMensagem !== 'lidas' && !this.contarTipo(this.filtroMensagem)) this.filtroMensagem = 'todas';
    } catch {
      this.error = 'Não foi possível marcar a mensagem como lida.';
    }
    this.resolvendoMensagemId = null;
    this.cdr.detectChanges();
  }

  // ---------- Erros ----------
  // Rastreamento proprio (issue #29): erros do navegador dos usuarios e 500 do backend, agrupados.

  erros: ErroRegistrado[] = [];
  errosResolvidos: ErroRegistrado[] | null = null;
  verErrosResolvidos = false;
  erroExpandido: number | null = null;
  resolvendoErroId: number | null = null;

  get errosVisiveis(): ErroRegistrado[] {
    return this.verErrosResolvidos ? (this.errosResolvidos ?? []) : this.erros;
  }

  /** Erros abertos que aconteceram nas ultimas 24h: os que entram no "Precisa de atencao". */
  get errosRecentes(): ErroRegistrado[] {
    const limite = Date.now() - 24 * 60 * 60 * 1000;
    return this.erros.filter(e => new Date(e.ultimaEm).getTime() >= limite);
  }

  async alternarErrosResolvidos(ver: boolean) {
    this.verErrosResolvidos = ver;
    if (ver && !this.errosResolvidos) this.errosResolvidos = await this.administracao.listarErros(true).catch(() => []);
    this.cdr.detectChanges();
  }

  async resolverErro(e: ErroRegistrado) {
    this.resolvendoErroId = e.id;
    this.cdr.detectChanges();
    try {
      await this.administracao.resolverErro(e.id);
      this.erros = this.erros.filter(x => x.id !== e.id);
      if (this.errosResolvidos) this.errosResolvidos = [e, ...this.errosResolvidos];
    } catch {
      this.error = 'Não foi possível marcar o erro como resolvido.';
    }
    this.resolvendoErroId = null;
    this.cdr.detectChanges();
  }

  navegadorResumido(userAgent: string | null): string {
    if (!userAgent) return '';
    const navegador = /Edg\//.test(userAgent) ? 'Edge' : /OPR\//.test(userAgent) ? 'Opera' : /Firefox\//.test(userAgent) ? 'Firefox'
      : /Chrome\//.test(userAgent) ? 'Chrome' : /Safari\//.test(userAgent) ? 'Safari' : 'outro navegador';
    const sistema = /Android/.test(userAgent) ? 'Android' : /iPhone|iPad/.test(userAgent) ? 'iOS' : /Windows/.test(userAgent) ? 'Windows'
      : /Mac OS/.test(userAgent) ? 'macOS' : /Linux/.test(userAgent) ? 'Linux' : '';
    return sistema ? `${navegador} · ${sistema}` : navegador;
  }

  // ---------- Coleta ----------

  private todasColetas(dados: StatusAdministrativoColeta): CartaoColeta[] {
    return [
      { titulo: 'Preços ITAD', tipo: 'precos', coleta: dados.precos },
      { titulo: 'Metadados Steam', tipo: 'steam', coleta: dados.steam },
      { titulo: 'Detalhes do jogo', tipo: 'detalhes', coleta: dados.detalhes },
      { titulo: 'Conquistas do catálogo', tipo: 'conquistas-catalogo', coleta: dados.conquistasCatalogo },
      { titulo: 'Instant Gaming: escaneamento', tipo: 'instant-gaming-escaneamento', coleta: dados.instantGamingEscaneamento },
      { titulo: 'Instant Gaming: casamento', tipo: 'instant-gaming-casamento', coleta: dados.instantGamingCasamento },
      { titulo: 'Instant Gaming: preços', tipo: 'instant-gaming-precos', coleta: dados.instantGamingPrecos },
    ];
  }

  coletasResumo(dados: StatusAdministrativoColeta): CartaoColeta[] {
    return this.todasColetas(dados);
  }

  cartoesDoGrupo(dados: StatusAdministrativoColeta): CartaoColeta[] {
    const todas = this.todasColetas(dados);
    switch (this.grupoColeta) {
      case 'detalhes-conquistas': return todas.slice(2, 4);
      case 'instant-gaming': return todas.slice(4);
      default: return todas.slice(0, 2);
    }
  }

  /**
   * Progresso estimado pela duracao da ultima rodada (o backend nao sabe quanto falta). Trava em 95%
   * pra nao parecer concluido quando a rodada atual demora mais que a anterior.
   */
  progresso(c: StatusColeta): number | null {
    if (!c.emExecucao || c.duracaoAtualMs == null || !c.ultimaDuracaoMs) return null;
    return Math.min(95, Math.round((c.duracaoAtualMs / c.ultimaDuracaoMs) * 100));
  }

  estadoColeta(c: StatusColeta): 'rodando' | 'erro' | 'ok' | 'nunca' {
    if (c.emExecucao) return 'rodando';
    if (c.ultimoErro) return 'erro';
    return c.ultimaConclusao ? 'ok' : 'nunca';
  }

  textoEstado(c: StatusColeta): string {
    switch (this.estadoColeta(c)) {
      case 'rodando': return `Rodando · ${this.formatarDuracao(c.duracaoAtualMs)}`;
      case 'erro': return 'Falhou na última rodada';
      case 'ok': return `Concluída ${this.tempoRelativo(c.ultimaConclusao)}`;
      default: return 'Aguardando primeira rodada';
    }
  }

  async disparar(tipo: TipoColeta) {
    this.disparando = tipo;
    this.aviso = '';
    this.error = '';
    this.cdr.detectChanges();
    try {
      await this.administracao.dispararColeta(tipo);
      this.aviso = 'Coleta solicitada. O status atualiza em instantes.';
      setTimeout(() => this.carregarStatus(), 800);
    } catch {
      this.error = 'Não foi possível solicitar a coleta.';
    } finally {
      this.disparando = null;
      this.cdr.detectChanges();
    }
  }

  // ---------- Ferramentas ----------

  aoDigitarBusca() {
    this.jogoEscolhido = null;
    this.resultadoPreenchimento = null;
    this.erroPreenchimento = '';
    if (this.buscaTimer) clearTimeout(this.buscaTimer);
    const termo = this.buscaJogo.trim();
    if (termo.length < 3) { this.sugestoesJogo = []; return; }
    const seq = ++this.buscaSeq;
    this.buscaTimer = setTimeout(async () => {
      try {
        const resultado = await firstValueFrom(this.games.searchGames(termo));
        if (seq === this.buscaSeq) this.sugestoesJogo = resultado.slice(0, 8);
      } catch {
        if (seq === this.buscaSeq) this.sugestoesJogo = [];
      }
      this.cdr.detectChanges();
    }, 300);
  }

  escolherJogo(jogo: GameSummary) {
    this.jogoEscolhido = jogo;
    this.buscaJogo = jogo.title;
    this.sugestoesJogo = [];
  }

  // "Preencher tudo agora": pra quando um jogo novo/pouco tocado esta bombando e nao vale esperar
  // ele chegar na vez na fila normal (steam/detalhes/conquistas rodam sincronos, na hora).
  // Aceita o jogo escolhido na busca ou um slug digitado direto.
  async preencherJogo() {
    const slug = this.jogoEscolhido?.slug ?? this.buscaJogo.trim();
    if (!slug || this.preenchendo) return;
    this.preenchendo = true;
    this.erroPreenchimento = '';
    this.resultadoPreenchimento = null;
    this.sugestoesJogo = [];
    this.cdr.detectChanges();
    try {
      this.resultadoPreenchimento = await this.administracao.preencherJogo(slug);
    } catch (erro: any) {
      this.erroPreenchimento = erro?.status === 404
        ? 'Jogo não encontrado. Escolha um jogo da lista ou confira o slug (o final da URL da página do jogo).'
        : 'Não foi possível preencher esse jogo agora.';
    } finally {
      this.preenchendo = false;
      this.cdr.detectChanges();
    }
  }

  // ---------- Formatacao ----------

  tempoRelativo(valor: string | null): string {
    if (!valor) return '--';
    const segundos = Math.max(0, Math.round((Date.now() - new Date(valor).getTime()) / 1000));
    if (segundos < 60) return 'agora';
    const minutos = Math.round(segundos / 60);
    if (minutos < 60) return `há ${minutos} min`;
    const horas = Math.round(minutos / 60);
    if (horas < 48) return `há ${horas} h`;
    return `há ${Math.round(horas / 24)} dias`;
  }

  formatarData(valor: string | null): string {
    return valor ? new Date(valor).toLocaleString('pt-BR') : '--';
  }

  formatarDuracao(valor: number | null): string {
    if (valor == null) return '--';
    if (valor < 1000) return `${valor} ms`;
    const s = Math.round(valor / 1000);
    return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, '0')}s`;
  }

  resumir(texto: string, max: number): string {
    return texto.length > max ? texto.slice(0, max - 1).trimEnd() + '…' : texto;
  }
}
