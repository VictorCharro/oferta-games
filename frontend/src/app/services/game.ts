import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { URL_API } from '../configuracao/url-api';

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
}

export interface GameDetail {
  id: string;
  slug: string;
  title: string;
  coverUrl: string | null;
  offers: Offer[];
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

  getGames(page = 0, size = 20, filters: { sort?: string; minPrice?: number | null; maxPrice?: number | null; minDiscount?: number | null; type?: string; platform?: string; q?: string } = {}): Observable<GameSummary[]> {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (filters.sort) params.set('sort', filters.sort);
    if (filters.minPrice != null) params.set('minPrice', String(filters.minPrice));
    if (filters.maxPrice != null) params.set('maxPrice', String(filters.maxPrice));
    if (filters.minDiscount != null && filters.minDiscount > 0) params.set('minDiscount', String(filters.minDiscount));
    if (filters.type && filters.type !== 'all') params.set('type', filters.type);
    if (filters.platform && filters.platform !== 'all') params.set('platform', filters.platform);
    if (filters.q && filters.q.trim()) params.set('q', filters.q.trim());
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
}
