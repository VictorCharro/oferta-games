/**
 * Texto do aviso ao salvar o monitoramento de um jogo (26/09/2026). Um lugar so pra card, pagina do
 * jogo e card de monitorados dizerem a mesma coisa.
 *
 * O aviso diz o que muda pra pessoa (quando ela vai ser avisada), nao so "salvo". Sem meta, o alerta
 * vem quando o preco cai de forma relevante; com meta, quando chega nela (ver doc.md, "Jogos
 * Monitorados").
 *
 * @param jaMonitorava se o jogo ja estava nos monitorados antes deste salvar (entao e edicao de meta)
 * @param meta valor em reais, ou null quando a pessoa deixou sem meta
 */
export function mensagemMeta(titulo: string, jaMonitorava: boolean, meta: number | null): string {
  const valor = meta != null ? meta.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }) : null;
  if (!jaMonitorava) {
    return valor
      ? `Monitorando ${titulo}: você será avisado quando chegar a ${valor}.`
      : `Monitorando ${titulo}: você será avisado quando o preço cair.`;
  }
  return valor
    ? `Meta de ${titulo} atualizada para ${valor}.`
    : `Meta de ${titulo} removida. Você continua sendo avisado quando o preço cair.`;
}
