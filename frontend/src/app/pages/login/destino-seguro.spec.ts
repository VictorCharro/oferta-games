import { destinoSeguro } from './login';

describe('destinoSeguro (returnUrl do login)', () => {
  it('aceita caminho interno do site', () => {
    expect(destinoSeguro('/jogo/death-stranding')).toBe('/jogo/death-stranding');
    expect(destinoSeguro('/catalogo?sort=discount')).toBe('/catalogo?sort=discount');
  });

  it('recusa destino fora do site (redirecionamento aberto usado em phishing)', () => {
    expect(destinoSeguro('https://golpe.example')).toBe('/');
    expect(destinoSeguro('//golpe.example')).toBe('/');
    expect(destinoSeguro('/\\golpe.example')).toBe('/');
    expect(destinoSeguro('javascript:alert(1)')).toBe('/');
  });

  it('sem valor ou apontando pro próprio login volta pro início', () => {
    expect(destinoSeguro(null)).toBe('/');
    expect(destinoSeguro('')).toBe('/');
    expect(destinoSeguro('/login?returnUrl=/x')).toBe('/');
  });
});
