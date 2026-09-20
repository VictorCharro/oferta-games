import { TransferState } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { sortearHome } from './sorteio-home';
import { vi } from 'vitest';

describe('sorteio da Home', () => {
  it('hidrata a mesma ordem sem sortear de novo', () => {
    const estado = TestBed.inject(TransferState);
    const jogos = [{ slug: 'a' }, { slug: 'b' }, { slug: 'c' }];
    const servidor = sortearHome(jogos, estado, 'banner', false);
    const random = vi.spyOn(Math, 'random');
    try {
      expect(sortearHome(jogos, estado, 'banner', true)).toEqual(servidor);
      expect(random).not.toHaveBeenCalled();
      sortearHome(jogos, estado, 'banner', true);
      expect(random).toHaveBeenCalled();
    } finally { random.mockRestore(); }
  });
});
