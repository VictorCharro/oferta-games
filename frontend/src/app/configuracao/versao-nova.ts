/**
 * Site aberto de antes de um deploy (01/10/2026). Os arquivos `chunk-XXXX.js` mudam de nome a cada
 * build e a Vercel so serve os da versao atual. Quem estava com a aba aberta e navegava pra uma
 * pagina ainda nao carregada (rota com loadComponent) pedia um chunk que nao existe mais:
 * "Failed to fetch dynamically imported module", e a navegacao simplesmente nao acontecia.
 *
 * <p>O conserto e recarregar a pagina inteira ja no destino: o index.html novo traz os chunks novos.
 * O App chama recarregarNaVersaoNova no NavigationError; o RelatorErros nao manda esse erro pra aba
 * Erros, a nao ser que ele volte DEPOIS da recarga (ai e problema de verdade, ex.: deploy quebrado).
 */

// Mensagem de cada navegador pra import dinamico que falhou (Chrome/Edge, Safari, Firefox) e a do
// webpack, por garantia.
const PADROES = [
  /Failed to fetch dynamically imported module/i,
  /Importing a module script failed/i,
  /error loading dynamically imported module/i,
  /Loading chunk [\w-]+ failed/i,
  /ChunkLoadError/,
];

const CHAVE = 'oferta-games-recarga-versao-nova';
/** Duas recargas por chunk velho em menos que isto = nao e chunk velho; para de tentar. */
const INTERVALO_MINIMO_MS = 30_000;

export function ehChunkDesatualizado(erro: unknown): boolean {
  const mensagem = erro instanceof Error ? `${erro.name}: ${erro.message}` : String(erro ?? '');
  return PADROES.some(padrao => padrao.test(mensagem));
}

/**
 * Recarrega a pagina em `destino` pra pegar a versao nova. Devolve false (e nao recarrega) se ja
 * recarregou por isso ha menos de 30s, pra um chunk que falta de verdade nao virar loop infinito
 * de recarga. Com sessionStorage bloqueado tambem nao recarrega: sem a trava, o loop seria possivel.
 */
export function recarregarNaVersaoNova(destino: string): boolean {
  try {
    const ultima = Number(sessionStorage.getItem(CHAVE) || 0);
    if (Date.now() - ultima < INTERVALO_MINIMO_MS) return false;
    sessionStorage.setItem(CHAVE, String(Date.now()));
  } catch {
    return false;
  }
  location.assign(destino);
  return true;
}

/**
 * A pagina atual nasceu de uma recarga por chunk velho, feita ha menos de 30s. Se o erro aparece de
 * novo agora, recarregar nao resolveu e ele precisa ir pra aba Erros. Compara com o inicio desta
 * carga (performance.timeOrigin): a marca gravada AGORA por esta pagina, a caminho da recarga, nao conta.
 */
export function recarregouHaPouco(): boolean {
  try {
    const ultima = Number(sessionStorage.getItem(CHAVE) || 0);
    return ultima > 0 && ultima < performance.timeOrigin && Date.now() - ultima < INTERVALO_MINIMO_MS;
  } catch {
    return false;
  }
}
