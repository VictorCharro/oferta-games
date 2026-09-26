import { MonitoredGameCard } from './monitored-game-card';
import { FavoriteGame } from '../../services/favorites';

// Object.create pula o construtor (FavoritesService, AuthService...): so a logica da barra de meta.
function card(minPrice: number, targetPrice: number | null): MonitoredGameCard {
  const c = Object.create(MonitoredGameCard.prototype) as MonitoredGameCard;
  c.game = { slug: 'x', title: 'X', coverUrl: null, minPrice, targetPrice, favoritedAt: '' } as FavoriteGame;
  return c;
}

describe('MonitoredGameCard: barra de meta', () => {
  it('nunca mostra 100% sem a meta estar atingida (arredondamento)', () => {
    const c = card(50.2, 50); // 99,6% arredondaria pra 100
    expect(c.metaAtingida).toBe(false);
    expect(c.progressoPct).toBe(99);
  });

  it('meta atingida mostra 100% e faixa "atingida"', () => {
    const c = card(45, 50);
    expect(c.progressoPct).toBe(100);
    expect(c.faixaMeta).toBe('atingida');
  });

  it('a partir de 70% do caminho a faixa e "perto"; antes disso, "longe"', () => {
    expect(card(70, 50).faixaMeta).toBe('perto');   // 71%
    expect(card(100, 50).faixaMeta).toBe('longe');  // 50%
  });
});
