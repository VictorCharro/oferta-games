import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { supabase } from './supabase';

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

export interface StatusAdministrativoColeta {
  precos: StatusColeta;
  steam: StatusColeta;
  fila: {
    nuncaSincronizados: number;
    sincronizacaoMaisAntiga: string | null;
    pendentesSteam: number;
  };
}

@Injectable({ providedIn: 'root' })
export class AdministracaoService {
  private api = 'https://oferta-games.onrender.com/api/admin';

  constructor(private http: HttpClient) {}

  async consultarColeta(): Promise<StatusAdministrativoColeta> {
    return firstValueFrom(this.http.get<StatusAdministrativoColeta>(`${this.api}/coleta`, {
      headers: await this.cabecalhosAutorizacao(),
    }));
  }

  async dispararColeta(tipo: 'precos' | 'steam'): Promise<void> {
    await firstValueFrom(this.http.post(`${this.api}/coleta/${tipo}`, {}, {
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
