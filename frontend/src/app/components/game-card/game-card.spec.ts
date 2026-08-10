import { GameCard } from './game-card';
import { FavoritesService } from '../../services/favorites';
import { AuthService } from '../../services/auth';
import { Router } from '@angular/router';
import { ChangeDetectorRef } from '@angular/core';
import { GameSummary } from '../../services/game';

// Instancia a classe diretamente (sem TestBed) pra testar a lógica pura de preço/desconto sem
// precisar montar HttpClient/Supabase por trás de FavoritesService/AuthService.
function criarGameCard(): GameCard {
  const favoritesStub = { slugs$: { subscribe: () => ({ unsubscribe: () => {} }) }, isFavorited: () => false } as unknown as FavoritesService;
  const authStub = {} as AuthService;
  const routerStub = {} as Router;
  const cdrStub = {} as ChangeDetectorRef;
  return new GameCard(favoritesStub, authStub, routerStub, cdrStub);
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
});
