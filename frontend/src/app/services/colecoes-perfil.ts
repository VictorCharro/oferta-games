import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ColecaoPerfil } from './perfis';
import { supabase } from './supabase';
import { URL_API } from '../configuracao/url-api';

@Injectable({ providedIn: 'root' })
export class ColecoesPerfilService {
  private readonly api = `${URL_API}/profile-collections`;

  constructor(private http: HttpClient) {}

  async listar(): Promise<ColecaoPerfil[]> {
    const headers = await this.authHeaders();
    if (!headers) return [];
    return firstValueFrom(this.http.get<ColecaoPerfil[]>(this.api, { headers }));
  }

  async criar(nome: string): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    await firstValueFrom(this.http.post(this.api, { nome }, { headers }));
  }

  async renomear(colecaoId: number, nome: string): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    await firstValueFrom(this.http.put(`${this.api}/${colecaoId}`, { nome }, { headers }));
  }

  async excluir(colecaoId: number): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    await firstValueFrom(this.http.delete(`${this.api}/${colecaoId}`, { headers }));
  }

  async adicionarItem(colecaoId: number, item: { slug?: string; steamAppId?: number }): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    await firstValueFrom(this.http.post(`${this.api}/${colecaoId}/itens`, item, { headers }));
  }

  async removerJogo(colecaoId: number, slug: string): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    await firstValueFrom(this.http.delete(`${this.api}/${colecaoId}/itens/jogo/${encodeURIComponent(slug)}`, { headers }));
  }

  async removerSteam(colecaoId: number, appId: number): Promise<void> {
    const headers = await this.authHeaders();
    if (!headers) return;
    await firstValueFrom(this.http.delete(`${this.api}/${colecaoId}/itens/steam/${appId}`, { headers }));
  }

  private async authHeaders(): Promise<{ Authorization: string } | null> {
    const { data } = await supabase.auth.getSession();
    return data.session?.access_token ? { Authorization: `Bearer ${data.session.access_token}` } : null;
  }
}
