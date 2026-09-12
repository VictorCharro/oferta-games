import { PerfilBloco } from './perfis';

// Metadados dos blocos de perfil, compartilhados entre o editor inline do perfil
// (public-profile) e a pagina dedicada /perfil/blocos. Ficam aqui pra nao duplicar
// a lista de tipos em dois lugares - quando um tipo novo entra, so este arquivo muda.

/** Tipos que so podem existir uma vez por perfil (leem dados unicos do dono). */
export const TIPOS_UNICOS: PerfilBloco['tipo'][] = ['favoritos', 'biblioteca', 'atividade', 'platinados', 'wishlist', 'conquistas-recentes', 'mais-jogados'];

export interface DescricaoBloco {
  tipo: PerfilBloco['tipo'];
  titulo: string;
  descricao: string;
  icone: string;
}

/** Ordem usada na "Biblioteca de Blocos" e no dropdown "Adicionar". */
export const CATALOGO_BLOCOS: DescricaoBloco[] = [
  { tipo: 'platinados', titulo: 'Jogos Platinados', descricao: 'Jogos com 100% das conquistas', icone: '🏆' },
  { tipo: 'favoritos', titulo: 'Jogos Favoritos', descricao: 'Seus jogos marcados como favoritos', icone: '❤️' },
  { tipo: 'conquistas-recentes', titulo: 'Conquistas Recentes', descricao: 'Ultimas conquistas desbloqueadas', icone: '🏅' },
  { tipo: 'mais-jogados', titulo: 'Mais Jogados', descricao: 'Ranking por horas na biblioteca', icone: '⏱️' },
  { tipo: 'biblioteca', titulo: 'Biblioteca', descricao: 'Jogos das plataformas conectadas', icone: '🎮' },
  { tipo: 'wishlist', titulo: 'Lista de Desejos (Steam)', descricao: 'Sincronizada com a wishlist da Steam', icone: '⭐' },
  { tipo: 'atividade', titulo: 'Atividade Recente', descricao: 'O que voce andou jogando', icone: '📡' },
  { tipo: 'texto', titulo: 'Texto', descricao: 'Um texto livre sobre voce', icone: '📝' },
  { tipo: 'imagem', titulo: 'Imagem', descricao: 'Uma imagem sua ou de um jogo', icone: '🖼️' },
  { tipo: 'links', titulo: 'Links', descricao: 'Links para suas redes', icone: '🔗' },
];

export function descricaoBloco(tipo: PerfilBloco['tipo']): DescricaoBloco {
  return CATALOGO_BLOCOS.find(item => item.tipo === tipo)
    ?? { tipo, titulo: tipo, descricao: '', icone: '▦' };
}

/**
 * Layout inicial de quem nunca mexeu nos blocos. largo (8/12) + pequeno (4/12) fecham uma
 * linha do grid: platinados a esquerda e favoritos na coluna da direita, como no Figma.
 */
export function blocosPadrao(): PerfilBloco[] {
  const bloco = (id: string, tipo: PerfilBloco['tipo'], posicao: number, tamanho: PerfilBloco['tamanho']): PerfilBloco =>
    ({ id, tipo, titulo: null, conteudo: null, posicao, tamanho, visivel: true, tipoFundo: 'padrao', valorFundo: null, opacidade: 0, corTexto: null });
  return [
    bloco('platinados', 'platinados', 0, 'largo'),
    bloco('favoritos', 'favoritos', 1, 'pequeno'),
    bloco('conquistas-recentes', 'conquistas-recentes', 2, 'largo'),
    bloco('biblioteca', 'biblioteca', 3, 'medio'),
    bloco('atividade', 'atividade', 4, 'medio'),
  ];
}

/** Bloco novo com os padroes de cada tipo. Blocos unicos usam o proprio tipo como id. */
export function novoBloco(tipo: PerfilBloco['tipo'], posicao: number): PerfilBloco {
  return {
    id: TIPOS_UNICOS.includes(tipo) ? tipo : `custom-${crypto.randomUUID()}`,
    tipo,
    titulo: tipo === 'texto' ? 'Novo texto' : tipo === 'imagem' ? 'Imagem' : tipo === 'links' ? 'Links' : null,
    conteudo: tipo === 'texto' ? 'Escreva algo sobre você.' : '',
    posicao,
    tamanho: 'medio',
    visivel: true,
    tipoFundo: 'padrao',
    valorFundo: null,
    opacidade: 0,
    corTexto: null,
  };
}
