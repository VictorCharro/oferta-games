import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { supabase } from './supabase';
import { URL_API } from '../configuracao/url-api';

/**
 * Pergunta ao backend se a pessoa logada e admin (`GET /api/admin/acesso`, 204 ou 401/403). A regra
 * de verdade mora no servidor (ADMIN_USER_IDS); aqui so decide se abre a pagina /admin e se mostra
 * o atalho "Administração" no menu da conta (26/09/2026).
 *
 * Guarda a resposta por usuario: trocar de conta refaz a pergunta, e abrir o menu varias vezes nao
 * repete a requisicao. Erro de rede conta como "nao e admin" e nao fica guardado.
 */
@Injectable({ providedIn: 'root' })
export class AcessoAdminService {
  private readonly http = inject(HttpClient);
  private usuarioDaResposta: string | null = null;
  private resposta: Promise<boolean> | null = null;

  async ehAdmin(): Promise<boolean> {
    const { data } = await supabase.auth.getSession();
    const sessao = data.session;
    if (!sessao) return false;
    if (this.resposta && this.usuarioDaResposta === sessao.user.id) return this.resposta;

    this.usuarioDaResposta = sessao.user.id;
    this.resposta = firstValueFrom(this.http.get(`${URL_API}/admin/acesso`, {
      headers: { Authorization: `Bearer ${sessao.access_token}` },
    })).then(() => true, (erro: { status?: number }) => {
      // 401/403 e resposta de verdade ("nao e admin"); falha de rede nao, pode tentar de novo.
      if (erro?.status !== 401 && erro?.status !== 403) this.resposta = null;
      return false;
    });
    return this.resposta;
  }
}
