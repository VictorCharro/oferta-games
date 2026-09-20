import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Topbar } from './topbar';

describe('sugestoes da topbar', () => {
  it('continua buscando depois de falha HTTP e limpa termos curtos', () => {
    vi.useFakeTimers();
    const searchGames = vi.fn().mockReturnValueOnce(throwError(() => new Error('503')))
      .mockReturnValue(of([{ slug: 'portal', title: 'Portal' }]));
    const vazio = of([]);
    const topbar = new Topbar({} as any, {} as any,
      { user$: vazio, sessaoResolvida$: vazio, avatar$: vazio } as any,
      { url: '/', events: new Subject() } as any,
      { searchGames } as any, {} as any, {} as any,
      { list$: vazio, mensagens$: vazio } as any, { detectChanges() {} } as any);
    try {
      topbar.ngOnInit();
      topbar.onSearchInput('erro');
      vi.advanceTimersByTime(250);
      topbar.onSearchInput('portal');
      vi.advanceTimersByTime(250);
      expect(topbar.suggestions[0].slug).toBe('portal');
      topbar.onSearchInput('p');
      vi.advanceTimersByTime(250);
      expect(topbar.suggestions).toEqual([]);
      expect(searchGames).toHaveBeenCalledTimes(2);
    } finally {
      topbar.ngOnDestroy();
      vi.useRealTimers();
    }
  });
});
