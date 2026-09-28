import { describe, expect, it } from 'vitest';
import { ContarNumero } from './contar-numero';

/** Monta a diretiva sem injecao (padrao do projeto: Object.create pula os inject() dos campos). */
function criar(opcoes: { noNavegador: boolean; jaPintou: boolean }) {
  const el = document.createElement('strong');
  const d = Object.create(ContarNumero.prototype) as ContarNumero;
  Object.assign(d, { el, noNavegador: opcoes.noNavegador, pintura: { jaPintou: opcoes.jaPintou }, jaApareceu: false, sufixo: '' });
  return { d, el };
}

describe('ContarNumero', () => {
  it('sem valor mostra "--"', () => {
    const { d, el } = criar({ noNavegador: true, jaPintou: true });
    d.valor = null;
    d.ngOnChanges();
    expect(el.textContent).toBe('--');
  });

  // Perfil que chegou pronto do SSR: o numero ja esta na tela e nao pode zerar pra contar.
  it('antes da primeira pintura escreve o valor final direto, em pt-BR e com sufixo', () => {
    const { d, el } = criar({ noNavegador: true, jaPintou: false });
    d.valor = 1234;
    d.sufixo = 'h';
    d.ngOnChanges();
    expect(el.textContent).toBe('1.234h');
  });

  it('com menos movimento pedido no sistema escreve o valor final, sem contar', () => {
    const original = globalThis.matchMedia;
    globalThis.matchMedia = ((q: string) => ({ matches: q.includes('reduce') })) as unknown as typeof matchMedia;
    try {
      const { d, el } = criar({ noNavegador: true, jaPintou: true });
      d.valor = 57;
      d.ngOnChanges();
      expect(el.textContent).toBe('57');
    } finally {
      globalThis.matchMedia = original;
    }
  });

  it('no servidor escreve o valor final', () => {
    const { d, el } = criar({ noNavegador: false, jaPintou: false });
    d.valor = 42;
    d.ngOnChanges();
    expect(el.textContent).toBe('42');
  });
});
