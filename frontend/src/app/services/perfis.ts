import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { supabase } from './supabase';

export interface PerfilProprio { handle: string | null; nomeExibicao: string; bio: string | null; publico: boolean; mostrarHoras: boolean; mostrarConquistas: boolean; mostrarBiblioteca: boolean; mostrarFavoritos: boolean; mostrarAtividades: boolean; }
export interface PerfilPublico { handle: string; nomeExibicao: string; bio: string | null; avatarUrl: string | null; totalMinutos: number | null; conquistasDesbloqueadas: number | null; conquistasTotal: number | null; biblioteca: Array<{ appId: number; titulo: string; minutosJogadas: number; iconeHash: string | null; conquistasDesbloqueadas: number; conquistasTotal: number }>; favoritos: Array<{ slug: string; titulo: string; capaUrl: string | null; ehDlc: boolean | null; precoMinimo: number | null; precoRegular: number | null; favoritadoEm: string }>; atividades: Array<{ tipo: string; tituloJogo: string | null; detalhe: string | null; criadaEm: string }>; mostrarAtividades: boolean; }

@Injectable({ providedIn: 'root' })
export class PerfisService {
  private readonly api = 'https://oferta-games.onrender.com/api/perfis';
  constructor(private http: HttpClient) {}
  async proprio(): Promise<PerfilProprio | null> { return firstValueFrom(this.http.get<PerfilProprio | null>(`${this.api}/me`, { headers: await this.headers() })); }
  async salvar(perfil: Omit<PerfilProprio, 'bio'> & { bio: string }): Promise<void> { await firstValueFrom(this.http.put(`${this.api}/me`, perfil, { headers: await this.headers() })); }
  async atualizarAvatar(avatarUrl: string): Promise<void> { await firstValueFrom(this.http.put(`${this.api}/me/avatar`, { avatarUrl }, { headers: await this.headers() })); }
  async publico(handle: string): Promise<PerfilPublico> { return firstValueFrom(this.http.get<PerfilPublico>(`${this.api}/${encodeURIComponent(handle)}`, { headers: await this.optionalHeaders() })); }
  async atualizarPublico(handle: string): Promise<{ status: 'agendada' | 'aguarde' | 'sem_conexao' }> { return firstValueFrom(this.http.post<{ status: 'agendada' | 'aguarde' | 'sem_conexao' }>(`${this.api}/${encodeURIComponent(handle)}/atualizar`, {}, { headers: await this.optionalHeaders() })); }
  private async headers(): Promise<{ Authorization: string }> { const { data } = await supabase.auth.getSession(); if (!data.session?.access_token) throw new Error('Sessao nao encontrada'); return { Authorization: `Bearer ${data.session.access_token}` }; }
  private async optionalHeaders(): Promise<{ Authorization?: string }> { const { data } = await supabase.auth.getSession(); return data.session?.access_token ? { Authorization: `Bearer ${data.session.access_token}` } : {}; }
}
