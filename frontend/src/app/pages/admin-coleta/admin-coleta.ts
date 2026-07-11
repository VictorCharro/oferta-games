import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { AdministracaoService, StatusAdministrativoColeta, StatusColeta } from '../../services/administracao';

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
  disparando: 'precos' | 'steam' | null = null;
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

  async disparar(tipo: 'precos' | 'steam') {
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
