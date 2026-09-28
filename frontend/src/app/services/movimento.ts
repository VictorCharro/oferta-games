/**
 * O visitante pediu menos movimento no sistema (no Windows: Configuracoes > Acessibilidade >
 * Efeitos visuais > "Efeitos de animacao" desligado). Boa pratica de acessibilidade (WCAG 2.3.3):
 * quem tem enjoo ou tontura com movimento na tela liga essa opcao.
 *
 * <p>O CSS trata o que e animacao de CSS (ver "Menos movimento" no styles.scss). Isto e pro que o
 * JS move sozinho: autoplay do banner, contagem de numeros, rolagem animada. No servidor devolve
 * false, e o navegador decide de novo ao hidratar.
 */
export function prefereMenosMovimento(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Rolagem suave, ou instantanea pra quem pediu menos movimento. */
export function comportamentoDeRolagem(): ScrollBehavior {
  return prefereMenosMovimento() ? 'auto' : 'smooth';
}
