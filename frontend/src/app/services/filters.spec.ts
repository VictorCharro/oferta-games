import { isDlc, resolveDlc } from './filters';

describe('isDlc', () => {
  it('reconhece DLCs pelo padrão do título', () => {
    expect(isDlc('Cyberpunk 2077: Phantom Liberty DLC')).toBe(true);
    expect(isDlc('The Witcher 3 - Season Pass')).toBe(true);
    expect(isDlc('Original Soundtrack')).toBe(true);
  });

  it('não reconhece jogo comum como DLC', () => {
    expect(isDlc('Baldur\'s Gate 3')).toBe(false);
    expect(isDlc('Cult of the Lamb')).toBe(false);
  });
});

describe('resolveDlc', () => {
  it('usa a flag da Steam quando definida, mesmo contra a heurística de título', () => {
    expect(resolveDlc('Cult of the Lamb', true)).toBe(true);
    expect(resolveDlc('Some Game DLC', false)).toBe(false);
  });

  it('cai pra heurística de título quando a flag é null/undefined', () => {
    expect(resolveDlc('Some Game DLC', null)).toBe(true);
    expect(resolveDlc('Some Game DLC', undefined)).toBe(true);
    expect(resolveDlc('Baldur\'s Gate 3', null)).toBe(false);
  });
});
