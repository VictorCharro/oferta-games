import { Offer } from '../../services/game';
import { compararOfertas } from './comparar-ofertas';

const oferta = (storeName: string, price: number): Offer =>
  ({ storeName, price, regularPrice: 100, currency: 'BRL', url: '', voucherCode: null });

describe('compararOfertas', () => {
  it('nada mudou: resumo diz que conferiu, sem destaque', () => {
    const r = compararOfertas([oferta('Steam', 50)], [oferta('Steam', 50)]);
    expect(r.houveMudanca).toBe(false);
    expect(r.porLoja.size).toBe(0);
    expect(r.resumo).toBe('Preços conferidos agora. Nenhuma loja mudou de preço.');
  });

  it('marca por loja o que caiu, subiu e entrou, e conta o que saiu', () => {
    const r = compararOfertas(
      [oferta('Steam', 50), oferta('Nuuvem', 40), oferta('GOG', 30)],
      [oferta('Steam', 45), oferta('Nuuvem', 42), oferta('Epic', 60)],
    );
    expect(r.porLoja.get('Steam')).toBe('caiu');
    expect(r.porLoja.get('Nuuvem')).toBe('subiu');
    expect(r.porLoja.get('Epic')).toBe('nova');
    expect(r.resumo).toBe('Preços atualizados: 1 loja baixou, 1 loja subiu, 1 loja nova e 1 oferta saiu.');
  });

  it('diferença de arredondamento (menos de 1 centavo) não conta como mudança', () => {
    expect(compararOfertas([oferta('Steam', 49.99)], [oferta('Steam', 49.9900001)]).houveMudanca).toBe(false);
  });

  it('acende o destaque do topo só quando o menor preço da página baixa', () => {
    expect(compararOfertas([oferta('Steam', 50), oferta('Nuuvem', 40)], [oferta('Steam', 35), oferta('Nuuvem', 40)]).melhorPrecoCaiu).toBe(true);
    expect(compararOfertas([oferta('Steam', 50), oferta('Nuuvem', 40)], [oferta('Steam', 45), oferta('Nuuvem', 40)]).melhorPrecoCaiu).toBe(false);
  });

  it('plural no resumo', () => {
    const r = compararOfertas([oferta('Steam', 50), oferta('Nuuvem', 40)], [oferta('Steam', 45), oferta('Nuuvem', 35)]);
    expect(r.resumo).toBe('Preços atualizados: 2 lojas baixaram.');
  });
});
