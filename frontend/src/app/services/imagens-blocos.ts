import { PerfilBloco } from './perfis';
import { supabase } from './supabase';

const MARCADOR_BUCKET = '/storage/v1/object/public/avatars/';

/**
 * Caminhos no bucket (`{uid}/blocks/...`) das imagens usadas pelos blocos: imagem do bloco de imagem
 * (JSON no conteudo) e imagem de fundo. Avatar e banner nao entram (nao sao de bloco).
 */
export function caminhosImagensDeBlocos(blocos: PerfilBloco[], usuarioId: string): Set<string> {
  const caminhos = new Set<string>();
  const prefixo = `${usuarioId}/blocks/`;
  const registrar = (url: string | null | undefined) => {
    const indice = url ? url.indexOf(MARCADOR_BUCKET) : -1;
    if (!url || indice === -1) return;
    const caminho = decodeURIComponent(url.slice(indice + MARCADOR_BUCKET.length).split('?')[0]);
    if (caminho.startsWith(prefixo)) caminhos.add(caminho);
  };
  for (const bloco of blocos) {
    if (bloco.tipoFundo === 'imagem') registrar(bloco.valorFundo);
    if (bloco.tipo === 'imagem' && bloco.conteudo) {
      try { registrar((JSON.parse(bloco.conteudo) as { url?: string }).url); } catch { /* conteudo antigo sem JSON */ }
    }
  }
  return caminhos;
}

/**
 * Apaga do bucket as imagens de bloco que o layout ANTERIOR usava e o recem-salvo nao usa mais
 * (issue #25). Antes, trocar ou remover a imagem de um bloco deixava o arquivo pra sempre,
 * consumindo a cota do Storage.
 *
 * Chamar SO depois do save dar certo. Falha aqui e silenciosa: uma imagem orfa e aceitavel, um
 * erro na tela depois de o perfil ter sido salvo nao.
 */
export async function removerImagensOrfas(anteriores: PerfilBloco[], atuais: PerfilBloco[]) {
  const { data } = await supabase.auth.getSession().catch(() => ({ data: { session: null } }));
  const usuarioId = data.session?.user.id;
  if (!usuarioId) return;
  const emUso = caminhosImagensDeBlocos(atuais, usuarioId);
  const orfas = [...caminhosImagensDeBlocos(anteriores, usuarioId)].filter(caminho => !emUso.has(caminho));
  if (!orfas.length) return;
  await supabase.storage.from('avatars').remove(orfas).catch(() => undefined);
}
