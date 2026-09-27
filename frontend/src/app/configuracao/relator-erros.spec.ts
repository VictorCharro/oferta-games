import { describe, expect, it } from 'vitest';
import { vemDeExtensao } from './relator-erros';

describe('vemDeExtensao', () => {
  // Caso real da aba Erros do admin (26/09/2026).
  it('erro lancado por extensao do Chrome e ignorado', () => {
    const pilha = `TypeError: Cannot read properties of undefined (reading 'M_ID')
    at Y (chrome-extension://eppiocemhmnlbhjplcgkofciiegomcon/executors/200.js:1:761)
    at E (chrome-extension://eppiocemhmnlbhjplcgkofciiegomcon/executors/200.js:1:1442)`;
    expect(vemDeExtensao(pilha)).toBe(true);
  });

  it('extensao do Firefox tambem', () => {
    expect(vemDeExtensao('Error: x\n    at f (moz-extension://abc/content.js:3:9)')).toBe(true);
  });

  // Nosso codigo lancou; a extensao so aparece mais abaixo na pilha: continua sendo bug nosso.
  it('erro do site que passa por uma extensao continua sendo relatado', () => {
    const pilha = `TypeError: x
    at Home.carregar (https://ofertagames.vercel.app/main-ABC.js:1:100)
    at chrome-extension://abc/hook.js:2:5`;
    expect(vemDeExtensao(pilha)).toBe(false);
  });

  it('sem pilha nao descarta', () => {
    expect(vemDeExtensao(undefined)).toBe(false);
    expect(vemDeExtensao('')).toBe(false);
  });
});
