import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { supabase } from './supabase';
import { URL_API } from '../configuracao/url-api';

export interface StatusColeta {
  tipo: string;
  emExecucao: boolean;
  inicioAtual: string | null;
  duracaoAtualMs: number | null;
  ultimaConclusao: string | null;
  ultimaDuracaoMs: number | null;
  jogosAtualizados: number;
  ofertasAtualizadas: number;
  ultimoErro: string | null;
}

export interface ResumoFilaInstantGaming {
  ultimoIdEscaneado: number;
  catalogoDescoberto: number;
  jogosCasados: number;
  pendentesCasamento: number;
}

export interface StatusAdministrativoColeta {
  precos: StatusColeta;
  steam: StatusColeta;
  detalhes: StatusColeta;
  conquistasCatalogo: StatusColeta;
  instantGamingEscaneamento: StatusColeta;
  instantGamingCasamento: StatusColeta;
  instantGamingPrecos: StatusColeta;
  fila: {
    nuncaSincronizados: number;
    sincronizacaoMaisAntiga: string | null;
    pendentesSteam: number;
  };
  pendentesDetalhes: number;
  pendentesConquistas: number;
  filaInstantGaming: ResumoFilaInstantGaming;
}

export interface ResultadoPreenchimentoJogo {
  metadadosSteamAtualizados: boolean;
  temSteamAppId: boolean;
  detalhesAtualizados: boolean;
  conquistasAtualizadas: boolean;
}

export type TipoColeta =
  | 'precos'
  | 'steam'
  | 'detalhes'
  | 'conquistas-catalogo'
  | 'instant-gaming-escaneamento'
  | 'instant-gaming-casamento'
  | 'instant-gaming-precos';

@Injectable({ providedIn: 'root' })
export class AdministracaoService {
  private api = `${URL_API}/admin`;

  constructor(private http: HttpClient) {}

  async consultarColeta(): Promise<StatusAdministrativoColeta> {
    return firstValueFrom(this.http.get<StatusAdministrativoColeta>(`${this.api}/coleta`, {
      headers: await this.cabecalhosAutorizacao(),
    }));
  }

  async dispararColeta(tipo: TipoColeta): Promise<void> {
    await firstValueFrom(this.http.post(`${this.api}/coleta/${tipo}`, {}, {
      headers: await this.cabecalhosAutorizacao(),
    }));
  }

  async preencherJogo(slug: string): Promise<ResultadoPreenchimentoJogo> {
    return firstValueFrom(this.http.post<ResultadoPreenchimentoJogo>(`${this.api}/jogos/${slug}/preencher-tudo`, {}, {
      headers: await this.cabecalhosAutorizacao(),
    }));
  }

  private async cabecalhosAutorizacao(): Promise<{ Authorization: string }> {
    const { data } = await supabase.auth.getSession();
    const token = data.session?.access_token;
    if (!token) throw new Error('Sessao administrativa nao encontrada');
    return { Authorization: `Bearer ${token}` };
  }
}
