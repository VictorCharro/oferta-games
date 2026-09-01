/**
 * Entrada da Vercel Function que roda o SSR do Angular.
 *
 * Por que existe: o preset `framework: "angular"` da Vercel nao reconhece a saida do builder do
 * Angular 21 (`@angular/build` com `outputMode: server`) — a Lambda ate era criada, mas o
 * roteamento da edge nunca a invocava e toda rota dava 404 (ver commit ee5c1a4, que reverteu o
 * site pro modo estatico por causa disso). Declarando a function explicitamente aqui, o roteamento
 * deixa de depender da deteccao automatica.
 *
 * `server.mjs` e um bundle autocontido (~818 kB) que so importa builtins do Node — express e o
 * runtime do Angular ja estao dentro dele. O `includeFiles` no vercel.json garante que os
 * assets-chunks (o template de index.html usado na renderizacao) acompanhem a function, porque
 * sao carregados dinamicamente pelo manifest e o tracer nao os enxerga.
 *
 * O handler exportado e o app Express de src/server.ts. Ele tambem serve estatico de ../browser,
 * mas na Vercel esses arquivos sao servidos pela CDN antes da requisicao chegar aqui.
 *
 * Sobre o `rm index.csr.html` no buildCommand: a Vercel resolve "/" para esse arquivo (medido — a
 * resposta em "/" vinha byte a byte igual a /index.csr.html), e como a verificacao de filesystem
 * roda ANTES dos rewrites, nenhum rewrite alcanca a raiz enquanto ele existir. E o bug
 * angular/angular-cli#30736. Apagar do output estatico e seguro porque a function nao usa esse
 * arquivo: ela tem a propria copia embutida em server/assets-chunks/index_csr_html.mjs, que e o
 * que continua servindo as rotas em RenderMode.Client (login, perfil, configuracoes, monitorados,
 * favoritos, admin/coleta).
 *
 * Tentativa descartada: trocar `rewrites` por `routes` com "/" antes do `handle: filesystem`
 * resolvia a raiz, mas `routes` assume a tabela de roteamento inteira e quebrava o fluxo de
 * autenticacao dos deploys de preview (todo request virava 302 pro SSO, sem nunca chegar na
 * function) — o que impedia justamente validar a mudanca antes de produção.
 */
export { reqHandler as default } from '../dist/frontend/server/server.mjs';
