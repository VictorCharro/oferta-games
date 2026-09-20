import { TransferState, makeStateKey } from '@angular/core';

/** Reutiliza a ordem do HTML no primeiro render do navegador; novas navegacoes podem sortear. */
export function sortearHome<T extends { slug: string }>(itens: T[], estado: TransferState, nome: string, navegador: boolean): T[] {
  const chave = makeStateKey<string[]>(`home-sorteio-${nome}`);
  if (estado.hasKey(chave)) {
    const ordem = estado.get(chave, []);
    if (navegador) estado.remove(chave);
    const porSlug = new Map(itens.map(item => [item.slug, item]));
    return ordem.map(slug => porSlug.get(slug)).filter((item): item is T => item != null);
  }
  const resultado = [...itens];
  for (let i = resultado.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [resultado[i], resultado[j]] = [resultado[j], resultado[i]];
  }
  if (!navegador) estado.set(chave, resultado.map(item => item.slug));
  return resultado;
}
