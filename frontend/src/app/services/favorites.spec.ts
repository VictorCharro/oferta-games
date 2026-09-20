import { of, throwError, Subject } from 'rxjs';
import { vi } from 'vitest';
import { FavoritesService } from './favorites';

describe('escrita de monitorados', () => {
  it('descarta leitura da conta anterior e carrega a conta atual', async () => {
    const user$ = new Subject<any>();
    const antiga = new Subject<any[]>();
    const auth = { user$, user: { id: 'antiga' }, sessaoResolvida: true };
    const http = { get: vi.fn().mockReturnValueOnce(antiga).mockReturnValueOnce(of([{ slug: 'novo' }])) };
    const service = new FavoritesService(http as any, auth as any);
    vi.spyOn(service as any, 'authHeaders').mockResolvedValue({ Authorization: 'teste' });
    user$.next(auth.user);
    await vi.waitFor(() => expect(http.get).toHaveBeenCalledTimes(1));
    auth.user = { id: 'nova' };
    user$.next(auth.user);
    antiga.next([{ slug: 'antigo' }]);
    await vi.waitFor(() => expect(service.isFavorited('novo')).toBe(true));
    expect(service.isFavorited('antigo')).toBe(false);
  });
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
