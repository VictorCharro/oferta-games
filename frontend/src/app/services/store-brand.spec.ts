import { storeBrand, storePlatforms } from './store-brand';

describe('storeBrand', () => {
  it('reconhece lojas conhecidas por substring, sem diferenciar caixa', () => {
    expect(storeBrand('Steam').name).toBe('Steam');
    expect(storeBrand('STEAM').name).toBe('Steam');
    expect(storeBrand('Epic Games').name).toBe('Epic Games Store');
    expect(storeBrand('GOG.com').name).toBe('GOG');
  });

  it('cai pro nome original quando a loja não é reconhecida', () => {
    const marca = storeBrand('Loja Desconhecida XYZ');
    expect(marca.name).toBe('Loja Desconhecida XYZ');
  });

  it('cai pra marca padrão quando não há nome de loja', () => {
    expect(storeBrand(null).name).toBe('Loja');
    expect(storeBrand(undefined).name).toBe('Loja');
    expect(storeBrand('').name).toBe('Loja');
  });
});

describe('storePlatforms', () => {
  it('reconhece PlayStation pela loja ou pela url', () => {
    expect(storePlatforms('PlayStation Store').map(p => p.name)).toEqual(['PlayStation']);
    expect(storePlatforms('Alguma Loja', 'https://store.playstation.com/jogo').map(p => p.name)).toEqual(['PlayStation']);
  });

  it('reconhece Xbox', () => {
    expect(storePlatforms('Xbox Store').map(p => p.name)).toEqual(['Xbox']);
  });

  it('Microsoft Store conta como Xbox e PC juntos', () => {
    expect(storePlatforms('Microsoft Store').map(p => p.name)).toEqual(['Xbox', 'PC']);
  });

  it('cai pra PC quando nada indica outra plataforma', () => {
    expect(storePlatforms('Steam').map(p => p.name)).toEqual(['PC']);
    expect(storePlatforms(null, null).map(p => p.name)).toEqual(['PC']);
  });
});
