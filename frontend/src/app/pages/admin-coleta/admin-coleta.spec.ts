import { vi } from 'vitest';
import { AdminColeta } from './admin-coleta';
import { StatusAdministrativoColeta, StatusColeta } from '../../services/administracao';

function coleta(parcial: Partial<StatusColeta> = {}): StatusColeta {
  return { tipo: 'x', emExecucao: false, inicioAtual: null, duracaoAtualMs: null, ultimaConclusao: '2026-09-26T10:00:00Z',
    ultimaDuracaoMs: 10000, jogosAtualizados: 0, ofertasAtualizadas: 0, ultimoErro: null, ...parcial };
}

function status(precos: StatusColeta): StatusAdministrativoColeta {
  const c = coleta();
  return { precos, steam: c, detalhes: c, conquistasCatalogo: c, instantGamingEscaneamento: c, instantGamingCasamento: c,
    instantGamingPrecos: c, descoberta: c, ranking: c } as StatusAdministrativoColeta;
}

// Object.create pula o construtor (servicos, DI); inicializa a mao so o que o teste usa.
function painel(): AdminColeta {
  const p = Object.create(AdminColeta.prototype) as AdminColeta;
  Object.assign(p, { recemTerminadas: new Map(), timersTermino: new Map(), destruido: false,
    cdr: { detectChanges: () => {} } });
  return p;
}
const aplicar = (p: AdminColeta, s: StatusAdministrativoColeta) => (p as unknown as { aplicarStatus(s: StatusAdministrativoColeta): void }).aplicarStatus(s);
const cartaoPrecos = (p: AdminColeta) => ({ titulo: 'Preços ITAD', tipo: 'precos' as const, coleta: p.status!.precos });

describe('AdminColeta: coleta que acaba de terminar', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('rodando -> concluida: a barra vai a 100% verde e some depois do destaque', () => {
    const p = painel();
    aplicar(p, status(coleta({ emExecucao: true, duracaoAtualMs: 7000 })));
    expect(p.barraProgresso(cartaoPrecos(p))).toMatchObject({ estado: 'rodando', pct: 70 });

    aplicar(p, status(coleta()));
    expect(p.barraProgresso(cartaoPrecos(p))).toEqual({ pct: 100, estado: 'ok', texto: 'Concluída' });

    vi.advanceTimersByTime(2500);
    expect(p.barraProgresso(cartaoPrecos(p))).toBeNull();
  });

  it('rodando -> com erro: marca falha', () => {
    const p = painel();
    aplicar(p, status(coleta({ emExecucao: true, duracaoAtualMs: 1000 })));
    aplicar(p, status(coleta({ ultimoErro: 'BadSqlGrammarException' })));
    expect(p.barraProgresso(cartaoPrecos(p))?.estado).toBe('erro');
  });

  it('coleta que ja estava parada nao ganha destaque na primeira leitura nem nas seguintes', () => {
    const p = painel();
    aplicar(p, status(coleta()));
    aplicar(p, status(coleta()));
    expect(p.recemTerminadas.size).toBe(0);
  });
});
