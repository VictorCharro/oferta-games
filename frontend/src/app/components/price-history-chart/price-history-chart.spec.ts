import { temHistoricoParaGrafico } from './price-history-chart';
import { PontoHistoricoPreco } from '../../services/game';

function ponto(price: number, diasAtras: number): PontoHistoricoPreco {
  return {
    price,
    lojaNome: 'Steam',
    capturadoEm: new Date(Date.now() - diasAtras * 86_400_000).toISOString(),
  };
}

describe('temHistoricoParaGrafico', () => {
  it('sem pontos não desenha', () => {
    expect(temHistoricoParaGrafico([])).toBe(false);
  });

  it('um ponto de hoje ainda não formou intervalo', () => {
    // Rastreamento começou hoje: não há período nenhum pra mostrar.
    expect(temHistoricoParaGrafico([ponto(50, 0)])).toBe(false);
  });

  it('um ponto de um dia anterior já rende gráfico', () => {
    // Como o backend só grava em mudança, um ponto antigo significa "preço estável desde então" —
    // que é justamente o que a linha reta até hoje mostra.
    expect(temHistoricoParaGrafico([ponto(50, 1)])).toBe(true);
    expect(temHistoricoParaGrafico([ponto(50, 30)])).toBe(true);
  });

  it('dois ou mais pontos sempre rendem gráfico', () => {
    expect(temHistoricoParaGrafico([ponto(50, 5), ponto(40, 0)])).toBe(true);
  });
});
