import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { supabase } from './supabase';
import { URL_API } from '../configuracao/url-api';

export interface StatusColeta {
  tipo: string;
  emExecucao: boolean;
  inicioAtual: string | null;
  duracaoAtualMs: number | null;
  ultimaConclusao: string | null;
  ultimaDuracaoMs: number | null;
  jogosAtualizados: number;
  ofertasAtualizadas: number;
  ultimoErro: string | null;
}

export interface ResumoFilaInstantGaming {
  ultimoIdEscaneado: number;
  catalogoDescoberto: number;
  jogosCasados: number;
  pendentesCasamento: number;
}

export interface StatusAdministrativoColeta {
  precos: StatusColeta;
  steam: StatusColeta;
  detalhes: StatusColeta;
  conquistasCatalogo: StatusColeta;
  instantGamingEscaneamento: StatusColeta;
  instantGamingCasamento: StatusColeta;
  instantGamingPrecos: StatusColeta;
  fila: {
    nuncaSincronizados: number;
    sincronizacaoMaisAntiga: string | null;
    pendentesSteam: number;
  };
  pendentesDetalhes: number;
  pendentesConquistas: number;
  filaInstantGaming: ResumoFilaInstantGaming;
}

export interface ResultadoPreenchimentoJogo {
  metadadosSteamAtualizados: boolean;
  temSteamAppId: boolean;
  detalhesAtualizados: boolean;
  conquistasAtualizadas: boolean;
}

export type TipoColeta =
  | 'precos'
  | 'steam'
  | 'detalhes'
  | 'conquistas-catalogo'
  | 'instant-gaming-escaneamento'
  | 'instant-gaming-casamento'
  | 'instant-gaming-precos';

export interface DenunciaAberta {
  id: number;
  handle: string | null;
  nomeExibicao: string | null;
  perfilBloqueado: boolean;
  motivo: string;
  criadaEm: string;
  totalDoPerfil: number;
}

export interface MensagemContato {
  id: number;
  tipo: 'elogio' | 'sugestao' | 'problema' | 'denuncia' | 'outro';
  mensagem: string;
  email: string | null;
  pagina: string | null;
  handle: string | null;
  criadaEm: string;
  /** Enviada com conta: da pra responder no site. Anonima nao tem pra quem entregar. */
  respondivel: boolean;
  resposta: string | null;
  respondidaEm: string | null;
  anexos: AnexoContato[];
}

export interface AnexoContato {
  id: number;
  contentType: string;
  tamanho: number;
  nomeOriginal: string | null;
}

export interface ErroRegistrado {
  id: number;
  origem: 'navegador' | 'servidor';
  mensagem: string;
  detalhe: string | null;
  pagina: string | null;
  userAgent: string | null;
  ocorrencias: number;
  primeiraEm: string;
  ultimaEm: string;
}

export interface PerfilBloqueado {
  handle: string;
  nomeExibicao: string | null;
  bloqueadoEm: string;
}

@Injectable({ providedIn: 'root' })
export class AdministracaoService {
  private api = `${URL_API}/admin`;

  constructor(private http: HttpClient) {}

  async listarMensagensContato(lidas = false): Promise<MensagemContato[]> {
    return firstValueFrom(this.http.get<MensagemContato[]>(`${this.api}/contato?lidas=${lidas}`, { headers: await this.cabecalhosAutorizacao() }));
  }

  /** Arquivo do anexo como Blob: a rota exige o token, entao nao da pra usar a URL direto num <img>. */
  async baixarAnexoContato(id: number): Promise<Blob> {
    return firstValueFrom(this.http.get(`${this.api}/contato/anexos/${id}`, { headers: await this.cabecalhosAutorizacao(), responseType: 'blob' }));
  }

  async responderMensagemContato(id: number, resposta: string): Promise<void> {
    await firstValueFrom(this.http.post(`${this.api}/contato/${id}/responder`, { resposta }, { headers: await this.cabecalhosAutorizacao() }));
  }

  async listarErros(resolvidos = false): Promise<ErroRegistrado[]> {
    return firstValueFrom(this.http.get<ErroRegistrado[]>(`${this.api}/erros?resolvidos=${resolvidos}`, { headers: await this.cabecalhosAutorizacao() }));
  }

  async resolverErro(id: number): Promise<void> {
    await firstValueFrom(this.http.post(`${this.api}/erros/${id}/resolver`, {}, { headers: await this.cabecalhosAutorizacao() }));
  }

  async listarPerfisBloqueados(): Promise<PerfilBloqueado[]> {
    return firstValueFrom(this.http.get<PerfilBloqueado[]>(`${this.api}/perfis/bloqueados`, { headers: await this.cabecalhosAutorizacao() }));
  }

  async resolverMensagemContato(id: number): Promise<void> {
    await firstValueFrom(this.http.post(`${this.api}/contato/${id}/resolver`, {}, { headers: await this.cabecalhosAutorizacao() }));
  }

  async listarDenuncias(): Promise<DenunciaAberta[]> {
    return firstValueFrom(this.http.get<DenunciaAberta[]>(`${this.api}/denuncias`, { headers: await this.cabecalhosAutorizacao() }));
  }

  async resolverDenuncia(id: number): Promise<void> {
    await firstValueFrom(this.http.post(`${this.api}/denuncias/${id}/resolver`, {}, { headers: await this.cabecalhosAutorizacao() }));
  }

  async definirBloqueio(handle: string, bloqueado: boolean): Promise<void> {
    await firstValueFrom(this.http.put(`${this.api}/perfis/${encodeURIComponent(handle)}/bloqueio`, { bloqueado }, { headers: await this.cabecalhosAutorizacao() }));
  }

  async consultarColeta(): Promise<StatusAdministrativoColeta> {
    return firstValueFrom(this.http.get<StatusAdministrativoColeta>(`${this.api}/coleta`, {
      headers: await this.cabecalhosAutorizacao(),
    }));
  }

  async dispararColeta(tipo: TipoColeta): Promise<void> {
    await firstValueFrom(this.http.post(`${this.api}/coleta/${tipo}`, {}, {
      headers: await this.cabecalhosAutorizacao(),
    }));
  }

  async preencherJogo(slug: string): Promise<ResultadoPreenchimentoJogo> {
    return firstValueFrom(this.http.post<ResultadoPreenchimentoJogo>(`${this.api}/jogos/${slug}/preencher-tudo`, {}, {
      headers: await this.cabecalhosAutorizacao(),
    }));
  }

  private async cabecalhosAutorizacao(): Promise<{ Authorization: string }> {
    const { data } = await supabase.auth.getSession();
    const token = data.session?.access_token;
    if (!token) throw new Error('Sessão administrativa não encontrada');
    return { Authorization: `Bearer ${token}` };
  }
}
