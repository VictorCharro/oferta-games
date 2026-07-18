import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { URL_API } from '../configuracao/url-api';
import { supabase } from './supabase';

export interface AvaliacaoJogo {
  id: number;
  usuarioNome: string | null;
  usuarioAvatarUrl: string | null;
  nota: number;
  comentario: string | null;
  criadoEm: string;
  recomenda: boolean;
  util: number;
  naoUtil: number;
  meuVoto: boolean | null;
}

export interface ResumoAvaliacoes {
  total: number;
  media: number | null;
  distribuicao: Record<number, number>;
  percentualRecomenda: number | null;
}

export interface RespostaAvaliacoes {
  resumo: ResumoAvaliacoes;
  minha: AvaliacaoJogo | null;
  avaliacoes: AvaliacaoJogo[];
}

@Injectable({ providedIn: 'root' })
export class GameReviewsService {
  private readonly api = URL_API;

  constructor(private http: HttpClient) {}

  async listar(slug: string): Promise<RespostaAvaliacoes> {
    const headers = await this.authHeadersOpcional();
    return firstValueFrom(this.http.get<RespostaAvaliacoes>(`${this.api}/games/${slug}/reviews`, { headers }));
  }

  async avaliar(slug: string, nota: number, comentario: string): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    await firstValueFrom(this.http.post(`${this.api}/games/${slug}/reviews`, { nota, comentario }, { headers }));
  }

  async remover(slug: string): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    await firstValueFrom(this.http.delete(`${this.api}/games/${slug}/reviews`, { headers }));
  }

  async votar(slug: string, reviewId: number, util: boolean): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    await firstValueFrom(this.http.post(`${this.api}/games/${slug}/reviews/${reviewId}/voto`, { util }, { headers }));
  }

  private async authHeaders(): Promise<{ Authorization: string } | null> {
    const { data } = await supabase.auth.getSession();
    return data.session?.access_token ? { Authorization: `Bearer ${data.session.access_token}` } : null;
  }

  private async authHeadersOpcional(): Promise<{ Authorization: string } | undefined> {
    return (await this.authHeaders()) ?? undefined;
  }
}
