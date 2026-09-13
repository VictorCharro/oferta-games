/**
 * Troca uma capa que falhou ao carregar pela imagem padrao (issue #27).
 *
 * As capas vem direto de assets.isthereanydeal.com (hotlink). Se a ITAD mudar a URL, sair do ar ou
 * bloquear hotlink, o card mostrava o icone de imagem quebrada com o texto alternativo por cima. O
 * `no-cover.svg` e local, entao nao falha; a marca `data-fallback` evita laco caso ate ele falhe.
 */
export function trocarPorCapaPadrao(evento: Event) {
  const imagem = evento.target as HTMLImageElement | null;
  if (!imagem || imagem.dataset['fallback'] === '1') return;
  imagem.dataset['fallback'] = '1';
  imagem.src = 'no-cover.svg';
}
