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
 * Sobre o vercel.json usar `routes` em vez de `rewrites`: `rewrites` so e avaliado DEPOIS da
 * verificacao de filesystem, e a Vercel resolve "/" para o index.csr.html do build — medido, a
 * resposta em "/" vinha byte a byte igual a /index.csr.html. E o bug angular/angular-cli#30736.
 * Com `routes` da pra colocar "/" -> "/api" ANTES do `handle: filesystem`, que e a unica forma de
 * a raiz chegar ate aqui. As demais rotas continuam caindo no catch-all depois do filesystem, pra
 * que JS, CSS e imagens sigam vindo da CDN.
 */
export { reqHandler as default } from '../dist/frontend/server/server.mjs';
