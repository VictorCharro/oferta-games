import { GameCard } from './game-card';
import { FavoritesService } from '../../services/favorites';
import { AuthService } from '../../services/auth';
import { Router } from '@angular/router';
import { ChangeDetectorRef } from '@angular/core';
import { GameSummary } from '../../services/game';
import { AvisosService } from '../../services/avisos';
import { vi } from 'vitest';

// Instancia a classe diretamente (sem TestBed) pra testar a lógica pura de preço/desconto sem
// precisar montar HttpClient/Supabase por trás de FavoritesService/AuthService.
function criarGameCard(): GameCard {
  const favoritesStub = { slugs$: { subscribe: () => ({ unsubscribe: () => {} }) }, isFavorited: () => false } as unknown as FavoritesService;
  const authStub = {} as AuthService;
  const routerStub = {} as Router;
  const cdrStub = {} as ChangeDetectorRef;
  return new GameCard(favoritesStub, authStub, routerStub, cdrStub, {} as AvisosService);
}

function jogo(sobrescreve: Partial<GameSummary> = {}): GameSummary {
  return {
    slug: 'jogo-teste',
    title: 'Jogo Teste',
    coverUrl: null,
    minPrice: 50,
    regularPrice: 100,
    ...sobrescreve,
  };
}

describe('GameCard', () => {
  it('calcula o desconto a partir de minPrice/regularPrice quando discountPct não é passado', () => {
    const card = criarGameCard();
    card.game = jogo({ minPrice: 50, regularPrice: 100 });
    expect(card.discount).toBe(50);
  });

  it('usa discountPct diretamente quando fornecido, ignorando os preços', () => {
    const card = criarGameCard();
    card.game = jogo({ minPrice: 50, regularPrice: 100 });
    card.discountPct = 30;
    expect(card.discount).toBe(30);
  });

  it('retorna 0 de desconto quando não há preço regular ou preço regular menor/igual ao atual', () => {
    const card = criarGameCard();
    card.game = jogo({ minPrice: 50, regularPrice: null });
    expect(card.discount).toBe(0);

    card.game = jogo({ minPrice: 100, regularPrice: 100 });
    expect(card.discount).toBe(0);
  });

  it('formata preço em BRL e trata nulo como travessão', () => {
    const card = criarGameCard();
    expect(card.formatPrice(49.9)).toContain('49,90');
    expect(card.formatPrice(null)).toBe('—');
  });

  it('identifica DLC pela flag isDlc do jogo', () => {
    const card = criarGameCard();
    card.game = jogo({ title: 'Baldur\'s Gate 3', isDlc: true });
    expect(card.isDlcGame).toBe(true);
  });

  describe('botão de monitorar', () => {
    function montar(monitorando: boolean, toggle: () => Promise<void>) {
      const avisos = { sucesso: vi.fn(), info: vi.fn(), erro: vi.fn() };
      const favoritos = { isFavorited: () => monitorando, toggle: vi.fn(toggle) } as unknown as FavoritesService;
      const card = new GameCard(favoritos, { isLoggedIn: true } as AuthService, {} as Router,
        { markForCheck: () => {} } as unknown as ChangeDetectorRef, avisos as unknown as AvisosService);
      card.game = jogo({ title: 'Hades' });
      return { card, avisos };
    }
    const clique = { preventDefault: () => {}, stopPropagation: () => {} } as unknown as Event;

    it('ao monitorar, avisa o benefício e dispara o pop de confirmação', async () => {
      const { card, avisos } = montar(false, () => Promise.resolve());
      await card.toggleMonitoring(clique);
      expect(avisos.sucesso).toHaveBeenCalledWith(expect.stringContaining('Monitorando Hades'));
      expect(card.confirmado).toBe(true);
      expect(card.salvandoMonitoramento).toBe(false);
    });

    it('ao deixar de monitorar, avisa como informação, não como sucesso', async () => {
      const { card, avisos } = montar(true, () => Promise.resolve());
      await card.toggleMonitoring(clique);
      expect(avisos.info).toHaveBeenCalledWith('Hades não está mais sendo monitorado.');
      expect(avisos.sucesso).not.toHaveBeenCalled();
    });

    it('falha do servidor vira aviso de erro e libera o botão', async () => {
      const { card, avisos } = montar(false, () => Promise.reject(new Error('500')));
      await card.toggleMonitoring(clique);
      expect(avisos.erro).toHaveBeenCalled();
      expect(card.confirmado).toBe(false);
      expect(card.salvandoMonitoramento).toBe(false);
    });
  });
});
