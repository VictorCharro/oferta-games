import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { URL_API } from '../configuracao/url-api';
import { supabase } from './supabase';

const BUCKET = 'avatars';

/**
 * Exclusao definitiva da conta (LGPD — issue #17).
 *
 * Duas etapas, nessa ordem:
 * 1. Arquivos do Storage (avatar, banner, imagens de bloco), com a sessao do proprio usuario: a
 *    policy do bucket so deixa o dono apagar a propria pasta, e o banco nao sabe desses arquivos.
 * 2. `DELETE /api/conta`: o backend apaga `auth.users` e as tabelas que nao tem cascade (ver
 *    RepositorioConta). E o passo que realmente encerra a conta.
 *
 * Se a etapa 1 falhar, segue pra 2 mesmo assim: sobrar uma imagem orfa e bem menos grave que nao
 * conseguir excluir a conta que a pessoa pediu pra excluir.
 */
@Injectable({ providedIn: 'root' })
export class ContaService {
  constructor(private http: HttpClient) {}

  async excluirConta(): Promise<void> {
    const { data } = await supabase.auth.getSession();
    const sessao = data.session;
    if (!sessao) throw new Error('Sessão não encontrada');

    await this.apagarArquivos(sessao.user.id).catch(() => undefined);

    await firstValueFrom(this.http.delete(`${URL_API}/conta`, {
      headers: { Authorization: `Bearer ${sessao.access_token}` },
    }));
    await supabase.auth.signOut().catch(() => undefined);
  }

  private async apagarArquivos(usuarioId: string) {
    const caminhos: string[] = [];
    for (const pasta of [usuarioId, `${usuarioId}/blocks`]) {
      const { data } = await supabase.storage.from(BUCKET).list(pasta, { limit: 1000 });
      // Item com id null e subpasta (ex. "blocks"), listada na volta seguinte.
      for (const item of data ?? []) if (item.id) caminhos.push(`${pasta}/${item.name}`);
    }
    if (caminhos.length) await supabase.storage.from(BUCKET).remove(caminhos);
  }
}
