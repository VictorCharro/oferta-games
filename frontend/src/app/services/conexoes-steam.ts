import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { supabase } from './supabase';
import { URL_API } from '../configuracao/url-api';

export interface StatusSteam {
  conectada: boolean;
  nome: string | null;
  avatarUrl: string | null;
  bibliotecaSincronizadaEm: string | null;
  conquistasSincronizadasEm: string | null;
  ultimoErro: string | null;
  totalJogos: number;
  totalMinutos: number;
  conquistasDesbloqueadas: number;
  conquistasTotal: number;
  jogosPlatinados: number;
}

export interface JogoBibliotecaSteam { appId: number; titulo: string; minutosJogadas: number; iconeHash: string | null; }

@Injectable({ providedIn: 'root' })
export class ConexoesSteamService {
  private readonly api = `${URL_API}/conexoes/steam`;
  constructor(private http: HttpClient) {}
  async status(): Promise<StatusSteam> { return firstValueFrom(this.http.get<StatusSteam>(this.api, { headers: await this.headers() })); }
  async biblioteca(): Promise<JogoBibliotecaSteam[]> { return firstValueFrom(this.http.get<JogoBibliotecaSteam[]>(`${this.api}/biblioteca`, { headers: await this.headers() })); }
  async conectar() { const resposta = await firstValueFrom(this.http.post<{ url: string }>(`${this.api}/iniciar`, {}, { headers: await this.headers() })); window.location.assign(resposta.url); }
  async sincronizar() { await firstValueFrom(this.http.post(`${this.api}/sincronizar`, {}, { headers: await this.headers() })); }
  async desconectar() { await firstValueFrom(this.http.delete(this.api, { headers: await this.headers() })); }
  private async headers(): Promise<{ Authorization: string }> { const { data } = await supabase.auth.getSession(); if (!data.session?.access_token) throw new Error('Sessao nao encontrada'); return { Authorization: `Bearer ${data.session.access_token}` }; }
}
