import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AdministracaoService, ResultadoPreenchimentoJogo, StatusAdministrativoColeta, StatusColeta, TipoColeta } from '../../services/administracao';

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

  async carregar(exibirCarregamento = true) {
    if (exibirCarregamento) this.loading = true;
    try {
      this.status = await this.administracao.consultarColeta();
      this.error = '';
    } catch {
      this.error = 'Nao foi possivel consultar o status da coleta.';
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
          { titulo: 'Conquistas do catalogo', tipo: 'conquistas-catalogo', coleta: dados.conquistasCatalogo },
        ];
      case 'instant-gaming':
        return [
          { titulo: 'Escaneamento', tipo: 'instant-gaming-escaneamento', coleta: dados.instantGamingEscaneamento },
          { titulo: 'Casamento', tipo: 'instant-gaming-casamento', coleta: dados.instantGamingCasamento },
          { titulo: 'Precos', tipo: 'instant-gaming-precos', coleta: dados.instantGamingPrecos },
        ];
      default:
        return [
          { titulo: 'Precos ITAD', tipo: 'precos', coleta: dados.precos },
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
      this.aviso = `Coleta de ${tipo} solicitada. O status sera atualizado em instantes.`;
      setTimeout(() => this.carregar(false), 800);
    } catch {
      this.error = 'Nao foi possivel solicitar a coleta.';
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
        ? 'Jogo nao encontrado. Confira o slug (o final da URL da pagina do jogo).'
        : 'Nao foi possivel preencher esse jogo agora.';
    } finally {
      this.preenchendo = false;
      this.cdr.detectChanges();
    }
  }

  textoStatus(status: StatusColeta): string {
    if (status.emExecucao) return 'Em execucao';
    return status.ultimaConclusao ? 'Concluida' : 'Aguardando primeira rodada';
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
