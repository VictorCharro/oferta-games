import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface GameSummary {
  slug: string;
  title: string;
  coverUrl: string | null;
  minPrice: number | string | null;
  regularPrice?: number | string | null;
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
  rank: number | null;
  storeName: string;
  price: number;
  regularPrice: number;
  url: string;
  discountPct: number;
}

@Injectable({ providedIn: 'root' })
export class GameService {
  private api = 'https://oferta-games.vercel.app/api';

  constructor(private http: HttpClient) {}

  getGames(page = 0, size = 20): Observable<GameSummary[]> {
    return this.http.get<GameSummary[]>(`${this.api}/games?page=${page}&size=${size}`);
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

  getTopDeals(size = 20): Observable<TopDeal[]> {
    return this.http.get<TopDeal[]>(`${this.api}/deals/top?size=${size}`);
  }
}
