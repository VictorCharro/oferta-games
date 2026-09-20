import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { FavoritesService } from './favorites';

describe('escrita de monitorados', () => {
  it('preserva monitorados e meta quando a API recusa a escrita', async () => {
    const jogo = { slug: 'portal', targetPrice: 10 };
    const http = {
      get: vi.fn(() => of([jogo])),
      post: vi.fn(() => throwError(() => new Error('503'))),
      delete: vi.fn(() => throwError(() => new Error('503'))),
    };
    const service = new FavoritesService(http as any,
      { user$: of(null), user: null, sessaoResolvida: true } as any);
    vi.spyOn(service as any, 'authHeaders').mockResolvedValue({ Authorization: 'teste' });
    await service.load();
    await expect(service.remove('portal')).rejects.toThrow();
    expect(service.isFavorited('portal')).toBe(true);
    expect(service.getFavorite('portal')?.targetPrice).toBe(10);
    await expect(service.add('novo')).rejects.toThrow();
    expect(service.isFavorited('novo')).toBe(false);
    await expect(service.add('portal', 5)).rejects.toThrow();
    expect(service.getFavorite('portal')?.targetPrice).toBe(10);
  });
});
