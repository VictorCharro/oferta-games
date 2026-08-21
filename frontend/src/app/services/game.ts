import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom, Observable } from 'rxjs';
import { URL_API } from '../configuracao/url-api';
import { supabase } from './supabase';

export interface GameSummary {
  slug: string;
  title: string;
  coverUrl: string | null;
  minPrice: number | string | null;
  regularPrice?: number | string | null;
  storeName?: string | null;
  url?: string | null;
  isDlc?: boolean | null;
}

export interface Offer {
  storeName: string;
  price: number;
  regularPrice: number;
  currency: string;
  url: string;
  voucherCode: string | null;
}

export interface GameDetail {
  id: string;
  slug: string;
  title: string;
  coverUrl: string | null;
  offers: Offer[];
  dlcs: GameSummary[];
  jogosBase: GameSummary[];
}

export interface GameHighlight {
  titulo: string;
  texto: string;
}

export interface GameDetails {
  descricao: string | null;
  generos: string[];
  desenvolvedores: string[];
  publicadoras: string[];
  dataLancamento: string | null;
  screenshots: string[];
  notaReviews: string | null;
  reviewsPositivas: number | null;
  reviewsNegativas: number | null;
  trailerUrl: string | null;
  trailerThumbnail: string | null;
  sobreCompleto: string | null;
  destaques: GameHighlight[];
  categorias: string[];
  requisitosMinimos: string | null;
  requisitosRecomendados: string | null;
}

export interface ConquistaComProgresso {
  nome: string;
  descricao: string | null;
  iconeUrl: string | null;
  percentualGlobal: number | null;
  desbloqueada: boolean;
  desbloqueadaEm: string | null;
}

export interface RespostaConquistas {
  total: number;
  desbloqueadas: number;
  percentualConcluido: number;
  proxima: ConquistaComProgresso | null;
  conquistas: ConquistaComProgresso[];
}

export interface AvaliacaoSteam {
  id: string;
  autorSteamId: string | null;
  autorNome: string;
  autorAvatarUrl: string | null;
  texto: string | null;
  recomenda: boolean;
  votosUteis: number | null;
  votosEngracados: number | null;
  comentarios: number | null;
  minutosJogados: number | null;
  criadaEm: string | null;
  idioma: string | null;
}

export interface RespostaAvaliacoesSteam {
  steamAppId: string | null;
  avaliacoes: AvaliacaoSteam[];
  proximoCursor: string | null;
  temMais: boolean;
  idiomaConsulta: 'brazilian' | 'all';
  ordenacao: 'recent' | 'all' | 'updated';
}

export interface TopDeal {
  slug: string;
  title: string;
  coverUrl: string | null;
  isDlc?: boolean | null;
  rank: number | null;
  storeName: string;
  price: number;
  regularPrice: number;
  url: string;
  discountPct: number;
}

@Injectable({ providedIn: 'root' })
export class GameService {
  private api = URL_API;

  constructor(private http: HttpClient) {}

  getGames(page = 0, size = 20, filters: { sort?: string; minPrice?: number | null; maxPrice?: number | null; minDiscount?: number | null; type?: string; platform?: string; q?: string; stores?: string[] } = {}): Observable<GameSummary[]> {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (filters.sort) params.set('sort', filters.sort);
    if (filters.minPrice != null) params.set('minPrice', String(filters.minPrice));
    if (filters.maxPrice != null) params.set('maxPrice', String(filters.maxPrice));
    if (filters.minDiscount != null && filters.minDiscount > 0) params.set('minDiscount', String(filters.minDiscount));
    if (filters.type && filters.type !== 'all') params.set('type', filters.type);
    if (filters.platform && filters.platform !== 'all') params.set('platform', filters.platform);
    if (filters.q && filters.q.trim()) params.set('q', filters.q.trim());
    if (filters.stores && filters.stores.length > 0) params.set('stores', filters.stores.join(','));
    return this.http.get<GameSummary[]>(`${this.api}/games?${params}`);
  }

  getGame(slug: string): Observable<GameDetail> {
    return this.http.get<GameDetail>(`${this.api}/games/${slug}`);
  }

  searchGames(q: string): Observable<GameSummary[]> {
    return this.http.get<GameSummary[]>(`${this.api}/games/search?q=${encodeURIComponent(q)}`);
  }

  refreshGame(slug: string): Observable<{ ok: boolean; updated: number }> {
    return this.http.post<{ ok: boolean; updated: number }>(`${this.api}/games/${slug}/refresh`, {});
  }

  getTopDeals(size = 20, sort: 'discount' | 'rank' = 'discount'): Observable<TopDeal[]> {
    return this.http.get<TopDeal[]>(`${this.api}/deals/top?size=${size}&sort=${sort}`);
  }

  getGameDetails(slug: string): Observable<GameDetails> {
    return this.http.get<GameDetails>(`${this.api}/games/${slug}/detalhes`);
  }

  getSteamReviews(
    slug: string,
    options: {
      cursor?: string | null;
      ordenacao?: 'recent' | 'all' | 'updated';
      idioma?: 'brazilian' | 'all';
    } = {}
  ): Observable<RespostaAvaliacoesSteam> {
    const params = new URLSearchParams({
      ordenacao: options.ordenacao ?? 'recent',
      idioma: options.idioma ?? 'brazilian',
    });
    if (options.cursor) params.set('cursor', options.cursor);
    return this.http.get<RespostaAvaliacoesSteam>(
      `${this.api}/games/${slug}/avaliacoes/steam?${params}`
    );
  }

  async getGameAchievements(slug: string): Promise<RespostaConquistas> {
    const headers = await this.authHeadersOpcional();
    return firstValueFrom(this.http.get<RespostaConquistas>(`${this.api}/games/${slug}/conquistas`, { headers }));
  }

  private async authHeadersOpcional(): Promise<{ Authorization: string } | undefined> {
    const { data } = await supabase.auth.getSession();
    return data.session?.access_token ? { Authorization: `Bearer ${data.session.access_token}` } : undefined;
  }
}
