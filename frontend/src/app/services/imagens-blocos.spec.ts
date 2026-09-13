import { caminhosImagensDeBlocos } from './imagens-blocos';
import { PerfilBloco } from './perfis';

const UID = 'u-1';
const BASE = 'https://proj.supabase.co/storage/v1/object/public/avatars/';

function bloco(parcial: Partial<PerfilBloco>): PerfilBloco {
  return { id: 'b', tipo: 'texto', titulo: '', conteudo: '', posicao: 0, tamanho: 'medio', visivel: true, tipoFundo: 'padrao', valorFundo: null, opacidade: 0, corTexto: null, visualizacao: null, ...parcial } as PerfilBloco;
}

describe('caminhosImagensDeBlocos', () => {
  it('pega imagem do bloco de imagem e imagem de fundo, só da pasta blocks do próprio usuário', () => {
    const caminhos = caminhosImagensDeBlocos([
      bloco({ tipo: 'imagem', conteudo: JSON.stringify({ url: `${BASE}${UID}/blocks/a-1`, zoom: 1 }) }),
      bloco({ tipoFundo: 'imagem', valorFundo: `${BASE}${UID}/blocks/fundo-2?t=9` }),
      // Nunca apagar: avatar, pasta de outro usuário e URL externa.
      bloco({ tipoFundo: 'imagem', valorFundo: `${BASE}${UID}/avatar` }),
      bloco({ tipo: 'imagem', conteudo: JSON.stringify({ url: `${BASE}outro/blocks/x` }) }),
      bloco({ tipo: 'imagem', conteudo: JSON.stringify({ url: 'https://i.pinimg.com/a.jpg' }) }),
      bloco({ tipo: 'imagem', conteudo: 'formato antigo sem json' }),
    ], UID);
    expect([...caminhos].sort()).toEqual([`${UID}/blocks/a-1`, `${UID}/blocks/fundo-2`]);
  });
});
