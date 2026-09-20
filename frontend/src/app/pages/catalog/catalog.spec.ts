import { Subject } from 'rxjs';
import { vi } from 'vitest';
import { Catalog } from './catalog';

describe('paginacao do catalogo', () => {
  it('repete a pagina que falhou sem apagar os jogos ja carregados', () => {
    const respostas = [new Subject<any[]>(), new Subject<any[]>(), new Subject<any[]>()];
    const getGames = vi.fn().mockImplementation(() => respostas[getGames.mock.calls.length - 1]);
    const catalogo = new Catalog({ getGames } as any, { detectChanges() {} } as any,
      {} as any, {} as any, {} as any, {} as any);
    vi.useFakeTimers();
    try {
      catalogo.load(true);
      respostas[0].next(Array.from({ length: 20 }, (_, i) => ({ slug: String(i) })));
      catalogo.loadMore();
      respostas[1].error(new Error('503'));
      expect(catalogo.games.length).toBe(20);
      catalogo.loadMore();
      expect(getGames.mock.calls.map(c => c[0])).toEqual([0, 1, 1]);
      respostas[2].next([{ slug: '20' }]);
      expect(catalogo.games.length).toBe(21);
      expect(catalogo.hasMore).toBe(false);
    } finally { vi.clearAllTimers(); vi.useRealTimers(); }
  });
});
