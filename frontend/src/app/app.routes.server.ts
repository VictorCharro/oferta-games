import { RenderMode, ServerRoute } from '@angular/ssr';

// Server (nao Prerender): o catalogo/precos mudam a cada 10min, entao renderizar sob demanda a
// cada request mantem o HTML sempre atual, em vez de precisar de rebuild pra refletir preco novo.
// Client: paginas que dependem de sessao (Supabase Auth guardada no browser) ou que nao tem valor
// de SEO — SSR nao teria a sessao do usuario mesmo, e renderizaria como deslogado antes de
// hidratar, gerando um flash visual sem necessidade.
//
// Cache na CDN da Vercel (issue #22). Antes toda visita era uma invocacao da function + 2 a 6
// chamadas a API na VM Oracle (1 vCPU): a carga no backend crescia 1:1 com as visitas, e o
// cabecalho padrao (max-age=0) nao deixava a CDN guardar nada.
// - s-maxage: quanto tempo a CDN serve sem renderizar de novo. O navegador nao guarda
//   (max-age=0), so a CDN — senao o usuario ficaria preso na copia local.
// - stale-while-revalidate: depois do s-maxage a CDN ainda entrega a copia antiga enquanto
//   renderiza a nova em segundo plano. E o que mantem o site no ar durante deploy/restart da API.
// Seguro porque o HTML do SSR nunca tem dado de usuario logado (a sessao mora no navegador, ver
// AuthService) e porque falha de API sai como 503 (StatusResposta), que a CDN nao guarda.
const LISTAGEM = { 'Cache-Control': 'public, max-age=0, s-maxage=300, stale-while-revalidate=600' };
// Pagina de jogo com janela menor: e o que o usuario recarrega logo depois de "Atualizar precos".
const JOGO = { 'Cache-Control': 'public, max-age=0, s-maxage=60, stale-while-revalidate=300' };

export const serverRoutes: ServerRoute[] = [
  { path: 'login', renderMode: RenderMode.Client },
  { path: 'perfil', renderMode: RenderMode.Client },
  { path: 'perfil/blocos', renderMode: RenderMode.Client },
  { path: 'configuracoes', renderMode: RenderMode.Client },
  { path: 'monitorados', renderMode: RenderMode.Client },
  { path: 'favoritos', renderMode: RenderMode.Client },
  { path: 'admin/coleta', renderMode: RenderMode.Client },
  { path: '', renderMode: RenderMode.Server, headers: LISTAGEM },
  { path: 'catalogo', renderMode: RenderMode.Server, headers: LISTAGEM },
  { path: 'mais-vendidos', renderMode: RenderMode.Server, headers: LISTAGEM },
  { path: 'gratuitos', renderMode: RenderMode.Server, headers: LISTAGEM },
  { path: 'jogo/:slug', renderMode: RenderMode.Server, headers: JOGO },
  // Perfil publico (:handle), busca e o resto: SEM cache na CDN. O perfil muda quando o dono edita
  // e o visitante veria a versao velha por minutos; a busca tem uma URL por termo e nao ganha nada.
  { path: '**', renderMode: RenderMode.Server },
];
