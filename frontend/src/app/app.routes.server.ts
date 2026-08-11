import { RenderMode, ServerRoute } from '@angular/ssr';

// Server (nao Prerender): o catalogo/precos mudam a cada 10min, entao renderizar sob demanda a
// cada request mantem o HTML sempre atual, em vez de precisar de rebuild pra refletir preco novo.
// Client: paginas que dependem de sessao (Supabase Auth guardada no browser) ou que nao tem valor
// de SEO — SSR nao teria a sessao do usuario mesmo, e renderizaria como deslogado antes de
// hidratar, gerando um flash visual sem necessidade.
export const serverRoutes: ServerRoute[] = [
  { path: 'login', renderMode: RenderMode.Client },
  { path: 'perfil', renderMode: RenderMode.Client },
  { path: 'configuracoes', renderMode: RenderMode.Client },
  { path: 'monitorados', renderMode: RenderMode.Client },
  { path: 'favoritos', renderMode: RenderMode.Client },
  { path: 'admin/coleta', renderMode: RenderMode.Client },
  { path: '**', renderMode: RenderMode.Server },
];
