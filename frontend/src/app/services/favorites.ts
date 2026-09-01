import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, firstValueFrom } from 'rxjs';
import { AuthService } from './auth';
import { supabase } from './supabase';
import { GameSummary } from './game';
import { URL_API } from '../configuracao/url-api';

export interface FavoriteGame extends GameSummary {
  targetPrice: number | null;
  favoritedAt: string;
}

@Injectable({ providedIn: 'root' })
export class FavoritesService {
  private api = URL_API;

  private _slugs = new BehaviorSubject<Set<string>>(new Set());
  slugs$ = this._slugs.asObservable();

  private _list = new BehaviorSubject<FavoriteGame[]>([]);
  list$ = this._list.asObservable();

  /**
   * Se a lista já reflete a resposta do servidor (ou a certeza de que não há o que buscar, no caso
   * de visitante deslogado).
   *
   * `list$` é um `BehaviorSubject([])`, então emite lista vazia no instante da inscrição — antes de
   * qualquer requisição. Quem usar só essa emissão para decidir que terminou de carregar mostra
   * "nenhum jogo monitorado" por um instante para quem tem jogos monitorados.
   */
  private _carregado = new BehaviorSubject<boolean>(false);
  carregado$ = this._carregado.asObservable();

  loading = false;
  private loadingPromise: Promise<void> | null = null;

  constructor(private http: HttpClient, private auth: AuthService) {
    this.auth.user$.subscribe(user => {
      if (user) this.load();
      else {
        this._slugs.next(new Set());
        this._list.next([]);
        // Só é "carregado" como lista vazia depois que a sessão foi consultada; enquanto ela não
        // resolveu, `user` é null por não sabermos ainda, não por estar deslogado.
        if (this.auth.sessaoResolvida) this._carregado.next(true);
      }
    });
  }

  isFavorited(slug: string): boolean {
    return this._slugs.value.has(slug);
  }

  getFavorite(slug: string): FavoriteGame | undefined {
    return this._list.value.find(g => g.slug === slug);
  }

  private async authHeaders(): Promise<{ Authorization: string } | null> {
    const { data } = await supabase.auth.getSession();
    const token = data.session?.access_token;
    return token ? { Authorization: `Bearer ${token}` } : null;
  }

  async load() {
    if (this.loadingPromise) return this.loadingPromise;
    this.loading = true;
    this.loadingPromise = this.loadFavorites();
    return this.loadingPromise.finally(() => {
      this.loadingPromise = null;
    });
  }

  private async loadFavorites() {
    const headers = await this.authHeaders();
    if (!headers) {
      this._slugs.next(new Set());
      this._list.next([]);
      this.loading = false;
      this._carregado.next(true);
      return;
    }

    try {
      const list = await firstValueFrom(this.http.get<FavoriteGame[]>(`${this.api}/favorites`, { headers }));
      this._list.next(list);
      this._slugs.next(new Set(list.map(g => g.slug)));
      this._carregado.next(true);
    } catch {
      // Mantem o estado atual; o usuario pode tentar carregar novamente ao navegar. Nao marca como
      // carregado: a tela segue em "carregando" em vez de afirmar que nao ha jogos monitorados.
    } finally {
      this.loading = false;
    }
  }

  async toggle(game: GameSummary) {
    if (this.isFavorited(game.slug)) {
      await this.remove(game.slug);
    } else {
      await this.add(game.slug);
    }
  }

  async remove(slug: string) {
    const headers = await this.authHeaders();
    if (!headers) return;

    const set = new Set(this._slugs.value);
    set.delete(slug);
    this._slugs.next(set);
    this._list.next(this._list.value.filter(g => g.slug !== slug));
    await firstValueFrom(this.http.delete(`${this.api}/favorites/${slug}`, { headers }));
  }

  // Tambem serve pra editar a meta de um jogo ja monitorado (upsert no backend).
  async add(slug: string, targetPrice: number | null = null) {
    const headers = await this.authHeaders();
    if (!headers) return;

    const set = new Set(this._slugs.value);
    set.add(slug);
    this._slugs.next(set);
    await firstValueFrom(this.http.post(`${this.api}/favorites`, { slug, targetPrice }, { headers }));
    await this.load();
  }
}
