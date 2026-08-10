import { GameDetail } from './game-detail';

// Object.create(prototype) evita passar pelo construtor (que precisa de ~9 dependencias via DI);
// so testa a logica pura do rotulo do botao de refresh (cooldown adicionado em 10/08/2026).
function criarInstanciaParcial(refreshing: boolean, cooldownSegundos: number): GameDetail {
  const instancia = Object.create(GameDetail.prototype) as GameDetail;
  instancia.refreshing = refreshing;
  instancia.cooldownSegundos = cooldownSegundos;
  return instancia;
}

describe('GameDetail.rotuloBotaoRefresh', () => {
  it('mostra "Atualizando..." enquanto a requisição está em andamento', () => {
    const gd = criarInstanciaParcial(true, 0);
    expect(gd.rotuloBotaoRefresh).toBe('Atualizando...');
  });

  it('mostra o texto padrão quando não há cooldown nem requisição em andamento', () => {
    const gd = criarInstanciaParcial(false, 0);
    expect(gd.rotuloBotaoRefresh).toBe('Atualizar preços');
  });

  it('mostra a contagem regressiva no formato m:ss durante o cooldown', () => {
    expect(criarInstanciaParcial(false, 300).rotuloBotaoRefresh).toBe('Aguarde 5:00');
    expect(criarInstanciaParcial(false, 65).rotuloBotaoRefresh).toBe('Aguarde 1:05');
    expect(criarInstanciaParcial(false, 9).rotuloBotaoRefresh).toBe('Aguarde 0:09');
  });

  it('prioriza "Atualizando..." mesmo se por algum motivo houver cooldown residual', () => {
    const gd = criarInstanciaParcial(true, 120);
    expect(gd.rotuloBotaoRefresh).toBe('Atualizando...');
  });
});
