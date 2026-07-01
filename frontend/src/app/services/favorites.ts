import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject } from 'rxjs';
import { AuthService } from './auth';
import { supabase } from './supabase';
import { GameSummary } from './game';

export interface FavoriteGame extends GameSummary {
  favoritedAt: string;
}

@Injectable({ providedIn: 'root' })
export class FavoritesService {
  private api = 'https://oferta-games.vercel.app/api';

  private _slugs = new BehaviorSubject<Set<string>>(new Set());
  slugs$ = this._slugs.asObservable();

  private _list = new BehaviorSubject<FavoriteGame[]>([]);
  list$ = this._list.asObservable();

  loading = false;

  constructor(private http: HttpClient, private auth: AuthService) {
    this.auth.user$.subscribe(user => {
      if (user) this.load();
      else {
        this._slugs.next(new Set());
        this._list.next([]);
      }
    });
  }

  isFavorited(slug: string): boolean {
    return this._slugs.value.has(slug);
  }

  private async authHeaders(): Promise<{ Authorization: string }> {
    const { data } = await supabase.auth.getSession();
    return { Authorization: `Bearer ${data.session?.access_token}` };
  }

  async load() {
    this.loading = true;
    const headers = await this.authHeaders();
    this.http.get<FavoriteGame[]>(`${this.api}/favorites`, { headers }).subscribe({
      next: (list) => {
        this._list.next(list);
        this._slugs.next(new Set(list.map(g => g.slug)));
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  async toggle(game: GameSummary) {
    const headers = await this.authHeaders();
    const favorited = this.isFavorited(game.slug);

    if (favorited) {
      const set = new Set(this._slugs.value);
      set.delete(game.slug);
      this._slugs.next(set);
      this._list.next(this._list.value.filter(g => g.slug !== game.slug));
      this.http.delete(`${this.api}/favorites/${game.slug}`, { headers }).subscribe();
    } else {
      const set = new Set(this._slugs.value);
      set.add(game.slug);
      this._slugs.next(set);
      this.http.post(`${this.api}/favorites`, { slug: game.slug }, { headers }).subscribe(() => this.load());
    }
  }
}
