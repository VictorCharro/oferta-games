import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AdministracaoService, DenunciaAberta, ResultadoPreenchimentoJogo, StatusAdministrativoColeta, StatusColeta, TipoColeta } from '../../services/administracao';

type Aba = 'precos-steam' | 'detalhes-conquistas' | 'instant-gaming';

interface CartaoColeta {
  titulo: string;
  tipo: TipoColeta;
  coleta: StatusColeta;
}

@Component({
  selector: 'app-admin-coleta',
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-coleta.html',
  styleUrl: './admin-coleta.scss',
})
export class AdminColeta implements OnInit, OnDestroy {
  status: StatusAdministrativoColeta | null = null;
  loading = true;
  error = '';
  aviso = '';
  disparando: TipoColeta | null = null;
  abaAtiva: Aba = 'precos-steam';
  slugPreenchimento = '';
  preenchendo = false;
  erroPreenchimento = '';
  resultadoPreenchimento: ResultadoPreenchimentoJogo | null = null;
  private destruido = false;
  private atualizador?: ReturnType<typeof setTimeout>;

  constructor(private administracao: AdministracaoService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.carregar().then(() => this.agendarProximaAtualizacao());
  }

  ngOnDestroy() {
    this.destruido = true;
    if (this.atualizador) clearTimeout(this.atualizador);
  }

  // Enquanto algum job estiver rodando, atualiza mais rapido (5s) pra quem esta acompanhando ver o
  // andamento quase em tempo real; parado, volta pro ritmo tranquilo de 15s.
  private agendarProximaAtualizacao() {
    if (this.destruido) return;
    const atraso = this.status && this.algumEmExecucao(this.status) ? 5000 : 15000;
    this.atualizador = setTimeout(() => this.carregar(false).then(() => this.agendarProximaAtualizacao()), atraso);
  }

  private algumEmExecucao(dados: StatusAdministrativoColeta): boolean {
    return [
      dados.precos,
      dados.steam,
      dados.detalhes,
      dados.conquistasCatalogo,
      dados.instantGamingEscaneamento,
      dados.instantGamingCasamento,
      dados.instantGamingPrecos,
    ].some(coleta => coleta.emExecucao);
  }

  // Denuncias de perfil (issue #25). Carregadas junto com o status, sem polling proprio.
  denuncias: DenunciaAberta[] = [];
  moderandoId: number | null = null;

  async resolverDenuncia(denuncia: DenunciaAberta, bloquearPerfil: boolean) {
    this.moderandoId = denuncia.id;
    this.cdr.detectChanges();
    try {
      if (bloquearPerfil && denuncia.handle) await this.administracao.definirBloqueio(denuncia.handle, true);
      await this.administracao.resolverDenuncia(denuncia.id);
      this.aviso = bloquearPerfil ? `Perfil /${denuncia.handle} bloqueado e denúncia resolvida.` : 'Denúncia resolvida.';
      this.denuncias = await this.administracao.listarDenuncias();
    } catch {
      this.error = 'Não foi possível moderar essa denúncia.';
    }
    this.moderandoId = null;
    this.cdr.detectChanges();
  }

  async desbloquear(handle: string) {
    try {
      await this.administracao.definirBloqueio(handle, false);
      this.aviso = `Perfil /${handle} desbloqueado.`;
      this.denuncias = await this.administracao.listarDenuncias();
    } catch {
      this.error = 'Não foi possível desbloquear o perfil.';
    }
    this.cdr.detectChanges();
  }

  async carregar(exibirCarregamento = true) {
    if (exibirCarregamento) this.loading = true;
    try {
      this.status = await this.administracao.consultarColeta();
      if (exibirCarregamento) this.denuncias = await this.administracao.listarDenuncias().catch(() => this.denuncias);
      this.error = '';
    } catch {
      this.error = 'Não foi possível consultar o status da coleta.';
    } finally {
      this.loading = false;
      this.cdr.detectChanges();
    }
  }

  selecionarAba(aba: Aba) {
    this.abaAtiva = aba;
  }

  cartoesDaAba(dados: StatusAdministrativoColeta): CartaoColeta[] {
    switch (this.abaAtiva) {
      case 'detalhes-conquistas':
        return [
          { titulo: 'Detalhes do jogo', tipo: 'detalhes', coleta: dados.detalhes },
          { titulo: 'Conquistas do catálogo', tipo: 'conquistas-catalogo', coleta: dados.conquistasCatalogo },
        ];
      case 'instant-gaming':
        return [
          { titulo: 'Escaneamento', tipo: 'instant-gaming-escaneamento', coleta: dados.instantGamingEscaneamento },
          { titulo: 'Casamento', tipo: 'instant-gaming-casamento', coleta: dados.instantGamingCasamento },
          { titulo: 'Preços', tipo: 'instant-gaming-precos', coleta: dados.instantGamingPrecos },
        ];
      default:
        return [
          { titulo: 'Preços ITAD', tipo: 'precos', coleta: dados.precos },
          { titulo: 'Metadados Steam', tipo: 'steam', coleta: dados.steam },
        ];
    }
  }

  async disparar(tipo: TipoColeta) {
    this.disparando = tipo;
    this.aviso = '';
    this.error = '';
    try {
      await this.administracao.dispararColeta(tipo);
      this.aviso = `Coleta de ${tipo} solicitada. O status será atualizado em instantes.`;
      setTimeout(() => this.carregar(false), 800);
    } catch {
      this.error = 'Não foi possível solicitar a coleta.';
    } finally {
      this.disparando = null;
      this.cdr.detectChanges();
    }
  }

  // Botao "Preencher tudo agora": pra quando um jogo novo/pouco tocado esta bombando e nao vale
  // esperar ele chegar na vez na fila normal (steam/detalhes/conquistas rodam sincronos, na hora).
  async preencherJogo() {
    const slug = this.slugPreenchimento.trim();
    if (!slug || this.preenchendo) return;
    this.preenchendo = true;
    this.erroPreenchimento = '';
    this.resultadoPreenchimento = null;
    try {
      this.resultadoPreenchimento = await this.administracao.preencherJogo(slug);
    } catch (erro: any) {
      this.erroPreenchimento = erro?.status === 404
        ? 'Jogo não encontrado. Confira o slug (o final da URL da página do jogo).'
        : 'Não foi possível preencher esse jogo agora.';
    } finally {
      this.preenchendo = false;
      this.cdr.detectChanges();
    }
  }

  textoStatus(status: StatusColeta): string {
    if (status.emExecucao) return 'Em execução';
    return status.ultimaConclusao ? 'Concluída' : 'Aguardando primeira rodada';
  }

  formatarData(valor: string | null): string {
    return valor ? new Date(valor).toLocaleString('pt-BR') : '--';
  }

  formatarDuracao(valor: number | null): string {
    if (valor == null) return '--';
    if (valor < 1000) return `${valor} ms`;
    return `${(valor / 1000).toFixed(1)} s`;
  }
}
