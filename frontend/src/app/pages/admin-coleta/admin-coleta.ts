import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { AdministracaoService, StatusAdministrativoColeta, StatusColeta, TipoColeta } from '../../services/administracao';

type Aba = 'precos-steam' | 'detalhes-conquistas' | 'instant-gaming';

interface CartaoColeta {
  titulo: string;
  tipo: TipoColeta;
  coleta: StatusColeta;
}

@Component({
  selector: 'app-admin-coleta',
  standalone: false,
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
  private atualizador?: ReturnType<typeof setInterval>;

  constructor(private administracao: AdministracaoService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.carregar();
    this.atualizador = setInterval(() => this.carregar(false), 15000);
  }

  ngOnDestroy() {
    if (this.atualizador) clearInterval(this.atualizador);
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
