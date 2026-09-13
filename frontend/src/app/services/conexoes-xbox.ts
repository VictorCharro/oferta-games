import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { supabase } from './supabase';
import { URL_API } from '../configuracao/url-api';

export interface StatusXbox {
  conectada: boolean;
  gamertag: string | null;
  avatarUrl: string | null;
  gamerscore: number | null;
  ultimoErro: string | null;
}

@Injectable({ providedIn: 'root' })
export class ConexoesXboxService {
  private readonly api = `${URL_API}/conexoes/xbox`;
  constructor(private http: HttpClient) {}

  async status(): Promise<StatusXbox> { return firstValueFrom(this.http.get<StatusXbox>(this.api, { headers: await this.headers() })); }

  async conectar() {
    const { appKey } = await firstValueFrom(this.http.get<{ appKey: string }>(`${this.api}/app-key`));
    window.location.assign(`https://api.xbl.io/app/auth/${appKey}`);
  }

  async concluir(code: string): Promise<void> { await firstValueFrom(this.http.post(`${this.api}/concluir`, { code }, { headers: await this.headers() })); }
  async sincronizar() { await firstValueFrom(this.http.post(`${this.api}/sincronizar`, {}, { headers: await this.headers() })); }
  async desconectar() { await firstValueFrom(this.http.delete(this.api, { headers: await this.headers() })); }
  private async headers(): Promise<{ Authorization: string }> { const { data } = await supabase.auth.getSession(); if (!data.session?.access_token) throw new Error('Sessão não encontrada'); return { Authorization: `Bearer ${data.session.access_token}` }; }
}
