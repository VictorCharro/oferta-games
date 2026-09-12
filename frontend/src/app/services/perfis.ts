import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { supabase } from './supabase';
import { URL_API } from '../configuracao/url-api';

export interface PerfilProprio { handle: string | null; nomeExibicao: string; bio: string | null; publico: boolean; mostrarHoras: boolean; mostrarConquistas: boolean; mostrarBiblioteca: boolean; mostrarFavoritos: boolean; mostrarAtividades: boolean; mostrarColecoes: boolean; avatarZoom: number; avatarPosicaoX: number; avatarPosicaoY: number; bannerUrl: string | null; bannerZoom: number; bannerPosicaoX: number; bannerPosicaoY: number; }
export type EntradaPerfil = Pick<PerfilProprio, 'handle' | 'nomeExibicao' | 'publico' | 'mostrarHoras' | 'mostrarConquistas' | 'mostrarBiblioteca' | 'mostrarFavoritos' | 'mostrarAtividades' | 'mostrarColecoes'> & { bio: string };
export interface PerfilBloco { id: string; tipo: 'favoritos' | 'biblioteca' | 'atividade' | 'platinados' | 'wishlist' | 'conquistas-recentes' | 'mais-jogados' | 'texto' | 'imagem' | 'links'; titulo: string | null; conteudo: string | null; posicao: number; tamanho: 'pequeno' | 'medio' | 'largo' | 'completo'; visivel: boolean; tipoFundo: 'padrao' | 'cor' | 'imagem' | 'gradiente'; valorFundo: string | null; opacidade: number; corTexto: string | null; visualizacao: 'cards' | 'lista' | null; }
// Um jogo de colecao tem a mesma forma de um favorito (catalogo ou Steam), entao reusa os helpers de card.
export interface JogoPerfil { slug: string | null; steamAppId: number | null; titulo: string; capaUrl: string | null; iconeHash: string | null; ehDlc: boolean | null; minutosJogadas: number | null; conquistasDesbloqueadas: number | null; conquistasTotal: number | null; precoMinimo: number | null; precoRegular: number | null; favoritadoEm: string; }
// origemSistema: true pra colecoes geridas automaticamente (hoje so a wishlist da Steam) - o
// dono nao pode renomear/excluir/adicionar/remover item, so ela sincroniza sozinha.
export interface ColecaoPerfil { id: number; nome: string; origemSistema: boolean; jogos: JogoPerfil[]; }
export interface PerfilPublico { handle: string; nomeExibicao: string; bio: string | null; avatarUrl: string | null; avatarZoom: number; avatarPosicaoX: number; avatarPosicaoY: number; bannerUrl: string | null; bannerZoom: number; bannerPosicaoX: number; bannerPosicaoY: number; totalMinutos: number | null; conquistasDesbloqueadas: number | null; conquistasTotal: number | null; jogosPlatinados: number | null; totalJogosBiblioteca: number | null; plataformasConectadas: Array<'steam' | 'xbox'>; biblioteca: Array<{ appId: number; titulo: string; minutosJogadas: number; iconeHash: string | null; conquistasDesbloqueadas: number; conquistasTotal: number; capaUrl: string | null; catalogSlug: string | null; platinumPosition: number | null; plataforma: 'steam' | 'xbox' }>; favoritos: JogoPerfil[]; colecoes: ColecaoPerfil[]; atividades: Array<{ tipo: string; tituloJogo: string | null; detalhe: string | null; criadaEm: string }>; mostrarAtividades: boolean; blocos: PerfilBloco[]; mostrarWishlistSteam: boolean; temColecaoWishlistSteam: boolean; conquistasRecentes: ConquistaRecente[]; }

// iconeUrl vem nulo quando o jogo ainda nao tem conquistas detalhadas no catalogo (o nome cai no
// derivado do api_name) - o card usa um placeholder nesse caso.
export interface ConquistaRecente { appId: number; titulo: string; tituloJogo: string | null; iconeUrl: string | null; desbloqueadaEm: string; }

@Injectable({ providedIn: 'root' })
export class PerfisService {
  private readonly api = `${URL_API}/perfis`;
  constructor(private http: HttpClient) {}
  async proprio(): Promise<PerfilProprio | null> { return firstValueFrom(this.http.get<PerfilProprio | null>(`${this.api}/me`, { headers: await this.headers() })); }
  async salvar(perfil: EntradaPerfil): Promise<void> { await firstValueFrom(this.http.put(`${this.api}/me`, perfil, { headers: await this.headers() })); }
  async atualizarAvatar(avatarUrl: string, zoom: number, posicaoX: number, posicaoY: number): Promise<void> { await firstValueFrom(this.http.put(`${this.api}/me/avatar`, { avatarUrl, zoom, posicaoX, posicaoY }, { headers: await this.headers() })); }
  async atualizarBanner(bannerUrl: string, zoom: number, posicaoX: number, posicaoY: number): Promise<void> { await firstValueFrom(this.http.put(`${this.api}/me/banner`, { bannerUrl, zoom, posicaoX, posicaoY }, { headers: await this.headers() })); }
  async atualizarMostrarWishlistSteam(mostrar: boolean): Promise<void> { await firstValueFrom(this.http.put(`${this.api}/me/wishlist-steam`, { mostrar }, { headers: await this.headers() })); }
  async salvarBlocos(blocos: PerfilBloco[]): Promise<void> { await firstValueFrom(this.http.put(`${this.api}/me/blocos`, blocos, { headers: await this.headers() })); }
  async publico(handle: string): Promise<PerfilPublico> { return firstValueFrom(this.http.get<PerfilPublico>(`${this.api}/${encodeURIComponent(handle)}`, { headers: await this.optionalHeaders() })); }
  async atualizarPublico(handle: string): Promise<{ status: 'agendada' | 'aguarde' | 'sem_conexao' }> { return firstValueFrom(this.http.post<{ status: 'agendada' | 'aguarde' | 'sem_conexao' }>(`${this.api}/${encodeURIComponent(handle)}/atualizar`, {}, { headers: await this.optionalHeaders() })); }
  private async headers(): Promise<{ Authorization: string }> { const { data } = await supabase.auth.getSession(); if (!data.session?.access_token) throw new Error('Sessao nao encontrada'); return { Authorization: `Bearer ${data.session.access_token}` }; }
  private async optionalHeaders(): Promise<{ Authorization?: string }> { const { data } = await supabase.auth.getSession(); return data.session?.access_token ? { Authorization: `Bearer ${data.session.access_token}` } : {}; }
}
