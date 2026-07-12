import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, firstValueFrom } from 'rxjs';
import { GameSummary } from './game';
import { supabase } from './supabase';

export interface FavoriteProfileGame {
  slug: string;
  titulo: string;
  capaUrl: string | null;
  ehDlc: boolean | null;
  precoMinimo: number | null;
  precoRegular: number | null;
  favoritadoEm: string;
}

@Injectable({ providedIn: 'root' })
export class ProfileFavoritesService {
  private readonly api = 'https://oferta-games.onrender.com/api/profile-favorites';
  private readonly slugsSubject = new BehaviorSubject<Set<string>>(new Set());
  readonly slugs$ = this.slugsSubject.asObservable();

  constructor(private http: HttpClient) {}

  isFavorite(slug: string): boolean { return this.slugsSubject.value.has(slug); }

  async load(): Promise<FavoriteProfileGame[]> {
    const headers = await this.authHeaders();
    if (!headers) { this.slugsSubject.next(new Set()); return []; }
    const favorites = await firstValueFrom(this.http.get<FavoriteProfileGame[]>(this.api, { headers }));
    this.slugsSubject.next(new Set(favorites.map(game => game.slug)));
    return favorites;
  }

  async toggle(game: GameSummary): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    if (this.isFavorite(game.slug)) await this.remove(game.slug, headers);
    else await firstValueFrom(this.http.post(this.api, { slug: game.slug }, { headers }));
    await this.load();
  }

  async remove(slug: string, headers?: { Authorization: string }): Promise<void> {
    const authorization = headers ?? await this.authHeaders();
    if (!authorization) return;
    await firstValueFrom(this.http.delete(`${this.api}/${encodeURIComponent(slug)}`, { headers: authorization }));
    const next = new Set(this.slugsSubject.value);
    next.delete(slug);
    this.slugsSubject.next(next);
  }

  private async authHeaders(): Promise<{ Authorization: string } | null> {
    const { data } = await supabase.auth.getSession();
    return data.session?.access_token ? { Authorization: `Bearer ${data.session.access_token}` } : null;
  }
}
