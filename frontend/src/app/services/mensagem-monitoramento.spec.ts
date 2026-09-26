import { mensagemMeta } from './mensagem-monitoramento';

describe('mensagemMeta', () => {
  it('começar a monitorar sem meta fala da queda de preço', () => {
    expect(mensagemMeta('Hades', false, null)).toBe('Monitorando Hades: você será avisado quando o preço cair.');
  });

  it('começar a monitorar com meta mostra o valor em reais', () => {
    expect(mensagemMeta('Hades', false, 50)).toBe('Monitorando Hades: você será avisado quando chegar a R$\u00a050,00.');
  });

  it('editar a meta de um jogo já monitorado', () => {
    expect(mensagemMeta('Hades', true, 39.9)).toBe('Meta de Hades atualizada para R$\u00a039,90.');
  });

  it('apagar a meta deixa claro que o alerta de queda continua', () => {
    expect(mensagemMeta('Hades', true, null)).toBe('Meta de Hades removida. Você continua sendo avisado quando o preço cair.');
  });
});
