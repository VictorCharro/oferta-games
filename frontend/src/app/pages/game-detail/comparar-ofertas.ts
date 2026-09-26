import { Offer } from '../../services/game';

export type MudancaOferta = 'caiu' | 'subiu' | 'nova';

export interface ResultadoComparacao {
  /** Por loja: o que aconteceu com a oferta dela. Loja sem mudanca nao aparece. */
  porLoja: Map<string, MudancaOferta>;
  /** Texto do aviso depois de "Atualizar preços". */
  resumo: string;
  houveMudanca: boolean;
  /** O menor preco da pagina baixou (o destaque do topo acende). */
  melhorPrecoCaiu: boolean;
}

/**
 * Compara as ofertas de antes e depois do "Atualizar preços" (26/09/2026). Antes a tabela trocava
 * os numeros em silencio e a pessoa nao sabia se o botao tinha feito alguma coisa. Agora a linha que
 * mudou pisca (verde se baixou, vermelha se subiu, entra com fade se e nova) e o aviso resume.
 *
 * A oferta nao tem id no front: a chave e a loja, que e unica por jogo na tabela.
 * Diferenca menor que 1 centavo e ruido de arredondamento, nao mudanca.
 */
export function compararOfertas(antes: Offer[], depois: Offer[]): ResultadoComparacao {
  const precoAntes = new Map(antes.map(o => [o.storeName, Number(o.price)]));
  const lojasDepois = new Set(depois.map(o => o.storeName));
  const porLoja = new Map<string, MudancaOferta>();
  let caiu = 0, subiu = 0, nova = 0;

  for (const oferta of depois) {
    const anterior = precoAntes.get(oferta.storeName);
    const atual = Number(oferta.price);
    if (anterior === undefined) { porLoja.set(oferta.storeName, 'nova'); nova++; }
    else if (atual < anterior - 0.009) { porLoja.set(oferta.storeName, 'caiu'); caiu++; }
    else if (atual > anterior + 0.009) { porLoja.set(oferta.storeName, 'subiu'); subiu++; }
  }
  const saiu = antes.filter(o => !lojasDepois.has(o.storeName)).length;

  const menor = (ofertas: Offer[]) => ofertas.length ? Math.min(...ofertas.map(o => Number(o.price))) : Infinity;
  const melhorPrecoCaiu = menor(depois) < menor(antes) - 0.009;

  const partes = [
    contar(caiu, 'loja baixou', 'lojas baixaram'),
    contar(subiu, 'loja subiu', 'lojas subiram'),
    contar(nova, 'loja nova', 'lojas novas'),
    contar(saiu, 'oferta saiu', 'ofertas saíram'),
  ].filter(Boolean);
  const houveMudanca = partes.length > 0;

  return {
    porLoja,
    houveMudanca,
    melhorPrecoCaiu,
    resumo: houveMudanca
      ? `Preços atualizados: ${juntar(partes as string[])}.`
      : 'Preços conferidos agora. Nenhuma loja mudou de preço.',
  };
}

function contar(n: number, singular: string, plural: string): string | null {
  if (n === 0) return null;
  return `${n} ${n === 1 ? singular : plural}`;
}

function juntar(partes: string[]): string {
  return partes.length === 1 ? partes[0] : `${partes.slice(0, -1).join(', ')} e ${partes[partes.length - 1]}`;
}
