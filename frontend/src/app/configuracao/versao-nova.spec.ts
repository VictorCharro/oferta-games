import { afterEach, describe, expect, it, vi } from 'vitest';
import { ehChunkDesatualizado, recarregarNaVersaoNova, recarregouHaPouco } from './versao-nova';

describe('ehChunkDesatualizado', () => {
  // Caso real da aba Erros (Chrome Android, 28/09/2026).
  it('reconhece a mensagem do Chrome', () => {
    expect(ehChunkDesatualizado(new TypeError('Failed to fetch dynamically imported module: https://ofertagames.vercel.app/chunk-TWLPJQG5.js'))).toBe(true);
  });

  it('reconhece Safari e Firefox', () => {
    expect(ehChunkDesatualizado(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(ehChunkDesatualizado(new TypeError('error loading dynamically imported module: https://x/chunk-A.js'))).toBe(true);
  });

  it('erro comum nao e chunk velho', () => {
    expect(ehChunkDesatualizado(new TypeError("Cannot read properties of undefined (reading 'x')"))).toBe(false);
    expect(ehChunkDesatualizado(null)).toBe(false);
  });
});

describe('recarregarNaVersaoNova', () => {
  afterEach(() => { sessionStorage.clear(); vi.restoreAllMocks(); });

  it('recarrega uma vez e trava a segunda dentro de 30s (sem loop de recarga)', () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...location, assign });
    expect(recarregarNaVersaoNova('/jogo/x')).toBe(true);
    expect(assign).toHaveBeenCalledWith('/jogo/x');
    expect(recarregarNaVersaoNova('/jogo/x')).toBe(false);
    expect(assign).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });

  // A marca gravada por ESTA pagina (a caminho da recarga) nao conta como "ja recarregou".
  it('recarregouHaPouco so vale pra recarga feita antes desta pagina nascer', () => {
    sessionStorage.setItem('oferta-games-recarga-versao-nova', String(Date.now()));
    expect(recarregouHaPouco()).toBe(false);
    sessionStorage.setItem('oferta-games-recarga-versao-nova', String(performance.timeOrigin - 1000));
    expect(recarregouHaPouco()).toBe(Date.now() - (performance.timeOrigin - 1000) < 30_000);
  });
});
