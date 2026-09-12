# Oferta Games - Contexto do Projeto

> Este arquivo e a fonte de verdade do projeto. Atualize-o sempre que uma decisao de arquitetura, schema, deploy, contrato de API ou fluxo relevante mudar. Nao registre segredos, tokens ou senhas aqui.

## Produto

O Oferta Games e um comparador de precos de jogos. Ele nao vende jogos: coleta ofertas de lojas parceiras por meio da API IsThereAnyDeal (ITAD), mostra o menor preco em BRL e encaminha o usuario para a loja.

O produto tambem tem contas, Jogos Monitorados para alertas de preco, perfis publicos personalizaveis e conexao Steam para biblioteca, horas e conquistas.

## Arquitetura Atual

| Camada | Tecnologia | Hospedagem atual |
|---|---|---|
| Frontend | Angular 21 + TypeScript | Vercel |
| Backend | Java 21 + Spring Boot 3 | Oracle Always Free via Docker |
| Banco de catalogo | PostgreSQL 17 (self-hosted) | Container Docker na propria VM Oracle |
| Banco de conta/perfil + Auth + Storage | PostgreSQL + Supabase Auth + Storage | Supabase |
| Precos | ITAD API | Consumida pelo backend |
| Perfil gamer | Steam OpenID + Steam Web API | Consumida pelo backend |

URLs de producao atuais:

- Frontend: `https://ofertagames.vercel.app`
- Backend Oracle temporario: `https://api.163.176.220.243.sslip.io`
- Health check Oracle: `https://api.163.176.220.243.sslip.io/actuator/health`

**Dois bancos desde 10/09/2026** (ver "Migracao do catalogo pra fora do Supabase" em "Deploy e Operacao"): `DATABASE_URL` continua Supabase (conta, perfis, favoritos, Auth) e usa o transaction pooler dele — a conversao pra JDBC e feita pelo backend, com `prepareThreshold=0`, pois prepared statements persistentes nao sao compativeis com esse modo do Supavisor. `CATALOG_DATABASE_URL` e o Postgres novo, self-hosted na VM (games/game_details/game_achievements/offers/price_history/instant_gaming_catalog) — conexao direta, sem pooler, `sslmode=disable` (rede interna do Docker compose, sem TLS configurado no container).

## Deploy e Operacao

O `Dockerfile` faz o build Maven em imagem Java 21 e inicia o JAR com limite de heap `-Xmx384m`. O Render esta desligado e nao deve receber novos deploys. Nao existe workflow de sincronizacao recorrente no GitHub Actions.

### Variaveis do backend

| Variavel | Obrigatoria | Uso |
|---|---:|---|
| `DATABASE_URL` | sim | PostgreSQL Supabase pelo pooler (conta, perfis, favoritos) |
| `CATALOG_DATABASE_URL` | sim | PostgreSQL self-hosted na VM (catalogo) — `?sslmode=disable`, sem pooler |
| `CATALOGO_DB_PASSWORD` | sim | Senha do usuario `catalogo` no Postgres self-hosted; usada tambem pelo `compose.yml` pra subir o container |
| `SUPABASE_URL` | sim | Validacao de tokens Supabase |
| `SUPABASE_ANON_KEY` | sim | Validacao de tokens Supabase |
| `ITAD_API_KEY` | sim | Coleta e refresh de ofertas |
| `STEAM_WEB_API_KEY` | sim para Steam | Biblioteca, horas e conquistas Steam |
| `XBL_APP_KEY` | sim para Xbox | "Public Key" do "Xbox App" criado no painel OpenXBL (xbl.io), usado no fluxo OAuth de login Xbox |
| `PUBLIC_BACKEND_URL` | sim para Steam | URL publica do backend para retorno OpenID |
| `FRONTEND_URL` | sim | URL do frontend para redirecionamentos (Steam) e pra montar as URLs do `sitemap.xml` (`app.frontend-url`) |
| `CORS_ALLOWED_ORIGINS` | sim | Separar por virgula; incluir Vercel e `http://localhost:4200` |
| `APP_SYNC_SCHEDULER_ENABLED` | sim | `true` em producao |
| `APP_SYNC_SCHEDULER_PRICE_DELAY_MS` | nao | Padrao `600000` (10 min) |
| `APP_SYNC_SCHEDULER_STEAM_DELAY_MS` | nao | Padrao `900000` (15 min) |
| `SYNC_SECRET_KEY` | sim para endpoint legado | Protege `POST /api/sync` |
| `PORT` | nao | Fornecida pelo Render; padrao `8080` |

Nunca colocar essas variaveis no Git ou em arquivos do frontend.

### Oracle

A VM Oracle Always Free em Sao Paulo foi criada com Ubuntu 24.04 ARM, `1 OCPU` e `6 GB`. Ela atende a producao. O compose da VM fica em `deploy/oracle/compose.yml`, com o arquivo secreto `deploy/oracle/.env` criado somente no servidor. Ele inclui as chaves de banco/Supabase/ITAD/Steam, CORS, `FRONTEND_URL`, `API_DOMAIN` e `PUBLIC_BACKEND_URL`.

O Caddy e executado no mesmo compose e entrega HTTPS na frente do backend, que nao expoe mais a porta 8080 fora da rede Docker. Enquanto nao houver dominio proprio, pode ser usado temporariamente `api.163.176.220.243.sslip.io`; ele aponta para o IP publico da VM. O frontend so deve trocar do Render para a Oracle depois que `https://<API_DOMAIN>/actuator/health` responder `UP`.

O workflow `.github/workflows/deploy-oracle.yml` atualiza o backend na VM por SSH em cada push relevante para `master`; exige os segredos `ORACLE_HOST` e `ORACLE_SSH_PRIVATE_KEY_B64` no GitHub. **Deploy automatico funcionando (06/08/2026)**: o bloqueio de billing (resolvido em ~31/07) foi seguido por outra falha (`Load key "/home/runner/.ssh/id_oracle": error in libcrypto` / `Permission denied (publickey)`) mesmo com uma chave nova gerada e testada via SSH direto — o problema era o secret `ORACLE_SSH_PRIVATE_KEY` (texto puro) se corrompendo na ida/volta pelo GitHub (provavel questao de quebra de linha). Corrigido gerando um par de chaves novo, adicionando a publica em `~/.ssh/authorized_keys` da VM, e guardando a privada em base64 no secret `ORACLE_SSH_PRIVATE_KEY_B64` — o workflow decodifica com `base64 -d` antes de usar. O secret antigo (`ORACLE_SSH_PRIVATE_KEY`) foi removido. O deploy manual continua disponivel como alternativa:

```powershell
.\scripts\deploy-oracle.ps1 -ChaveSsh "C:\Users\VFulls\Downloads\oferta-games.key"
```

O script conecta na VM, atualiza o `master`, recria somente o container do backend e valida o health check. A chave da VM usada para baixar o repositorio continua somente leitura.

### Migracao do catalogo pra fora do Supabase (10/09/2026)

O banco Supabase free (cota de 500MB) bateu 89% de uso (442MB) mesmo depois da limpeza de 01/09/2026 — as tabelas de catalogo (`games`/`game_details`/`game_achievements`/`offers`/`price_history`/`instant_gaming_catalog`) sao 90%+ do banco e crescem organicamente pelos jobs de sincronizacao (nao e bug, e o catalogo funcionando). Como nao guardam dado de usuario (sem FK com `auth.users`), foram movidas pra um Postgres 17 self-hosted, container `catalogo-db` no mesmo `compose.yml` do backend, na VM Oracle — sem cota, sem porta exposta pro host (so acessivel pela rede interna do Docker compose). Banco caiu pra 15MB no Supabase.

**Auth e tudo ligado a usuario continuam 100% no Supabase** (favoritos, perfis, avaliacoes, notificacoes, conexoes Steam/Xbox) — inclusive o cascade delete de `auth.users` pra esses dados, que so funciona porque estao no mesmo Postgres que o Auth. Ver "Schema Relevante" abaixo pra saber qual tabela mora em qual banco.

`ConfiguracaoBancoDados` sobe dois `DataSource`/`JdbcClient`/`JdbcTemplate`/`PlatformTransactionManager` — o do Supabase e `@Primary` (usado por quem nao pede qualifier), o do catalogo e qualificado `"catalogo"`. Sete repositorios so usam catalogo (`RepositorioJogos`, `RepositorioDescontos`, boa parte de `RepositorioInstantGaming`); cinco fazem JOIN entre tabela de usuario e tabela de catalogo e foram reescritos pra buscar nos dois bancos e juntar em Java (`RepositorioFavoritos`, `RepositorioFavoritosPerfil`, `RepositorioAtividadesPerfil`, `RepositorioColecoesPerfil`, `RepositorioNotificacoes`) — perderam a garantia de integridade referencial entre as duas pontas (ex: nada impede um `favorites.game_id` apontar pra um jogo que nao existe mais no catalogo), mas o app nunca deleta jogos do catalogo (so upsert), entao isso e teorico ate hoje.

**Backup**: o Supabase faz backup sozinho; o Postgres novo nao. `deploy/oracle/backup-catalogo.sh` roda via cron diario (ver `deploy/oracle/README-catalogo-db.md` pra configurar e pra restaurar do zero) — sem isso, um `catalogo-db` novo sobe saudavel mas **vazio**, e toda query de catalogo quebra com "relation games does not exist" sem aviso nenhum antes disso.

**Cinco bugs reais surgiram nas primeiras horas em producao** (nenhum deles apareceu na verificacao manual antes do deploy — so sob trafego/jobs reais):

1. Declarar so o `JdbcClient`/`PlatformTransactionManager` do catalogo (com `@Qualifier`, sem `@Primary`) fez a autoconfiguracao do Spring Boot desistir de criar o bean default do Supabase (`@ConditionalOnMissingBean`) — o unico `JdbcClient` restante no contexto (o do catalogo) foi injetado em todo mundo sem qualifier, inclusive `EstadoColeta` (tentou rodar `UPDATE coleta_status`, tabela que ficou no Supabase, contra o catalogo). Fix: declarar os dois pares explicitamente, nunca depender da autoconfiguracao condicional quando ha mais de um `DataSource`.
2. `pg_restore -t <tabela>` copiou tabela+dados mas nao as sequences de auto-incremento (`games_id_seq`, `offers_id_seq`, `price_history_id_seq`, `game_achievements_id_seq`) nem os `DEFAULT nextval(...)` das colunas `id` — invisivel ate o primeiro INSERT sem id explicito (a sincronizacao de precos agendada), que travava com "null value in column id" e derrubava o backend em loop. Fix: recriar as 4 sequences a partir do `MAX(id)` atual de cada tabela.
3. `catalogo-db` usava o `shm_size` padrao do Docker (64MB) — insuficiente pra queries com hash/sort grande (`/api/deals/top`, `DISTINCT ON` + `LATERAL` no catalogo inteiro) sob varias requisicoes concorrentes, chegando a "could not resize shared memory segment: No space left on device" quando o trafego voltou de uma vez apos o site sair do ar. Fix: `shm_size: '256mb'` no `compose.yml`.
4. `RepositorioInstantGaming` foi movido inteiro pro datasource do catalogo, mas `instant_gaming_scan_cursor` (cursor da varredura) nunca fez parte da migracao — ficou no Supabase. So `buscarUltimoIdEscaneado`/`avancarCursor` usam `jdbc` (Supabase); o resto da classe usa `jdbcCatalogo`.
5. Pool do catalogo com `maximumPoolSize=5` (numero herdado do dimensionamento pro Supabase free/pooler) esgotou sob trafego real (varias queries da Home concorrentes + o job de sincronizacao brigando pelas mesmas 5 conexoes) — `SQLTransientConnectionException: Connection is not available`. Sem teto de plano nesse Postgres, subido pra 20; `leakDetectionThreshold=20000` ligado nos dois pools pra qualquer vazamento futuro logar a stack trace de quem segurou a conexao.

**Limitacao de capacidade conhecida, nao um bug**: a VM Oracle tem so **1 vCPU**. Testar com ~30 requisicoes pesadas simultaneas (fora do padrao real de trafego) derruba o unico nucleo (load average > 8) e faz ate leituras simples darem timeout por alguns minutos, ate a fila esvaziar sozinha. Se o trafego real crescer, isso pode virar gargalo genuino — nao ha acao tomada por enquanto, sem gatilho real ainda.

### Testes e CI (10/08/2026)

Ate 10/08/2026 o projeto nao tinha testes de verdade: o backend nao tinha `src/test` nenhum, e o frontend so tinha o `app.spec.ts` padrao do `ng generate`, nunca adaptado (chegava a checar um texto — "Hello, frontend" — que nem existe mais no app, e quebrava com `NG0304` por `app-sidebar` nao estar declarado no modulo de teste).

- **Backend** (`backend-java/src/test/java`, JUnit 5 + Mockito via `spring-boot-starter-test`, sem contexto Spring pra ficar rapido):
  - `comum/ClassificadorDlcTest`, `ConteudosNaoJogosTest`, `JogosBloqueadosTest`, `LojasBloqueadasTest`, `GeradorSlugTest`: cobrem as classes de classificacao/filtro/slug usadas em quase toda query do catalogo — sao puras (sem I/O), risco alto de regressao silenciosa se alguem mexer no regex sem perceber.
  - `jogos/ServicoCatalogoTest`: cobre `atualizarPrecos` com `RepositorioJogos`/`ClienteItad`/`ServicoInstantGaming` mockados — cooldown de 5min (bloqueia e libera), cascata de preco pras DLCs (soma corretamente, DLC sem preco nao derruba o jogo principal, cooldown so registra no jogo principal), e `JogoSemItadException` quando nao ha nenhuma fonte de preco. Essa e a logica mais nova e mais arriscada do backend (adicionada nesta mesma sessao), entao ganhou o teste mais pesado.
  - Rodar localmente: `cd backend-java && mvn test` (ou `mvn -o test` se os plugins do Maven ja estiverem em cache local, sem precisar de rede).
- **Frontend** (`*.spec.ts` ao lado de cada arquivo, Vitest + jsdom via `@angular/build:unit-test` — **nao e Karma/Jasmine**, entao nao precisa de Chrome/browser real nem em CI):
  - `services/filters.spec.ts`, `services/store-brand.spec.ts`: funcoes puras de classificacao de DLC e de marca/plataforma de loja, usadas em varias paginas (catalogo, home, cards).
  - `components/game-card/game-card.spec.ts`: calculo de desconto e formatacao de preco, instanciando a classe direto (sem `TestBed`) com stubs dos servicos injetados — evita ter que montar `HttpClient`/Supabase por tras de `FavoritesService`/`AuthService` so pra testar um calculo.
  - `pages/game-detail/game-detail.spec.ts`: rotulo do botao de refresh durante o cooldown (`Object.create(GameDetail.prototype)` pra pular o construtor, que tem ~9 dependencias via DI, e testar so o getter `rotuloBotaoRefresh`).
  - `app.spec.ts`: reescrito pra testar de verdade o comportamento de `App` (esconder sidebar/topbar na rota `/login`), com `NO_ERRORS_SCHEMA` pra nao precisar declarar `app-sidebar`/`app-topbar` no modulo de teste.
  - Rodar localmente: `cd frontend && npm test -- --watch=false`.
- **CI** (`.github/workflows/ci.yml`, novo, separado do `deploy-oracle.yml`): roda em todo push (qualquer branch) e em pull requests pra `master`. Dois jobs paralelos e independentes — `backend` (`mvn -B test` com JDK 21 via `actions/setup-java`) e `frontend` (`npm ci` + `npm test -- --watch=false` + `npm run build`, com Node 22 via `actions/setup-node`).
- **Deploy so depois da CI passar (10/08/2026)**: `deploy-oracle.yml` nao dispara mais direto por `push`; agora usa `workflow_run` acionado pela conclusao do workflow `CI` em `master`, com `if: github.event.workflow_run.conclusion == 'success'` — se a CI falhar, o job de deploy nem roda. `workflow_dispatch` continua disponivel pra deploy manual explicito, sem depender da CI. Sempre deploya quando o job roda (sem tentar adivinhar "mudou backend?"): uma primeira versao usava `git diff HEAD^ HEAD` pra pular deploys "irrelevantes", mas isso comparava so o ultimo commit — um push com mudanca real de backend cuja CI falhou, seguido de um push so corrigindo a CI (sem tocar `backend-java/`), fazia o diff do segundo commit sozinho dar "nao relevante" e o deploy real ficava parado sem ninguem notar. `git pull --ff-only` na VM ja e barato/idempotente quando nao ha nada novo, entao sempre deployar nao tem custo real.

### SEO (SSR), sitemap e headers de seguranca (10/08/2026)

O site era 100% CSR (client-side rendering): o servidor mandava um HTML quase vazio e o JS montava tudo depois, entao toda pagina indexava com o mesmo `<title>`/meta generico do `index.html`, sem nome/preco do jogo especifico, e links compartilhados (WhatsApp/Discord/Twitter) nao mostravam preview nenhum. Convertido pra SSR de verdade (nao so prerender estatico, ja que preco muda a cada 10min e prerender geraria HTML desatualizado sem um rebuild):

- **`@angular/ssr`** adicionado via `ng add` (teve que instalar `@angular/ssr`/`@angular/platform-server` manualmente com a versao exata ja usada pelo resto do monorepo — `ng add` tentava puxar a versao `latest`, que ficava um patch a frente e quebrava a resolucao de peer deps do npm). Gerou `src/server.ts` (entrypoint Express), `src/main.server.ts`, `src/app/app.module.server.ts`.
- **Render mode por rota** (`src/app/app.routes.server.ts`): a maioria das rotas usa `RenderMode.Server` (renderiza a cada request, nunca fica desatualizado). As paginas que dependem de sessao guardada no browser (Supabase Auth) ou nao tem valor de SEO — `login`, `perfil`, `configuracoes`, `monitorados`, `favoritos`, `admin/coleta` — usam `RenderMode.Client` (SSR nao teria a sessao do usuario mesmo, e renderizar como deslogado antes de hidratar geraria um flash visual sem necessidade).
- **`provideHttpClient(withFetch())`** substituiu `HttpClientModule` em `app-module.ts`: o backend XHR classico do Angular nao existe em Node: precisa do backend baseado em `fetch` (nativo no Node 18+) pra funcionar igual no browser e no servidor.
- **Bugs de SSR encontrados e corrigidos** (globals de browser que nao existem em Node — cada um derrubava a renderizacao inteira com `ReferenceError`, sem fallback silencioso): `services/supabase.ts` (o client do Supabase tentava usar `localStorage` na propria construcao — corrigido com `persistSession: false` quando `typeof window === 'undefined'`), `services/theme.ts` e `services/preferences.ts` (leem `localStorage`/`document` no construtor do servico, guardado atras de `typeof window !== 'undefined'`), `components/deals-carousel/deals-carousel.ts` (`new MutationObserver(...)` em `ngAfterViewInit`, guardado com `typeof MutationObserver === 'undefined'`), `pages/catalog/catalog.ts` (`checkLoadMore` lia `window.innerHeight`/`document.documentElement.scrollHeight` sem guarda, chamado via `setTimeout` incondicional). Regra geral daqui pra frente: qualquer servico/componente que rode durante o carregamento inicial (constructor, `ngOnInit`, `ngAfterViewInit`, ou qualquer coisa agendada sem esperar interacao do usuario) **nao pode** tocar `window`/`localStorage`/`sessionStorage`/`navigator`/`MutationObserver`/`ResizeObserver` sem checar `typeof window !== 'undefined'` antes — `document` e seguro (o Angular fornece um DOM de servidor pra ele).
- **`security.allowedHosts`** em `angular.json` (protecao SSRF nativa do `@angular/ssr` contra header `Host` forjado) precisou ser preenchido — vinha vazio (`[]`) do schematic, o que rejeitava **toda** requisicao com 400, inclusive em producao; setado pra `["ofertagames.vercel.app", "*.vercel.app", "localhost"]`.
- **`services/seo.ts`** (`SeoService`): wrapper de `Title`/`Meta` do Angular (funcionam identico no server e no browser) com `set({title, description, image, path})` e `reset()`. Monta title com sufixo "| Oferta Games", meta description, e tags Open Graph/Twitter (pra preview de link em redes sociais). Chamado em `ngOnInit`/no callback de carregamento de: `game-detail.ts` (titulo = nome do jogo, descricao com o menor preco, imagem = capa do jogo), `public-profile.ts` (titulo = nome de exibicao, descricao = bio, imagem = avatar), `catalog.ts`/`best-sellers.ts`/`free-games.ts`/`search.ts`/`not-found.ts` (titulo/descricao fixos por pagina), `home.ts` (`reset()`, usa o generico). `game-detail.ts` e `public-profile.ts` tambem chamam `reset()` no `ngOnDestroy` pra o titulo anterior nao "vazar" pra uma pagina que nao define o proprio.
- **Deploy do SSR na Vercel** (10/08/2026, revisto e corrigido em 01/09/2026). Historico completo porque cada tentativa falhou de um jeito diferente e silencioso:

  - **Tentativa 1 — remover o `vercel.json`** e deixar a deteccao automatica assumir: **404 em toda rota**, ate a Home. O projeto tem Framework Preset em branco (`framework: null`, confirmado via API), entao sem hint o `vercel build` tratou a saida como estatica generica.
  - **Tentativa 2 — `{ "framework": "angular" }`**: a Lambda era criada (`type: LAMBDAS` confirmado via API) mas o roteamento da edge **nunca a invocava** — 404 em toda rota, sem nenhum log de runtime. O preset da Vercel nao reconhece a saida do `@angular/build` com `outputMode: server`.
  - **Rollback (commit `ee5c1a4`)**: com o site quebrado no ar, voltou pro `vercel.json` estatico com rewrite `/(.*) -> /index.csr.html`. Correto como decisao emergencial, mas ficou assim por 3 semanas: **o SSR nunca rodou em producao nesse periodo** — toda rota servia a mesma casca CSR de 10 kB, com `<title>` generico e sem Open Graph. Este documento afirmava o contrario ate 01/09/2026.
  - **Solucao atual (01/09/2026)** — nao depender da deteccao automatica. Tres correcoes, cada uma so visivel depois de resolver a anterior:
    1. **Function declarada explicitamente** em `frontend/api/index.mjs`, reexportando o `reqHandler` de `src/server.ts`. `server.mjs` e um bundle autocontido (~818 kB, so importa builtins do Node), entao o `includeFiles: dist/frontend/server/**` do `vercel.json` basta — precisa incluir os `assets-chunks`, carregados dinamicamente pelo manifest e invisiveis pro tracer.
    2. **`trustProxyHeaders: true`** no `AngularNodeAppEngine` (`src/server.ts`). Sem isso o engine ignora os `X-Forwarded-*` da edge, nao monta a URL real da requisicao e **devolve a casca CSR com HTTP 200** — falha silenciosa, cujo unico sinal e o warning `Received "x-forwarded-for" header but "trustProxyHeaders" was not set up to allow it` no log da function. Mesma familia de armadilha do `allowedHosts` vazio: protecao de SSRF que falha fechado sem erro visivel.
    3. **`rm dist/frontend/browser/index.csr.html`** no `buildCommand`. A Vercel resolve `/` para esse arquivo e a **verificacao de filesystem roda antes dos rewrites**, entao nenhum rewrite alcanca a raiz enquanto ele existir (bug conhecido: [angular/angular-cli#30736](https://github.com/angular/angular-cli/issues/30736)). Apagar do output estatico e seguro porque a function tem a propria copia em `server/assets-chunks/index_csr_html.mjs`, que continua servindo as rotas em `RenderMode.Client`.

  **Descartado:** trocar `rewrites` por `routes` com `/` antes do `handle: filesystem` resolve a raiz, mas `routes` assume a tabela de roteamento inteira e quebra a autenticacao dos deploys de preview (todo request vira 302 pro SSO), impedindo validar mudanca antes de producao.

  **Como verificar que o SSR esta mesmo ativo** (o modo quebrado responde HTTP 200, entao status code nao serve): `curl -s https://ofertagames.vercel.app/jogo/hollow-knight | grep '<title>'` tem que trazer o nome do jogo, nao "Oferta Games"; e a resposta passa de 100 kB em vez de 10 kB. Nos headers, resposta vinda da function tem `Content-Type: text/html;charset=UTF-8` (sem espaco) e traz `X-Content-Type-Options: nosniff`; a estatica vem como `text/html; charset=utf-8` e sem os headers de seguranca do Express.

- **O que o servidor nao pode saber, e as tres piscadas que isso causou** (01/09/2026). Tema, sessao e lista de monitorados sao estado do browser do visitante. O servidor renderiza sem eles, a pagina pinta, e so depois a correcao chega — antes do SSR a tela ficava em branco nesse intervalo, entao nada disso aparecia. Regra geral: **se a informacao so existe no browser, o HTML do servidor nao pode se comprometer com nenhuma das respostas.**

  - **Tema**: script inline e sincrono no `<head>` do `index.html` aplica o `data-theme` do `localStorage` antes do primeiro paint. **`inlineCritical` fica desligado no `angular.json` por causa disto** — nao e preferencia de performance, e requisito de correcao: o beasties monta o CSS critico a partir do HTML renderizado, e como o servidor nao sabe o tema, o HTML sai sem `data-theme` e as regras `[data-theme="light"]` nunca entram no corte, enquanto o `:root` escuro entra. Com a folha completa adiada (`media="print"`), quem usa tema claro via a pagina pintar escura e so depois virar clara. **Religar `inlineCritical` traz a piscada de volta** — e nao ha ajuste que resolva, porque o corte e decidido a partir de um HTML que por construcao desconhece a preferencia de quem le.
  - **Sessao**: `AuthService.sessaoResolvida` distingue "deslogado" de "ainda nao sei" (`isLoggedIn` sozinho nao distingue: `_user` comeca `null` e o `getSession()` e assincrono). Ela **fica `false` para sempre no servidor**, mesmo depois do `getSession()` responder — a sessao mora no browser, entao o "deslogado" que o servidor obtem nao e uma resposta que ele tenha como saber se e verdade. Sem essa guarda o SSR gravava "Entrar / Criar conta" no HTML de todo mundo. Quem montar interface a partir do estado de login precisa checar essa flag antes.
  - **Lista de monitorados**: `FavoritesService.carregado$` existe porque `list$` e um `BehaviorSubject([])` e emite lista vazia no instante da inscricao, antes de qualquer requisicao — usar essa emissao como "terminou de carregar" fazia a tela afirmar "nenhum jogo monitorado" antes de perguntar ao servidor. Em erro de rede o `carregado$` **nao** vira `true`: a tela segue carregando em vez de mentir que a lista esta vazia. Vale pra qualquer `BehaviorSubject` com valor inicial vazio usado pra decidir estado vazio.

  **Meta tags de SEO ficam declaradas no `index.html`**, nao criadas pelo `SeoService`. Ele usa `Meta.updateTag()` quando a tag ja existe e `addTag()` quando nao existe, e `addTag` anexa no **fim** do `<head>` — depois do CSS que o Angular injeta. As tags `og:` caiam por volta do byte 63.000 e o crawler do WhatsApp, que le so o inicio do documento, nunca as alcancava: o preview do link saia sem capa, nome nem preco. Declaradas no `index.html` elas ficam por volta do byte 1.100 e o SeoService as atualiza no lugar. **Nao remover essas tags do `index.html`** achando que o SeoService as recria — ele recria, mas no lugar errado.
- **`sitemap.xml`** fica no dominio do **backend**, nao no frontend (pacote novo `sitemap/`, `ControladorSitemap` + `ServicoSitemap`). Motivo: sitemap referenciado via `Sitemap:` no `robots.txt` aceita dominio cruzado (Google suporta), e gerar no backend evita duplicar a query de jogos/filtros no frontend. Como o catalogo passa de 100k jogos (acima do limite de 50.000 URLs por arquivo do protocolo de sitemap), e um indice: `GET /sitemap.xml` lista `sitemap-estatico.xml` (home/catalogo/mais-vendidos/gratuitos) + uma pagina `sitemap-jogos-N.xml` por bloco de 10.000 jogos (`ServicoSitemap.TAMANHO_PAGINA`). `robots.txt` (`frontend/public/robots.txt`, estatico) desautoriza `/login`, `/perfil`, `/configuracoes`, `/monitorados`, `/admin/` e aponta `Sitemap: https://api.163.176.220.243.sslip.io/sitemap.xml` — precisa ser atualizado se o dominio do backend mudar (hoje e o IP temporario da Oracle via sslip.io). **Dois dominios convivem aqui, e trocar um pelo outro quebra o sitemap inteiro:** os `<loc>` do *indice* apontam pros proprios sub-sitemaps, servidos pelo **backend** (`app.public-backend-url`, a mesma variavel do retorno OpenID da Steam); ja as URLs *dentro* de cada sub-sitemap sao paginas do site e usam o **frontend** (`app.frontend-url`). O indice usava a URL do frontend nos dois casos ate 01/09/2026 — o crawler pegava o indice no backend, era mandado pro frontend e recebia **HTML em vez de XML** (o frontend responde 200 com a pagina do app pra qualquer caminho desconhecido), entao nenhuma pagina do catalogo era descoberta. Coberto por teste de regressao em `ServicoSitemapTest`.
- **Headers de seguranca** em toda resposta: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` (bloqueia camera/microfone/geolocalizacao), `Strict-Transport-Security` (HSTS, seguro porque o Caddy na Oracle sempre serve por HTTPS). Backend via `FiltroCabecalhosSeguranca` (`OncePerRequestFilter` em `configuracao/`); frontend via middleware Express em `src/server.ts` (so afeta requests que passam pelo servidor SSR — paginas 100% CSR do lado do browser nao tem como adicionar header de resposta HTTP). Sem CSP (Content-Security-Policy) em nenhum dos dois: a API so serve JSON e o frontend usa estilos inline gerados pelo Angular + fontes do Google Fonts, calibrar um CSP direito pra isso sem quebrar nada seria um projeto a parte.

## Coletas e Atualizacao de Catalogo

### Precos ITAD

O Spring executa a coleta internamente quando `APP_SYNC_SCHEDULER_ENABLED=true`.

- Intervalo padrao: 10 minutos.
- Cada rodada seleciona 5.000 jogos: 200 relevantes (top 2.000 por `rank`) e 4.800 da fila geral.
- A fila e dada por `games.last_price_sync_at`: o item mais antigo e atualizado primeiro e volta naturalmente ao fim da fila.
- A ITAD recebe lotes de ate 200 IDs, em ate 25 chamadas sequenciais por rodada.
- Cada lote e tentado ate tres vezes. Uma falha nao cancela os demais lotes; o jogo permanece prioritario na proxima rodada.
- A coleta atualiza **todas as ofertas retornadas pela ITAD** para cada jogo, como o botao manual `Atualizar precos`. Ofertas ITAD antigas que nao retornam mais sao removidas.
- Antes e depois da troca de ofertas, o backend compara o menor preco para criar notificacoes de queda para quem monitora o jogo.
- `sync_locks` impede jobs simultaneos, inclusive em deploys com duas instancias temporarias.
- `EstadoColeta` (status/contadores mostrados em `/admin/coleta`) e persistido na tabela `coleta_status`, nao em memoria (06/08/2026) — o backend redeploya varias vezes ao dia e um Map em memoria perderia status a cada deploy. No boot, `@PostConstruct` limpa qualquer `em_execucao = true` travado por um restart no meio de uma rodada (nao ha job de fato rodando logo apos o processo subir).
- **Cupons ITAD (05/08/2026)**: `ClienteItad.buscarPrecos` passa `vouchers=true` na chamada `/games/prices/v3` — sem esse parametro a ITAD omite ofertas com cupom aplicado por padrao. Quando existe, o `price`/`cut` da oferta ja vem calculado com o desconto do cupom (nao precisa calcular nada), e o codigo vai pra `offers.voucher_code`. O frontend mostra esse preco como o principal (ja e o menor preco real), com um selo "Cupom XYZ" do lado — no "Melhor preco" do topo e na linha da oferta especifica — indicando que precisa aplicar o codigo no carrinho da loja pra garantir o valor.

O endpoint `POST /api/sync?page=0` e legado: serve para diagnostico/manual e exige `X-Sync-Key`. Nao usar GitHub Actions para processar o catalogo completo.

### Historico de precos (22/08/2026)

Tabela `price_history` (`game_id`, `price`, `captured_at`) guarda a evolucao do menor preco de cada jogo, usada pelo grafico "Historico de preco (90 dias)" na aba Precos da pagina do jogo (`GET /api/games/{slug}/historico-precos?dias=90`, ver "API HTTP").

- **So grava quando o menor preco muda de verdade**, nao a cada sincronizacao: `RepositorioJogos.registrarHistoricoDePrecos` faz um unico `INSERT ... SELECT` comparando o `MIN(price)` atual das ofertas com a ultima linha gravada daquele jogo (`IS DISTINCT FROM`), so inserindo quando e diferente (ou quando ainda nao ha nenhuma linha). Isso mantem o volume da tabela proporcional a mudancas reais de preco, e nao ao ritmo dos jobs agendados (que tocam milhares de jogos a cada 10min mesmo sem nenhum preco ter mudado — gravar em toda sincronizacao geraria da ordem de 1M linhas/dia).
- Chamado a partir de `RepositorioJogos.salvarOfertas` (cobre os tres caminhos que escrevem ofertas da ITAD: refresh manual, `substituirOfertasItad` e o lote agendado `substituirOfertasItadEmLote`) e, separadamente, de dentro de `RepositorioInstantGaming` (`salvarPreco`/`removerOferta`) — duplicado ali de proposito, ja que esse pacote nao depende de `jogos` (ver nota de dependencia circular no topo de `RepositorioInstantGaming.java`).
- **Retencao de 90 dias**: poda diaria via `AgendadorColetas.podarHistoricoDePrecos` → `ServicoSincronizacao.podarHistoricoDePrecos` → `RepositorioJogos.podarHistoricoDePrecos(90)` (`DELETE ... WHERE captured_at < now() - 90 dias`), mesma trava/mecanismo `ServicoExecucaoColeta` dos outros jobs.
- **Sem dado retroativo**: a tabela comeca vazia em 22/08/2026 — nao ha como recuperar preco historico anterior a essa data. O frontend so mostra o grafico quando ha 2+ pontos (`temHistoricoSuficiente`); com 0 ou 1 ponto mostra uma nota "ainda estamos acompanhando" em vez de um grafico vazio/quebrado.
- Grafico e um SVG simples desenhado a mao em `game-detail.ts`/`.html` (sem biblioteca de charts, pra nao pesar o bundle) — linha+area com gradiente, ultimo ponto destacado e nota do menor preco do periodo.

### Metadados Steam de catalogo

Job separado, em geral a cada 15 minutos:

- Completa capa oficial quando ela esta ausente.
- Ajuda a identificar DLCs quando existe uma oferta Steam correspondente.
- Resolve e persiste `games.steam_app_id` (antes era resolvido a cada execucao e descartado).
- Processa um lote pequeno de jogos pendentes com `games.last_steam_sync_at`.
- Compartilha a mesma trava da coleta de preco.
- **Bug corrigido (31/07/2026)**: `ServicoSteam.APP_ID` (regex que extrai o appId da URL da oferta Steam) nao reconhecia jogos com aviso de conteudo (violencia/nudez), que a Steam redireciona pra `/agecheck/app/<id>/` em vez de `/app/<id>/` direto — esses jogos nunca ganhavam `steam_app_id` e ficavam sem reviews/detalhes/conquistas pra sempre (ex: Shadow of the Tomb Raider - Definitive Edition). O regex agora aceita o prefixo opcional `agecheck/`. Isso liberou o `steam_app_id` de centenas de jogos de uma vez, dobrando o backlog pendente do job de conquistas do catalogo (ver nota abaixo).

### Instant Gaming (preco extra por scraping)

A Instant Gaming nao tem API publica nem esta no ITAD (`isthereanydeal.com/shops/`; contato oficial pedindo API/parceria foi feito e negado). Preco vem por scraping das paginas de produto deles, modulo `instantgaming` (`ClienteInstantGaming`, `ServicoInstantGaming`, `RepositorioInstantGaming`), com tres jobs separados em `ServicoSincronizacao`/`AgendadorColetas`:

- **Varredura** (`escanearInstantGaming`, ~15min, lote de 30): descoberta por **id numerico sequencial de produto** (`instant-gaming.com/en/{id}-x/`, sempre redireciona pra URL canonica). O robots.txt deles bloqueia as paginas de busca (`/en/search/`, `/br/pesquisar/`, etc.) mas nao bloqueia paginas de produto individuais nem categoria — por isso a descoberta nunca usa busca, so varre ids em sequencia. Nao ha protecao anti-bot (Cloudflare/challenge) nas paginas de produto testadas, entao um `RestClient` comum com Jsoup basta, sem browser headless. Preco e nome vem de meta tags schema.org (`itemprop="name"/"price"/"priceCurrency"` dentro de `#product-app`), mais estavel que depender de classes CSS visuais. Grava titulo + titulo normalizado (mesma normalizacao do `GeradorSlug`) + URL em `instant_gaming_catalog`, avancando o cursor em `instant_gaming_scan_cursor` (uma linha so). Pausa de 300ms entre requisicoes dentro do lote pra ser educado com o servidor deles.
- **Casamento** (`casarInstantGaming`, ~20min, lote de 200): pra jogos com `games.instant_gaming_url IS NULL`, prioriza top-2000 por rank (mesmo padrao dos outros jobs), busca o titulo normalizado em `instant_gaming_catalog`. So grava o match quando existe **exatamente um** produto com aquele titulo normalizado — ambiguidade (edicoes/remakes com nome parecido) fica sem match em vez de arriscar linkar o jogo errado.
- **Precos** (`atualizarPrecosInstantGaming`, lote de 100 a cada 30min — ajustado depois que o casamento pegou ~100 jogos em poucas horas com a varredura acelerada): pra jogos ja casados, busca a pagina do produto de novo (pela URL canonica salva) e grava em `offers` com `source = 'instant_gaming'`, `store_name = 'Instant Gaming'`, `regular_price = NULL` (a pagina deles nao expoe preco cheio/desconto de forma confiavel ainda). Preco minimo (`MIN(o.price)`) ja pega isso automaticamente sem mudanca nenhuma no restante do catalogo.
- **Fora de estoque**: quando o produto esta fora de estoque, a pagina deles mantem `itemprop="price"` como `"0.00"` em vez de omitir o campo — sem tratar isso, o produto aparecia como o menor preco do catalogo (de graca, sem nem poder comprar). `ClienteInstantGaming` checa `itemprop="availability"` (rejeita quando contem "outofstock") e tambem rejeita qualquer preco `<= 0` como cinto de seguranca. Se um jogo ja tinha oferta salva e o produto fica fora de estoque depois, `atualizarPrecos`/`atualizarPrecoImediato` removem a oferta antiga em vez de deixar um preco desatualizado parecendo disponivel (`RepositorioInstantGaming.removerOferta`). Descoberta trata produto fora de estoque como "nao encontrado" (nunca entra em `instant_gaming_catalog` naquela varredura); se ele reabastecer depois, so e descoberto numa proxima passada por aquele id, o que hoje nao acontece automaticamente (a varredura so avanca o cursor pra frente).
- **Link de afiliado**: a Instant Gaming aprovou afiliacao em 27/07/2026 (codigo `oferta-games`). `ServicoInstantGaming` adiciona `?igr=oferta-games` (ou `&igr=...` se ja houver query string) na URL salva em `offers.url` — a URL canonica usada internamente pra buscar/casar produtos (`games.instant_gaming_url`, `instant_gaming_catalog.url`) fica sempre limpa, sem o parametro.
- **Refresh manual**: `POST /api/games/{slug}/refresh` atualiza ITAD e Instant Gaming juntos. Pra Instant Gaming, se o jogo ainda nao tiver `instant_gaming_url`, tenta casar na hora com o que ja foi descoberto ate entao (so consulta `instant_gaming_catalog`, sem scraping novo) em vez de esperar o proximo ciclo do job de casamento; o match persiste mesmo que a busca de preco falhe nessa chamada. So falha com `JogoSemItadException` quando o jogo nao tem ITAD **e** o Instant Gaming tambem nao atualizou nada.
- Disparo manual admin: `POST /api/admin/coleta/instant-gaming-escaneamento`, `instant-gaming-casamento` e `instant-gaming-precos`.
- `RepositorioInstantGaming` nao depende do pacote `jogos` (grava direto em `offers`/`games` via SQL propria) pra evitar dependencia circular, ja que `ServicoCatalogo` (em `jogos`) chama `ServicoInstantGaming` no refresh manual.
- Frontend: `store-brand.ts` reconhece `/instant.?gaming/i` e usa `store-logos/instant-gaming.png` (icone de marca oficial deles, PNG quase quadrado 298x300; passou por um monograma "IG" placeholder e um PNG wordmark antes disso).

### Detalhes e conquistas de catalogo (Sobre/Review/Conquistas)

Dois jobs novos em `ServicoSincronizacao`/`AgendadorColetas`, ambos dependem de `games.steam_app_id` ja preenchido pelo job de metadados Steam acima. So processam jogos com oferta Steam; os demais ficam sem esses dados (mesma limitacao pre-existente de capa/DLC).

- **Detalhes** (`coletarDetalhesJogos`, ~15min): reaproveita a mesma chamada `appdetails` do job de metadados Steam, solicitando `l=brazilian&cc=br`, e extrai descricao curta, generos, desenvolvedores, publicadoras, data de lancamento e screenshots; complementa com `store.steampowered.com/appreviews/{appid}` (nota, positivas, negativas). Grava em `game_details` (1 linha por jogo, upsert). Jogos sem detalhes entram imediatamente na fila; dados existentes com mais de 30 dias sao atualizados novamente.
- **Conquistas de catalogo** (`coletarConquistasCatalogo`, ~3h — schema e % global mudam devagar): `ISteamUserStats/GetSchemaForGame/v2` (precisa de `STEAM_WEB_API_KEY`, chamado com `l=brazilian` desde 19/07/2026 — antes vinha em ingles por faltar esse parametro) mesclado com `ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2` (publico). Grava em `game_achievements` (upsert por `api_name`). Nao tem relacao com `conexoes/AgendadorConquistasSteam`, que sincroniza as conquistas *desbloqueadas por um usuario logado* — este job novo e catalogo-wide, sem usuario.
  - Historico de backlog: em 19/07/2026 `game_achievements` foi zerada em producao pra reprocessar tudo em portugues (fix do `l=brazilian`) e o job foi acelerado ate zerar (concluido, revertido de volta pro ritmo normal). Em 31/07/2026 o fix do regex de `/agecheck/app/` em `ServicoSteam` (ver "Metadados Steam de catalogo" abaixo) liberou `steam_app_id` de centenas de jogos que antes ficavam bloqueados pra sempre, dobrando o backlog pendente de ~769 para ~1724; o job foi acelerado de novo e revertido em 01/08/2026 de volta pro ritmo normal. **Hoje (12/09/2026) esse job so roda pelo botao de admin** — a varredura agendada esta desligada desde 01/09/2026 (ver "Conquistas sao coletadas sob demanda"); os valores atuais sao `LIMITE_CONQUISTAS_CATALOGO = 250` por rodada e `steam-achievements-delay-ms` 30min, usados quando a varredura e disparada manualmente.
  - **Bug corrigido em 09/08/2026**: jogos sem nenhuma conquista real na Steam (`GetSchemaForGame` retorna vazio) nunca geravam linha em `game_achievements`, entao ficavam pendentes pra sempre e eram reconsultados em todo ciclo — como a fila ordena por `id ASC`, esses jogos entupiam o inicio dela e o job (so 15 jogos por rodada, a cada 3h) gastava a rodada inteira reconferindo os mesmos jogos sem nunca sobrar espaco pro resto do backlog, mesmo com milhares de jogos genuinamente pendentes. Adicionada `games.achievements_checked_at`: `ServicoCatalogo.preencherConquistas` marca o jogo como verificado mesmo quando o esquema vem vazio, e `listarPendentesConquistas`/`contarPendentesConquistas` passam a excluir quem ja foi checado. O backlog acumulado ate a correcao (12k+ jogos) ainda precisa ser processado no ritmo normal (ou acelerado manualmente se for preciso drenar mais rapido).
- Endpoints novos, independentes de `/api/games/{slug}` (usado pela aba Precos, que nao mudou): `GET /api/games/{slug}/detalhes` (404 se ainda nao sincronizado) e `GET /api/games/{slug}/conquistas` (lista vazia se nao houver).
- Disparo manual: `POST /api/admin/coleta/detalhes` e `POST /api/admin/coleta/conquistas-catalogo` (mesmo padrao de auth de admin dos demais tipos).
- `game_details` tambem guarda `trailer_url`/`trailer_thumbnail` (primeiro item de `movies` no appdetails), `about_full` + `feature_highlights` (jsonb, parseado via Jsoup dos blocos `<h2 class=bb_tag>` do `about_the_game`), `categories` (badges tipo "Suporte a controle") e `requirements_min`/`requirements_rec` (texto, uma linha por item, extraido do HTML de `pc_requirements`).
- Consumido pela pagina do jogo: abas Sobre/Review/Conquistas em `pages/game-detail/`. Sobre exige destaques ou trailer — descricao/screenshots sozinhos nao bastam, para nao aparecer em trilhas sonoras; Review fica disponivel em todo jogo para permitir a primeira avaliacao; Conquistas exige pelo menos 1 conquista no catalogo.

### Conquistas com progresso pessoal

`GET /api/games/{slug}/conquistas` aceita `Authorization` opcional. Sem login (ou sem Steam conectada, ou sem dado pra esse jogo), devolve a lista do catalogo com tudo bloqueado e 0%. Logado com progresso: cruza `game_achievements.api_name` (catalogo) com `steam_user_achievements` (por usuario e app id, ja existia pra sync de biblioteca) via `ServicoConexoesSteam.conquistasDesbloqueadas` (novo metodo publico — o repositorio de `conexoes` e package-private, entao o modulo `jogos` so acessa por esse service). Calcula `percentualConcluido` e a "proxima conquista" (a nao desbloqueada com maior `global_percent` = a que mais gente ja pegou). Sem pontuacao (decidido nao implementar por ora).

### Reviews (Steam + Oferta Games)

Duas fontes na aba Review, sub-abas no frontend (Oferta Games primeiro):
- **Steam**: o resumo global continua vindo de `game_details` (`notaReviews`/`reviewsPositivas`/`reviewsNegativas`). A pagina tambem consulta `GET /api/games/{slug}/avaliacoes/steam`, que usa a API publica de reviews da Steam, prioriza textos em portugues brasileiro, completa a primeira pagina com outros idiomas quando necessario e enriquece autor/avatar pela Steam Web API. O resultado fica em cache por 10 minutos. A interface segue o painel visual da pagina de reviews, com resumo, recomendacao, lista e destaques objetivos. Carrega 10 reviews por pagina e o botao `Ver mais` consulta a proxima pagina pelo cursor oficial da Steam. O seletor ordena de verdade por recentes, uteis ou atualizadas. Nao inventa distribuicao de estrelas, analise semantica, voto util ou formulario nessa sub-aba.
- **Oferta Games**: sistema de review proprio, modulo `avaliacoesjogo`. Tabelas `game_reviews` (nota 1-5 + comentario, um por usuario por jogo, upsert) e `game_review_votes` (util/nao-util por review, um voto por usuario, pode trocar mas nao remover). "Recomenda" e derivado de `rating >= 4`, sem campo proprio. Nome/avatar de quem avaliou vem de `profiles` (mesma tabela do perfil publico).
  ```
  GET    /api/games/{slug}/reviews             auth opcional -> resumo (media, distribuicao por nota, % recomenda) + minha review + lista
  POST   /api/games/{slug}/reviews              auth obrigatoria -> { nota, comentario } -> upsert
  DELETE /api/games/{slug}/reviews              auth obrigatoria -> remove a propria review
  POST   /api/games/{slug}/reviews/{id}/voto     auth obrigatoria -> { util: boolean } -> upsert do voto
  ```
  Fora de escopo por ora: "destaques automaticos das reviews" (analise de texto/temas).

## Fonte de Dados e Regras de Catalogo

### ITAD

ITAD e a fonte principal de catalogo e ofertas. O sistema trabalha somente com BRL por enquanto.

- Menor preco e calculado em query com `MIN(price)`, nao armazenado.
- `rank` vem da ITAD: menor rank significa jogo mais relevante.
- A busca consulta o banco primeiro; se nao encontrar, consulta ITAD e persiste os resultados.
- A pagina de detalhe pode atualizar um jogo individualmente via ITAD.
- **Preco 0 da ITAD e sempre confiavel, nao filtrar por "price=0 e regular=0 juntos" (tentado e revertido em 29/07/2026)**: parece dado quebrado (delistado/placeholder) mas as vezes e giveaway real — confirmado com "Amigdala" (`store.steampowered.com/api/appdetails?appids=450110` retorna `is_free:true` de verdade) que tinha Steam a R$0 e Fanatical pago ao mesmo tempo, um caso legitimo de "gratis numa loja, pago em outra". Um filtro que rejeitava price=0/regular=0 quando o jogo tinha outra oferta paga (baseado so nos campos de preco) removeu esse caso real junto com entradas de fato quebradas, sem forma confiavel de distinguir os dois so pelos numeros. Se reaparecer o problema original (muitos jogos pagos comuns aparecendo a R$0,00 sem ser giveaway), investigar caso a caso antes de qualquer filtro automatico — nao ha sinal automatico confiavel encontrado ate agora.
- **Gratuitos e ofertas com regular_price 0 (29/07/2026)**: `RepositorioDescontos.listarMelhores` (usada por `/api/deals/top` e pela pagina Gratuitos) calculava desconto so quando `regular_price > 0`, entao um jogo permanentemente gratis (giveaway sem "preco normal" registrado, ex: Amigdala na Steam) nunca entrava, mesmo sendo gratuito de verdade. Agora `price = 0` sempre conta como 100% off (`discount_pct` fixo em 100 nesse caso, sem dividir por `regular_price`, que pode ser 0/nulo), independente de outras lojas venderem o mesmo jogo pago.

### Lojas e conteudos bloqueados

As regras ficam no backend em classes de dominio, nao no frontend:

- `LojasBloqueadas.java`: GreenManGaming, AllYouPay/AllYouPlay, PlanetPlay, PlayerLand, JoyBuggy, WinGameStore, MacGameStore e Humble Store/Humble Bundle, porque os links nao abriam corretamente. GamesPlanet US/FR/DE/UK (01/08/2026), porque mostram preco em USD/EUR/GBP em vez de BRL — nao existe uma variante "GamesPlanet BR". GOG (01/08/2026), decisao de produto: pouco usada e os jogos la costumam ficar restritos aquela plataforma (sem sync com biblioteca Steam). Ofertas ja salvas dessas lojas nao sao apagadas, so ficam de fora de toda leitura via `filtroLojaBloqueada`/`LojasBloqueadas.filtroSql` (mesmo padrao das outras lojas bloqueadas). O regex SQL agora e ancorado (`^(...)$`, exige nome normalizado identico) em vez de so "contido", pra nomes curtos como "gog" nao baterem em lojas futuras que so contenham esse trecho.
- `JogosBloqueados.java`: itens especificos com link quebrado, incluindo `tell-me-why-chapter-1` e a URL ITAD `019e8518-404a-709b-ae47-a4ef949552ea`.
- `ConteudosNaoJogos.java`: cursos, bundles educacionais e musicais nao aparecem como jogos. Exemplos de termos filtrados: certification, e-learning, Kali Linux, programming bundle, cybersecurity, phonk e masterclass.

Ao adicionar uma nova loja ou excecao, garantir que ela seja filtrada em catalogo, home, busca, detalhe, favoritos e fila de coleta.

### Plataformas e DLCs

- O catalogo recebe `platform=all|pc|xbox`.
- Como a plataforma ainda nao e persistida diretamente da ITAD, o frontend infere PC/Xbox pelo nome e URL da loja. PlayStation permanece oculto ate haver ofertas confiaveis.
- DLCs sao separados de jogos base por `games.is_dlc` e heuristicas de titulo (`ClassificadorDlc`). O sinal da Steam (`type == "dlc"` no appdetails) e o fallback por titulo sao combinados com OR — a Steam classifica trilhas sonoras como `type: "music"`, entao depender so dela deixava `is_dlc = false` incorretamente pra esses itens (corrigido em 18/07/2026; jogos ja classificados errado precisaram de `UPDATE games SET is_dlc = NULL WHERE ...` manual pra reprocessar, ja que o job so revisita jogos com `is_dlc IS NULL`).
- A home deve manter uma secao de maiores descontos de DLC separada quando houver itens elegiveis.
- Pagina de detalhe do jogo (aba Precos, abaixo da lista de lojas): mostra as DLCs desse jogo, quando existirem no catalogo. A relacao vem do array `dlc` da appdetails da Steam (lista de app ids das DLCs do jogo base), salvo em `game_details.dlc_steam_app_ids` junto com os demais detalhes (`ServicoCatalogo.preencherDetalhesJogos`/`ServicoSteam.buscarDetalhesAplicativo`). `RepositorioJogos.listarDlcsDoJogo` cruza esses app ids com `games.steam_app_id` pra montar os cards — so aparecem DLCs que ja foram descobertas via ITAD (tem entrada propria no catalogo); a secao inteira fica oculta quando a lista vem vazia. Backfill: `listarPendentesDetalhes` reprocessa jogos ja detalhados enquanto `dlc_steam_app_ids IS NULL`, entao o catalogo existente e preenchido aos poucos pelo job de detalhes (60 jogos/15min) sem precisar de um job separado.
- `POST /api/games/{slug}/refresh` no jogo base tambem atualiza o preco de cada DLC listada (`ServicoCatalogo.atualizarPrecosDoJogo`, reaproveitado pro jogo principal e pras DLCs). Diferente do jogo principal, uma DLC sem fonte de preco disponivel e simplesmente ignorada — nao derruba o refresh do jogo base.
- Esse endpoint e publico (sem login) — cooldown de 5min por jogo (10/08/2026): `games.last_manual_refresh_at`, checado no inicio de `atualizarPrecos` antes de qualquer chamada externa. Dentro do cooldown, responde 429 com `{ error, segundosRestantes }` e header `Retry-After`; o botao "Atualizar precos" mostra a contagem regressiva em vez de ficar so desabilitado. O cooldown e so no jogo principal do refresh, nao em cada DLC individualmente.
- `atualizarPrecos` busca as DLCs em lote (`RepositorioJogos.buscarParaAtualizarPorSlugs`) em vez de 1 query por DLC (10/08/2026), e `atualizarPrecosDoJogo` salva as ofertas ITAD de um jogo com `salvarOfertas` (lote) em vez de `salvarOferta` por oferta.
- Caminho inverso (06/08/2026): na pagina de uma DLC, a aba Precos mostra uma secao "Jogo base" com o(s) jogo(s) do qual ela faz parte, dando ida e volta entre as duas paginas. `RepositorioJogos.listarJogosBaseDaDlc` cruza o `steam_app_id` do proprio jogo com o `dlc_steam_app_ids` de todo mundo (`gd.dlc_steam_app_ids @> ARRAY[:steamAppId]`) — so aparece quando o jogo base ja foi detalhado o suficiente pra ter essa relacao salva.

## Schema Relevante

Desde 10/09/2026 (ver "Migracao do catalogo pra fora do Supabase" em "Deploy e Operacao"), **"Catalogo" abaixo mora no Postgres self-hosted da VM** (`CATALOG_DATABASE_URL`); todo o resto continua no Supabase (`DATABASE_URL`), junto do Auth.

### Catalogo (Postgres self-hosted na VM)

```text
games
  id, itad_id, title, slug, cover_url, rank, is_dlc, steam_app_id
  last_price_sync_at, last_steam_sync_at, created_at

offers
  id, game_id, source, store_name, price, regular_price
  currency, url, voucher_code, updated_at
  UNIQUE (game_id, source, store_name)
  -- voucher_code: preenchido quando a ITAD (com vouchers=true) devolve um preco ja calculado
  -- com cupom aplicado; price/regular_price ja vem com o desconto do cupom embutido

game_details
  game_id (PK, FK games), short_description, genres[], developers[]
  publishers[], release_date, screenshots[]
  review_score_desc, review_positive, review_negative
  trailer_url, trailer_thumbnail, about_full, feature_highlights (jsonb)
  categories[], requirements_min, requirements_rec, dlc_steam_app_ids[], updated_at

game_achievements
  id, game_id (FK games), api_name, display_name, description
  icon_url, icon_gray_url, global_percent, position
  UNIQUE (game_id, api_name)

price_history
  id, game_id (FK games), price, captured_at
  -- so grava uma linha quando o menor preco do jogo (MIN entre as ofertas) mudou desde a
  -- ultima linha gravada, nao a cada sincronizacao (ver "Historico de precos" abaixo)

instant_gaming_catalog
  product_id (PK), title, normalized_title, url, discovered_at
```

`game_id` em `offers`/`game_details`/`game_achievements`/`price_history` referencia `games(id)` **dentro do proprio Postgres do catalogo** (FK preservada, ja que as duas pontas moraram juntas). Ja `game_id`/`game_reviews.game_id` em tabelas do lado Supabase (abaixo) sao so um `bigint` solto, sem FK de banco pro catalogo — a integridade referencial entre os dois bancos e garantida so pela aplicacao (o app nunca deleta jogo, so upsert).

### Catalogo, mas ficaram no Supabase (tem FK com `auth.users` ou sao operacionais)

```text
game_reviews
  id, game_id (bigint, sem FK — id do catalogo), user_id (FK auth.users), rating (1-5), comentario
  created_at, updated_at
  UNIQUE (game_id, user_id)

game_review_votes
  review_id (FK game_reviews), user_id (FK auth.users), util
  PK (review_id, user_id)

sync_locks
  name, locked_until

coleta_status
  tipo, em_execucao, inicio_atual, ultima_conclusao, ultima_duracao_ms
  jogos_atualizados, ofertas_atualizadas, ultimo_erro
  -- status/contadores dos jobs de coleta (ver "/admin/coleta"); EstadoColeta
  -- ainda escreve aqui (Supabase), mesmo com o catalogo em outro banco
  -- (foi exatamente essa mistura que causou o bug #1 da migracao de 10/09/2026)

instant_gaming_scan_cursor
  id (sempre true), last_scanned_id
  -- unica excecao dentro de RepositorioInstantGaming: nao fez parte da migracao,
  -- ficou no Supabase (ver "Migracao do catalogo pra fora do Supabase")
```

### Conta, monitoramento e notificacoes (Supabase)

```text
favorites
  user_id, game_id, created_at
  -- representa Jogos Monitorados, nunca favoritos pessoais

price_notifications
  user_id, game_id, previous_price, current_price, store_name
  read_at, created_at
```

### Perfis, Steam e Xbox

```text
profiles
  user_id, handle, display_name, bio, avatar_url, is_public
  show_game_hours, show_achievements, show_library
  show_favorite_games, show_recent_activity
  avatar_zoom, avatar_position_x, avatar_position_y
  banner_url, banner_zoom, banner_position_x, banner_position_y
  show_collections
  show_steam_wishlist
  -- toggle proprio da colecao de sistema da wishlist (default true); nao faz parte do
  -- salvar() geral do perfil, tem endpoint dedicado (ver "Colecoes de sistema")

steam_connections
  user_id, steam_id, persona_name, avatar_url
  connected_at, last_library_sync_at, last_achievement_sync_at, last_error

steam_library_games
  user_id, app_id, title, playtime_minutes, icon_hash, last_synced_at

steam_game_achievements
  user_id, app_id, unlocked_count, total_count, last_synced_at

profile_platinum_order
  user_id, app_id, position
  -- ordem manual dos platinados Steam (arrastar no bloco do perfil); so Steam por enquanto

xbox_connections
  user_id, xuid, gamertag, avatar_url, gamerscore, access_token
  connected_at, last_library_sync_at, last_error
  -- access_token: token por usuario devolvido pelo /app/claim da OpenXBL, usado nas chamadas seguintes em nome dele

xbox_library_games
  user_id, title_id, title, cover_url
  achievements_unlocked, achievements_total, gamerscore_unlocked, gamerscore_total
  minutes_played, last_played_at, last_synced_at
  -- upsert-only por title_id: a sincronizacao nunca faz DELETE, so atualiza/adiciona

profile_favorites
  user_id, game_id, created_at, position

profile_steam_favorites
  user_id, app_id, created_at, position
  -- usado para jogos da biblioteca Steam ausentes do catalogo
  -- position: sequencia manual unica por usuario, combinando as duas tabelas

profile_collections
  id, user_id, name, origem, position, created_at
  -- "Minhas Listas": grupos nomeados de jogos, independentes dos favoritos
  -- origem: 'usuario' (padrao, editavel) ou 'steam_wishlist' (colecao de sistema,
  -- travada - ver "Colecoes de sistema" na API HTTP); indice unico parcial
  -- garante no maximo 1 colecao 'steam_wishlist' por usuario

profile_collection_items
  id, collection_id, user_id, game_id, app_id, position, created_at
  -- game_id (catalogo) OU app_id (biblioteca Steam), nunca os dois (CHECK)
  -- N:N: o mesmo jogo pode estar em varias colecoes

profile_activities
  user_id, type, game_id, detail, created_at

profile_blocks
  user_id, block_id, block_type, title, content, position, size
  visible, background_type, background_value, overlay_opacity, text_color
  view_mode
```

`profile_blocks.visible` era gravado como `true` fixo enquanto nenhuma tela expunha o campo. Desde o toggle **Ativo/Oculto** do editor de blocos (11/09/2026) ele grava a escolha do dono — e um controle de layout ("nao quero esse bloco no perfil agora"), nao de privacidade: a privacidade continua sendo do perfil como um todo e dos controles gerais de dados.

`profile_blocks.view_mode` (12/09/2026) guarda a visualizacao escolhida pro bloco: `cards` (capa grande) ou `lista` (linha compacta). `NULL` = padrao do tipo — hoje so o bloco de favoritos tem as duas desenhadas, e ele nasce em `lista`.

## Migrations Supabase

Arquivos em `backend-java/sql/`:

1. `20260711_coleta_agendada.sql`
2. `20260711_conexoes_steam.sql`
3. `20260711_perfis_publicos.sql`
4. `20260712_atividades_perfil.sql`
5. `20260712_atividades_steam_detalhadas.sql`
6. `20260712_atualizacao_publica_perfil.sql`
7. `20260712_avatars_perfil.sql`
8. `20260712_favoritos_pessoais.sql`
9. `20260712_perfis_publicos_por_padrao.sql`
10. `20260712_visibilidade_atividade_perfil.sql`
11. `20260713_editor_de_perfil.sql`
12. `20260713_favoritos_steam_perfil.sql`
13. `20260713_notificacoes_preco.sql`
14. `20260713_cor_texto_blocos_perfil.sql`
15. `20260716_banner_perfil.sql`
16. `20260716_ordem_favoritos_perfil.sql`
17. `20260717_colecoes_perfil.sql`
18. `20260718_detalhes_jogo.sql`
19. `20260718_detalhes_jogo_extra.sql`
20. `20260718_avaliacoes_jogo.sql`
21. `20260718_reprocessar_detalhes_pt_br.sql`
22. `20260718_biblioteca_steam_capas.sql`
23. `20260719_instant_gaming.sql`
24. `20260801_ordem_platinados_perfil.sql`
25. `20260805_voucher_code_offers.sql`
26. `20260806_conexoes_xbox.sql`
27. `20260806_biblioteca_xbox.sql`
28. `20260805_dlc_steam_app_ids.sql`
29. `20260806_coleta_status_persistente.sql`
30. `20260809_achievements_checked_at.sql`
31. `20260810_cooldown_refresh_manual.sql`
32. `20260822_historico_precos.sql`
33. `20260910_colecao_wishlist_steam.sql`
34. `20260911_mostrar_wishlist_steam.sql`
35. `20260912_view_mode_blocos.sql`

As migrations 1 a 35 ja foram aplicadas ao projeto Supabase de producao. A 35 adiciona `profile_blocks.view_mode` (`cards`/`lista`/`NULL`), usada pela opcao "Visualizacao" do editor de blocos; precisa ser aplicada antes do deploy do backend que le/grava essa coluna, senao `GET /api/perfis/{handle}` e `GET /api/perfis/me/blocos` quebram (coluna inexistente). A 33 adiciona `profile_collections.origem` e o indice unico parcial da wishlist (ver "Colecoes de sistema" na API HTTP); precisa ser aplicada antes do deploy do backend que sincroniza a wishlist, senao `GET /api/profile-collections` quebra (coluna `origem` inexistente). A 34 adiciona `profiles.show_steam_wishlist` (default `true`), usada pelo toggle de visibilidade da wishlist; precisa ser aplicada antes do deploy do backend que le/escreve essa coluna. A 28 adiciona `game_details.dlc_steam_app_ids` (ver "Plataformas e DLCs"). A 29 cria `coleta_status`, usada por `EstadoColeta` pra persistir o status dos jobs de coleta entre deploys; precisa ser aplicada antes do deploy do backend que passou a le-la/escreve-la, senao `/admin/coleta` quebra com erro 500. A 30 adiciona `games.achievements_checked_at` (ver "Detalhes e conquistas de catalogo"). A 31 adiciona `games.last_manual_refresh_at`, usada pelo cooldown do refresh manual (ver acima). A 24 cria `profile_platinum_order` para a ordem manual dos platinados Steam (arrastar no bloco do perfil). A 25 adiciona `offers.voucher_code`. A 26 cria `xbox_connections` (fluxo OAuth "Xbox App" da OpenXBL). A 27 cria `xbox_library_games` (upsert-only) e depois adiciona `minutes_played`; precisa ser aplicada antes do deploy do backend que sincroniza biblioteca Xbox. A migration 21 marcou 408 detalhes existentes como antigos em 18/07/2026 para a fila substitui-los gradualmente pelo conteudo pt-BR, sem apagar o texto atual durante o processamento. Em outro ambiente, executar as migrations estruturais em ordem antes de publicar o backend. Em especial, a coluna `profile_activities.detail` e obrigatoria para atividade recente detalhada; se ela estiver ausente, a rota de perfil pode retornar HTTP 500. A migration do banner (15) precisa ser aplicada antes do deploy do backend que a usa: o backend seleciona `banner_url`/`banner_zoom`/`banner_position_x`/`banner_position_y` em toda consulta de perfil, entao sem essas colunas qualquer pagina de perfil (propria ou publica) quebra com erro 500. A migration 16 adiciona `position` aos favoritos e tambem precisa ser aplicada antes do deploy do backend que ordena por essa coluna. A 17 cria as colecoes e adiciona `profiles.show_collections`, lido em toda consulta de perfil: sem ela, qualquer pagina de perfil quebra com 500. A migration 22 adiciona `cover_url`/`cover_synced_at` a `steam_library_games` para guardar a capa real de cada jogo (resolvida via appdetails, ja que a Steam mudou o CDN das capas pra um caminho com hash imprevisivel em `shared.akamai.steamstatic.com`); precisa ser aplicada antes do deploy do backend que preenche/le essas colunas. A migration 23 adiciona `instant_gaming_url`/`last_instant_gaming_sync_at` a `games` e cria `instant_gaming_catalog`/`instant_gaming_scan_cursor` (ver secao "Instant Gaming" acima); precisa ser aplicada antes do deploy do backend que usa essas tabelas/colunas. A migration 32 cria `price_history` (ver "Historico de precos" acima); precisa ser aplicada antes do deploy do backend que grava/le essa tabela.

O bucket publico `avatars` do Supabase Storage guarda avatar, imagens dos blocos e seus fundos. Cada usuario so pode gravar na propria pasta. As politicas RLS de `SELECT`, `INSERT`, `UPDATE` e `DELETE` foram aplicadas ao projeto novo em 15/07/2026; sem elas o Storage retorna HTTP 400 nos uploads. Limite de upload de imagem no frontend: 2 MB, JPG/PNG/WebP. Blocos de imagem e fundos tambem aceitam URL externa `http(s)`.

## API HTTP

### Catalogo

- `GET /api/games?page=&size=&sort=&type=&platform=&minPrice=&maxPrice=&minDiscount=&q=&stores=`
  - `sort`: `rank` (relevancia: destaques com desconto primeiro, depois rank), `popularity` (rank puro, "Mais famosos"), `discount`, `price_asc`, `price_desc`.
  - `type`: `all`, `game`, `dlc`.
  - `stores` (22/08/2026): chaves separadas por virgula (`steam,epic,...`) do filtro "Lojas preferidas" das Configuracoes — ver `LojasCatalogo` em `comum/`. So filtra quando alguma chave e enviada; sem o parametro, mostra ofertas de todas as lojas (comportamento antigo). Filtra tanto quais jogos aparecem quanto o preco minimo/loja exibidos, pra nao mostrar preco de uma loja que o usuario nao quer ver.
  - Retorna a oferta minima, incluindo loja e URL quando disponiveis.
  - `RepositorioJogos.listar` e cacheado em memoria (Caffeine, `comum/ConfiguracaoCache`) por 10min por combinacao de parametros, pra nao repetir a query a cada abertura do catalogo. Expira sozinho; nao ha invalidacao manual quando a sincronizacao de precos roda. A pagina Mais Vendidos usa este mesmo endpoint (`getGames`), entao ja se beneficia do cache.
- `GET /api/games/search?q=nome`
- `GET /api/games/{slug}` — inclui `dlcs[]` (DLCs desse jogo ja presentes no catalogo) e `jogosBase[]` (caminho inverso: se este jogo e uma DLC, o(s) jogo(s) base dela); ver "Plataformas e DLCs".
- `GET /api/games/{slug}/avaliacoes/steam?cursor=&ordenacao=recent&idioma=brazilian`: pagina de 10 reviews reais da Steam, com texto, recomendacao, tempo jogado, autor e avatar quando disponiveis. Retorna `proximoCursor`, `temMais`, `idiomaConsulta` e `ordenacao`; aceita `recent`, `all` (mais uteis) e `updated`. Resposta publica e cacheada.
- `GET /api/games/{slug}/detalhes` — descricao/generos/devs/publishers/data/screenshots/review, de `game_details`. 404 se o jogo ainda nao foi sincronizado (sem oferta Steam, ou aguardando o job).
- `GET /api/games/{slug}/conquistas` — auth opcional. `{ total, desbloqueadas, percentualConcluido, proxima, conquistas[] }`; cada conquista com `desbloqueada`/`desbloqueadaEm` cruzados com o progresso do visitante logado (ver "Conquistas com progresso pessoal" acima). Sempre 200, mesmo pra jogo inexistente (fica tudo zerado).
- `GET|POST|DELETE /api/games/{slug}/reviews`, `POST /api/games/{slug}/reviews/{id}/voto` — ver "Reviews (Steam + Oferta Games)" acima.
- `POST /api/games/{slug}/refresh`
- `GET /api/games/{slug}/historico-precos?dias=90` — pontos `{ price, capturadoEm }` de `price_history`, ordem cronologica; `dias` limitado a 1-90. 404 se o slug nao existir. Ver "Historico de precos" acima.
- `GET /api/deals/top?size=&sort=discount|rank&type=all|game|dlc` — usado pela Home (rank, discount e discount+type=dlc) e pela pagina Gratuitos (`size=100&sort=discount`, filtrando `discountPct === 100` no front); `RepositorioDescontos.listarMelhores` tambem cacheado (Caffeine, `ConfiguracaoCache.CACHE_DESCONTOS`, 10min), pois e uma query com DISTINCT ON + join na tabela `offers` inteira. `ServicoAquecimentoCache` reaquece as combinacoes usadas (100/rank, 200/discount, 100/discount, 50/discount/dlc) logo apos cada rodada de precos, pra nenhuma delas (incluindo Gratuitos) pegar cache frio.
  - **`type=dlc` (22/08/2026)**: o carrossel "Maiores descontos em DLCs" da Home derivava as DLCs filtrando (no frontend) o mesmo pool geral de 200 mais descontados — como DLC e uma fatia pequena do catalogo, quase nunca sobrava alguma nesse pool (so 3 em producao, apesar de existirem milhares de DLCs com desconto ativo). Agora o backend filtra DLC diretamente na query (reusa `ClassificadorDlc`, ja usado no catalogo) e a Home pede um pool proprio de 50 (nao 20) porque boa parte das entradas mais "descontadas" sao preco zerado/glitch (100% off), que o frontend descarta — com folga suficiente pra sobrar 20+ com desconto real.
- `POST /api/sync?page=` (legado, exige `X-Sync-Key`)

### Jogos Monitorados e notificacoes

- `GET|POST /api/favorites`
- `DELETE /api/favorites/{slug}`
- `GET /api/notifications`
- `GET /api/notifications/unread-count`
- `PATCH /api/notifications/{id}/read`
- `PATCH /api/notifications/read-all`
- `DELETE /api/notifications/{id}`

### Perfil e favoritos pessoais

- `GET /api/perfis/me`
- `PUT /api/perfis/me`
- `PUT /api/perfis/me/avatar`
- `PUT /api/perfis/me/banner`
- `PUT /api/perfis/me/wishlist-steam` — `{ mostrar: boolean }`, ver "Colecoes de sistema"
- `GET|PUT /api/perfis/me/blocos`
  - o `GET` devolve **todos** os blocos do dono, inclusive os ocultos, e e a fonte da pagina `/perfil/blocos`. Nao trocar por `GET /api/perfis/{handle}`: aquele filtra os ocultos e o "Salvar" (que substitui a lista inteira) apagaria o bloco escondido.
  - o `PUT` valida tipo, tamanho, `tipoFundo`, opacidade (0-85), cores `#RRGGBB` e `visualizacao` (`cards`/`lista`/null), no maximo 20 blocos.
- `GET /api/perfis/{handle}`
- `POST /api/perfis/{handle}/atualizar`
  - qualquer visitante pode pedir atualizacao Steam publica; cada perfil aceita uma solicitacao a cada 10 minutos.
- `GET|POST /api/profile-favorites`
- `DELETE /api/profile-favorites/{slug}`
- `POST /api/profile-favorites/steam`
- `DELETE /api/profile-favorites/steam/{appId}`
- `PUT /api/profile-favorites/ordem`
  - recebe `{ itens: [{ slug?, steamAppId? }] }` na ordem desejada e grava `position` 0,1,2... Novos favoritos entram no fim da fila.

### Colecoes ("Minhas Listas")

- `GET|POST /api/profile-collections`
- `PUT|DELETE /api/profile-collections/{id}`
- `POST /api/profile-collections/{id}/itens` — `{ slug? , steamAppId? }`, exatamente um dos dois
- `DELETE /api/profile-collections/{id}/itens/jogo/{slug}`
- `DELETE /api/profile-collections/{id}/itens/steam/{appId}`

Toda rota exige token e valida que a colecao e do usuario autenticado; colecao de outro usuario responde 404, nunca 403, para nao revelar a existencia dela. Limites: nome de 1 a 40 caracteres, 20 colecoes por usuario e 200 jogos por colecao. As rotas de escrita (`PUT`/`DELETE /{id}`, `POST|DELETE /{id}/itens*`) respondem `400` quando a colecao e de sistema (ver abaixo) — so a colecao normal (`origem = "usuario"`) aceita edicao manual.

#### Colecoes de sistema (10/09/2026)

`profile_collections.origem` (`"usuario"` por padrao, ou `"steam_wishlist"`) marca colecoes geridas automaticamente pelo backend, sem edicao manual do dono. Hoje so existe uma: **"Lista de Desejos (Steam)"**, criada e mantida por `ServicoConexoesSteam.sincronizarWishlist` — roda dentro de `sincronizarBiblioteca` (mesma cadencia da biblioteca: conexao inicial, botao manual, solicitacao publica a cada 10min; nao ha job agendado global so pra wishlist).

- Busca `IWishlistService/GetWishlist/v1` (Steam Web API, sem exigir `key`) e casa cada `appid` com `games.steam_app_id` — so jogos ja descobertos no catalogo entram na colecao; os demais ficam de fora ate serem descobertos, sem erro.
- **Sincronizacao e reconciliacao completa**, nao so adicao: `RepositorioColecoesPerfil.sincronizarItensSistema` faz a colecao ficar exatamente igual a wishlist atual — jogo comprado ou removido da wishlist real desaparece da colecao no proximo sync. E o unico lugar do backend que faz `DELETE` em `profile_collection_items` fora de um pedido explicito do dono; seguro porque e escopado so a colecoes de sistema (nunca a uma colecao criada manualmente).
- Falha ao buscar a wishlist nunca derruba a sincronizacao da biblioteca (que roda antes, no mesmo metodo) — erro isolado, so registrado em `steam_connections.last_error`.
- `GET /api/profile-collections` devolve `origemSistema: boolean` em cada colecao; o frontend usa isso pra esconder Renomear/Excluir/Gerenciar jogos e mostrar um selo "Sincronizada com a Steam" (aba Colecoes do perfil).
- Indice unico parcial `profile_collections_wishlist_unica` garante no maximo 1 colecao de wishlist por usuario.
- **Sempre no topo e recolhida por padrao (11/09/2026)**: `colecoesOrdenadas` (getter em `public-profile.ts`) poe colecoes de sistema antes das do usuario, independente de `position`. Toda colecao (de sistema ou nao) comeca recolhida — clicar no cabecalho (`toggleColecaoExpandida`) expande e mostra a grade de jogos; sem isso uma colecao grande (a wishlist ja passa de 30 itens em alguns testes) deixava a aba "infinita" de scroll.
- **Visibilidade independente do toggle geral de colecoes (11/09/2026)**: `profiles.show_steam_wishlist` (default `true`) controla so a wishlist, separado de `show_collections`. Desmarcado, a colecao some **pra todo mundo, inclusive o dono** — ele ve exatamente o que um visitante veria, decisao explicita do usuario ao revisar a feature ("nao deveria desaparecer pra mim tambem? pra eu ver como todos veem?"). O checkbox continua visivel mesmo com a colecao escondida (senao ninguem reativaria): `PerfilPublico.temColecaoWishlistSteam` (existe uma wishlist, calculado antes do filtro de `mostrarWishlistSteam`) e um campo separado de `mostrarWishlistSteam` (o estado atual do toggle) — o frontend usa o primeiro pra decidir se mostra o checkbox, o segundo pro estado marcado/desmarcado. Checkbox "Mostrar lista de desejos Steam" fica dentro do modo **Organizar** da aba Colecoes (nao em Configuracoes > Privacidade, diferente dos outros `mostrar*`). `PUT /api/perfis/me/wishlist-steam` (`{ mostrar: boolean }`) e um endpoint dedicado, no mesmo padrao de `/me/avatar`/`/me/banner` — nao passa pelo `salvar()` geral do perfil.

### Steam, Xbox e administracao

- `POST /api/conexoes/steam/iniciar`
- `GET /api/conexoes/steam/retorno`
- `GET|DELETE /api/conexoes/steam`
- `GET /api/conexoes/steam/biblioteca` (endpoint do dono; retorna a biblioteca inteira, ate 2000 jogos, para montar colecoes. A previa do perfil publico segue limitada a 100)
- `POST /api/conexoes/steam/sincronizar`
- `PUT /api/conexoes/steam/platinados/ordem` (ordem manual dos platinados; so appIds Steam)
- `GET /api/conexoes/xbox/app-key` (publico, sem auth — "Public Key" da OpenXBL, usada pelo frontend para montar a URL de login)
- `POST /api/conexoes/xbox/concluir` (troca o `code` do retorno OAuth por xuid/gamertag/token, autenticado pelo bearer normal do usuario)
- `GET|DELETE /api/conexoes/xbox`
- `POST /api/conexoes/xbox/sincronizar`
- `GET /api/admin/coleta` — status de todos os 7 jobs (precos, steam, detalhes, conquistas-catalogo, instant-gaming-escaneamento/casamento/precos) e as filas correspondentes (`fila`, `pendentesDetalhes`, `pendentesConquistas`, `filaInstantGaming`).
- `POST /api/admin/coleta/{tipo}` — dispara qualquer um dos 7 tipos manualmente, mesmo padrao de auth de admin (`precos`, `steam`, `detalhes`, `conquistas-catalogo`, `instant-gaming-escaneamento`, `instant-gaming-casamento`, `instant-gaming-precos`).
- `POST /api/admin/jogos/{slug}/preencher-tudo` — botao "Preencher tudo agora": roda os tres passos da Steam pra um jogo so, na hora (metadados **forcados** + detalhes + conquistas). Sobrescreve capa/`steam_app_id` existentes de proposito — serve pra corrigir dado errado. O mesmo `ServicoCatalogo.preencherTudoDoJogo` roda automaticamente quando aparece conquista desbloqueada fora do catalogo (ver "Recoleta de jogo live-service").
- `GET /actuator/health`
- `GET /sitemap.xml`, `GET /sitemap-estatico.xml`, `GET /sitemap-jogos-{pagina}.xml` — publicos, sem auth (ver "SEO (SSR), sitemap e headers de seguranca" acima).

Os endpoints autenticados recebem token Bearer do Supabase. A administracao exige o UID autorizado no backend: `0a6eb06b-756e-4434-899b-33420bed8609`.

## Frontend e UX

### Rotas e navegacao

- `/` home.
- `/catalogo` usa scroll infinito e filtros.
- `/jogo/:slug` mostra ofertas e atualizacao individual.
- `/monitorados` mostra a lista de precos acompanhados. `/favoritos` redireciona por compatibilidade.
- `/perfil` e uma ponte autenticada: resolve/cria o handle e redireciona para a URL canonica.
- `/perfil/blocos` e o editor de blocos do perfil (autenticado). Declarada **antes** de `/:handle` no `app-routing-module`, senao "perfil" seria capturado como handle.
- `/:handle` e a pagina publica do perfil. Aceita `?editor=1`, que faz o dono entrar direto no modo de edicao inline (usado pelo ajuste de imagem, ver "Pagina Editar Blocos"). As rotas de produto sao reservadas e nao podem ser handles.
- Toda mudanca de rota deve iniciar no topo. A pagina publica observa mudancas de `handle` e descarta respostas de requisicoes antigas para nao manter o perfil anterior na tela.

### Mapa de paginas

| Pagina/rota | Finalidade | Comportamentos e dados importantes |
|---|---|---|
| **Inicio** (`/`) | Apresentar ofertas de interesse imediato. | Banner de melhor oferta, carrosseis de Jogos Monitorados, plataforma favorita quando definida, descontos em jogos, descontos em DLCs, mais vendidos e gratuitos. O conteudo geral nunca deve desaparecer ao selecionar uma plataforma favorita. |
| **Catalogo** (`/catalogo`) | Explorar todo o catalogo. | Scroll infinito; filtros de tipo, plataforma, desconto minimo e faixa de preco; ordenacao por dropdown customizado. As preferencias preenchem os filtros iniciais, mas o usuario pode mudar tudo manualmente. |
| **Detalhe do jogo** (`/jogo/:slug`) | Comparar lojas e reunir informacoes do jogo. | Hero com capa, melhor preco, botao `Atualizar precos`, acoes pessoais, tabela de ofertas e abas Precos/Sobre/Review/Conquistas quando ha dados. Review separa o resumo e as avaliacoes recentes reais da Steam do sistema proprio do Oferta Games. |
| **Busca** | Encontrar jogos, lojas e categorias pela topbar. | Sugestoes devem navegar diretamente para o jogo escolhido; a mudanca de URL precisa recarregar o detalhe mesmo quando o usuario ja esta em outro detalhe. |
| **Jogos Monitorados** (`/monitorados`) | Listar jogos acompanhados por preco. | Usa a tabela `favorites` e os endpoints `/api/favorites`. E diferente de favoritos pessoais do perfil. A rota antiga `/favoritos` somente redireciona para aqui. |
| **Mais vendidos** | Mostrar jogos relevantes/populares. | Usa rank ITAD e deve respeitar as mesmas regras de filtro de conteudo nao-jogo, DLC e lojas bloqueadas. |
| **Gratuitos** | Mostrar ofertas com preco zero. | Itens com link invalido ou filtrados por `JogosBloqueados` nao devem aparecer. |
| **Login** | Autenticar por Supabase Auth. | Nao usa sidebar nem topbar. Depois do login, a navegacao volta ao fluxo normal do aplicativo. |
| **Configuracoes** (`/configuracoes`) | Centralizar opcoes da conta. | Abas separadas: Conta, Conexoes, Preferencias e Privacidade. Nao misturar assuntos entre abas. Preferencias afetam home/catalogo; Privacidade afeta o perfil publico; Conexoes concentra Steam e Xbox. |
| **Perfil proprio** (`/perfil` -> `/:handle`) | Personalizar e visualizar o perfil do usuario. | `/perfil` redireciona para o handle canonico. O dono pode editar bio, foto e pedir atualizacao Steam ali; ordem/visibilidade/tamanho dos blocos ficam em `/perfil/blocos`. A pagina canonica e a mesma que visitantes veem, com controles extras apenas para o dono. |
| **Editar Blocos** (`/perfil/blocos`) | Organizar os blocos do perfil. | Aberta pelo botao "Editar perfil" do cabecalho. Lista reordenavel com toggle Ativo/Oculto, menu por bloco (tamanho, visualizacao, cores, conteudo de texto/links) e Biblioteca de Blocos. A aba "Visualizar Perfil" e uma previa real do rascunho, renderizando o proprio `app-public-profile`. |
| **Perfil publico** (`/:handle`) | Compartilhar biblioteca e perfil gamer. | Respeita privacidade geral e dos dados escolhidos. Mostra uma faixa fixa com biblioteca, horas, conquistas desbloqueadas, jogos platinados (so quando ha algum) e icones das plataformas conectadas; abaixo, mostra Resumo, Jogos favoritos, Colecoes e Biblioteca quando liberados. Nunca mostra e-mail, UUID, Jogos Monitorados ou controles de edicao a visitantes. |
| **Administracao de coleta** (`/admin/coleta`) | Acompanhar e disparar jobs internos. | Exclusiva do UID administrador. Abas horizontais agrupam os 7 jobs (Precos e Steam / Detalhes e Conquistas / Instant Gaming), cada uma com seus cards de status e sua propria fila; permite disparar coleta manual em segundo plano, mas nao substitui o scheduler. |

### Estados de erro e 404 (10/08/2026)

- `app-load-error` (`components/load-error/`): componente reutilizavel de erro de carregamento (`message` + evento `retry`), reaproveita o estilo global `.empty-state` com um botao "Tentar novamente". Usado em Catalogo, Home (bloco de destaques), Gratuitos, Mais Vendidos e Busca — as 5 paginas que carregam a lista principal via uma chamada HTTP unica. Antes, o `error:` do `.subscribe()` so desligava o loading sem mostrar nada, deixando a tela vazia sem explicacao quando a API falhava.
- Rota coringa (`**`) renderiza `pages/not-found/` (404 com link pra Home) em vez de redirecionar silenciosamente pra `/`. A rota `:handle` (perfil publico) continua casando primeiro com qualquer path de 1 segmento — o 404 real só é alcançado por paths com mais de 1 segmento que não bateram em nenhuma rota anterior.

### Componentes globais

- **Sidebar:** navegacao principal. O item de monitoramento deve se chamar **Jogos Monitorados** e usar o icone correspondente, nunca o de favoritos pessoais.
- **Topbar:** busca global, alternancia de tema, notificacoes e menu da conta. Ao clicar em Perfil, deve resolver o handle do usuario e navegar diretamente para `/:handle`, nunca permanecer em `/perfil`.
- **Notificacoes:** sino da topbar; lista quedas de preco de Jogos Monitorados, permite marcar como lida ou remover e mostra badge de nao lidas.
- **Card de jogo:** exibe capa, desconto, preco, loja e plataforma quando conhecidos. O icone de acao nele monitora preco, nao adiciona aos favoritos pessoais.

### Home, catalogo e monitoramento

- A home mantem conteudo geral misturado. Se houver plataforma preferida, cria uma secao adicional **Jogos da sua plataforma favorita** abaixo de Jogos Monitorados, sem esconder o restante.
- Preferencias de plataforma, ocultar DLC, desconto minimo e preco maximo preenchem inicialmente os filtros do catalogo; o usuario ainda pode altera-los.
- **Lojas preferidas** (22/08/2026): checkboxes em Configuracoes > Preferencias (`STORE_FILTER_OPTIONS` em `services/store-brand.ts`, chaves espelhando `LojasCatalogo` no backend). Sem nenhuma marcada, mostra ofertas de todas as lojas; com alguma marcada, o Catalogo aplica automaticamente ao abrir (`stores=` em `GameService.getGames`). Diferente do filtro de Plataforma, nao tem controle proprio dentro da pagina do Catalogo — so e definido nas Configuracoes.
- **Historico de preco (22/08/2026)**: grafico na aba Precos da pagina do jogo (abaixo das abas, acima da tabela de ofertas), so aparece com 2+ pontos de historico — ver "Historico de precos" em "Coletas e Atualizacao de Catalogo".
- Cards e detalhe usam o icone `jogos-monitorados.png` para monitoramento de preco. Nunca usar o icone de favorito pessoal nesse fluxo.
- **Colecoes ("Minhas Listas")** sao uma feature separada dos favoritos, na aba **Colecoes** do perfil: grupos nomeados criados pelo dono, com um jogo podendo estar em varias colecoes (N:N). Diferente dos favoritos, aceitam jogos do catalogo que o usuario **nao possui** (ex: "Quero jogar em 2027") alem de jogos da biblioteca Steam. Visibilidade pelo toggle proprio **Mostrar colecoes** em Configuracoes > Privacidade. O dono usa o modo **Organizar** da aba para criar, renomear e excluir; fora dele a aba so exibe as listas.
- Favoritos pessoais sao outra funcionalidade, mostrada no perfil e biblioteca Steam. Na aba **Jogos favoritos**, o dono entra no modo **Organizar** (botao no cabecalho da aba) para reordenar por arrastar e soltar e remover itens; fora desse modo os controles ficam escondidos e os cards navegam normalmente. A ordem e salva automaticamente via `PUT /api/profile-favorites/ordem`; visitantes so visualizam. A ordem manual e unica por usuario e vale para favoritos de catalogo e Steam juntos.

### Perfil publico e privado

- Perfis novos sao publicos por padrao. O dono pode tornar o perfil privado em Configuracoes > Privacidade.
- E-mail, UUID, Jogos Monitorados e dados de autenticacao nunca sao publicos.
- O dono escolhe a exposicao de horas, conquistas, biblioteca, favoritos pessoais, colecoes e atividade recente. O backend filtra a resposta publica.
- O dono na propria URL canonica ve controles de avatar, banner, bio, atualizacao e modo de edicao; visitantes nao veem esses comandos.
- O avatar e o banner do topo do perfil sao salvos no Storage com zoom e posicao persistidos para todos verem o mesmo enquadramento. O ajuste usa o mesmo editor em modal dos blocos de imagem: arrastar com mouse/touch para posicionar e scroll/pinca para zoom, com folga minima de 115% para sempre permitir arrastar em qualquer direcao, calculado em pixels reais (imagem x quadro) tanto no modal quanto na exibicao final. Os botoes "Trocar foto" e "Trocar banner" só aparecem no modo de edicao do perfil. O modal de ajuste mostra o tamanho recomendado (banner: 1600×400px).
- **Bug corrigido (01/08/2026)**: `avatarDisplayStyle`/`bannerDisplayStyle` (o avatar/banner exibidos na pagina, visiveis atras do backdrop semi-transparente/desfocado do modal de ajuste) usavam as mesmas variaveis (`avatarZoom`/`avatarPositionX/Y`, `bannerZoom`/`bannerPositionX/Y`) que o rascunho ao vivo do crop, entao arrastar pra reposicionar fazia a imagem de fundo "piscar" atras do modal a cada movimento do mouse. Agora esses dois metodos sempre leem o zoom/posicao ja salvos em `profile.*`, nunca o rascunho.
- A primeira sincronizacao Steam gera somente os resumos. Nas posteriores, novos jogos e conquistas viram atividades individuais.
- A atividade recente e publica por padrao, salvo escolha do dono na privacidade.
- **Favorito da Steam leva pro nosso catalogo, nao pra loja da Steam** (12/09/2026). `favoriteUrl()` usa `/jogo/{slug}` quando o item tem slug e cai na Steam quando nao tem; `RepositorioFavoritosPerfil.listarPorUsuario` montava os favoritos Steam com `slug` null fixo, entao **nenhum** favorito Steam ficava no site. Agora cruza os `app_id` com `games.steam_app_id` no banco do catalogo (mesmo cruzamento que a biblioteca ja fazia via `RepositorioJogos.buscarSlugsPorSteamAppIds`). Feito no repositorio, nao no `ServicoPerfis`, pra valer nos dois consumidores: o payload do perfil publico e o `GET /api/perfis/favoritos` que o dono usa. `steamAppId` continua preenchido, entao o card segue mostrando horas e percentual de conquistas. Favorito de jogo que nao existe no catalogo continua indo pra Steam (e o caso de Path of Exile 2 e GTA V Legacy, por exemplo).

### Redesign visual do perfil (11-12/09/2026)

Baseado em dois mockups do Figma, evoluindo o que existia em vez de reescrever — os componentes, tokens e dados sao os mesmos.

- **Cabecalho** (`.public-hero`): o banner cobre o card inteiro com um gradiente escuro a 95deg pra garantir contraste do texto (antes era uma faixa 5:1 separada); avatar de 124px com anel ciano e glow discreto; acoes do dono (`.hero-actions`) num canto: "Editar perfil" (vai pra `/perfil/blocos`), engrenagem pra `/configuracoes` e ↻ de atualizar. Os dois botoes redondos so tem glifo, sem rotulo, entao levam tamanho proprio (20-21px) — herdando o `font-size` do `.hero-btn` ficavam ilegiveis.
- **Faixa de estatisticas** (`.profile-stat-strip`): um card unico com celulas divididas, cada uma com o icone num tile arredondado. Afinada de 88px pra **60px** de altura em 12/09/2026 (numero 19px, rotulo 11px, tile 32px): e um resumo de leitura rapida e competia com o cabecalho. Os numeros usam `font-variant-numeric: tabular-nums` pra faixa nao mudar de largura quando um valor passa de 3 pra 4 digitos. "Plataformas conectadas" usa a mesma estrutura das outras celulas (valor em cima, rotulo embaixo), com os icones no lugar do numero — antes era o unico item invertido e ficava fora da linha de base.
- **Abas** com icone SVG inline (nao PNG): o icone precisa acompanhar a cor da aba ativa/inativa, e so `currentColor` faz isso sem duplicar arquivo.
- **Paineis** com icone no titulo e link "Ver todos ›" no canto.
- O layout inicial de quem nunca mexeu nos blocos coloca Platinados (`largo`, 8/12) e Favoritos (`pequeno`, 4/12) lado a lado, fechando uma linha do grid de 12 colunas.

`public-profile.scss` passou do budget de 26 kB (29,2 kB) e o build emite aviso — igual `game-detail.scss`, que ja estourava antes. Aviso, nao erro.

### Pagina "Editar Blocos" (/perfil/blocos) (11/09/2026)

O botao **Editar perfil** do cabecalho do perfil nao abre mais o modo de edicao inline: ele leva pra `/perfil/blocos` (`EditarBlocos`, lazy, atras do `authGuard`). A pagina lista os blocos numerados com alca de arraste (`cdkDropList`), setas cima/baixo, toggle **Ativo/Oculto** (`bloco.visivel`) e um menu `⋮` com tamanho, cores de fundo/texto, "voltar ao padrao" e remover. Blocos de **texto** e **links** editam titulo e conteudo no proprio menu. Do lado direito ficam a **Biblioteca de Blocos** (um card por tipo, desabilitado quando o tipo e unico e ja esta em uso, ou quando nao ha dado pra mostrar) e o card de dica. Os metadados de tipo (titulo padrao, descricao, icone, tipos unicos, layout inicial) vivem em `services/perfil-blocos.ts`, compartilhados com o perfil — `PublicProfile.defaultBlocks()` so delega pra `blocosPadrao()`.

O menu `⋮` de um bloco de jogos tambem tem **Visualização: Lista compacta / Cards com capa** (12/09/2026), persistida em `profile_blocks.view_mode`. Hoje so o bloco de favoritos tem as duas visualizacoes desenhadas (`TIPOS_COM_VISUALIZACAO` em `services/perfil-blocos.ts`); os demais blocos de jogos so existem como card. O limite de itens acompanha o modo: em cards vale o `previewLimit` normal (1/4/6/8), em lista cabem mais (5/6/8/10), porque cada item e uma linha e nao uma capa.

**Ocultar um bloco tira ele do perfil pra todo mundo, inclusive o dono** (12/09/2026), mesma regra do toggle da wishlist — o dono precisa ver o perfil como os outros veem. Filtrado nos dois lados: `ServicoPerfis.blocosPublicos` no backend e `PublicProfile.visibleBlocks` no frontend. Dois bugs foram corrigidos juntos aqui: (1) `RepositorioBlocosPerfil.substituir` gravava `visible = true` fixo, entao o toggle nunca persistia; (2) depois de persistir, nada filtrava na exibicao, e o bloco oculto continuava desenhado.

Por isso o editor **le os blocos de `GET /me/blocos`, nao do payload publico** (`perfis.publico`): o publico agora filtra os ocultos, e carregar dali faria o proximo "Salvar" — que substitui a lista inteira — apagar de vez o bloco que o dono so quis esconder. O `publico()` continua sendo chamado, mas so pra alimentar as regras de "tem dado pra mostrar?" da Biblioteca de Blocos (`temColecaoWishlistSteam`, `conquistasRecentes`, `biblioteca`).

A aba **Visualizar Perfil** e uma previa de verdade, nao um link: renderiza o proprio `app-public-profile` com dois inputs novos, `previewHandle` e `previewBlocos`. Nesse modo o componente ignora a rota, nao mexe no SEO, nao consulta `/me` e fica com `isOwner = false` — o dono ve exatamente o que um visitante veria, ja com o rascunho nao salvo (blocos ocultos somem, tamanhos novos valem). A previa fica **fora** do container de `max-width: 1280px` da pagina: o perfil real ocupa a largura inteira do `<main>`, e limitar a previa encolhia os cards e criava sobra vertical nos blocos, ou seja, a previa mentia sobre o resultado.

O modo de edicao inline descrito abaixo continua existindo, mas so pro ajuste de imagem dos blocos (que exige o bloco ja renderizado pra arrastar/dar zoom). Ele e aberto pelo item "Ajustar imagem no perfil" do menu `⋮` de um bloco de imagem, que navega pra `/{handle}?editor=1` — o `queryParam` `editor=1` faz o perfil entrar direto em `startLayoutEdit()` quando quem abre e o dono.

### Editor de perfil (modo inline)

> **Leia antes:** desde 11/09/2026 este modo **nao e mais o caminho normal**. O botao "Editar perfil" leva pra `/perfil/blocos` (secao acima), e o inline so e alcancado por `/{handle}?editor=1`, usado pelo ajuste de imagem de bloco. O texto abaixo descreve o que ele faz quando aberto; parte das funcoes (ordem, visibilidade, tamanho, visualizacao, cores, conteudo de texto/links) tambem existe — e e o caminho preferido — na pagina nova. O que **so** existe aqui: recorte/enquadramento de imagem, gradiente de fundo e o atalho de cor global. Mover o recorte de imagem pra um modal na pagina nova e o passo que permitiria apagar este modo por inteiro.

O modo de edicao permite reorganizar blocos por arrastar e soltar, mudar tamanho, remover e adicionar blocos. Ele existe somente na aba **Resumo**: ao abrir, as abas Jogos favoritos e Biblioteca ficam indisponiveis e os comandos internos Gerenciar/Ver biblioteca somem para evitar navegacao acidental. A faixa de estatisticas logo abaixo do cabecalho e fixa, portanto nao entra no editor: mostra jogos na biblioteca, horas jogadas, somente conquistas desbloqueadas e icones das plataformas conectadas. A barra "Modo de edicao" fica fixa na parte inferior da tela (nao rola com a pagina), com os botoes Cancelar/Salvar visualmente destacados a direita, separados do seletor de adicionar bloco. Tipos suportados:

- paineis de favoritos pessoais, biblioteca, atividade, platinados (01/08/2026), wishlist da Steam, conquistas recentes e mais jogados (11/09/2026);
- blocos personalizados de texto, imagem e links.

**Conquistas recentes** (11/09/2026) vem pronto do backend em `PerfilPublico.conquistasRecentes` (`ServicoConexoesSteam.conquistasRecentes`, que cruza `steam_library_games` com nome/icone do catalogo). Limite proprio por tamanho (pequeno 3, medio 5, largo 8, completo 12), porque cada item e uma linha compacta e nao um card. Visibilidade pro visitante segue `mostrarConquistas()`. **Mais jogados** nao tem dado proprio: e `profile.biblioteca` ordenada por horas, e segue `mostrarBiblioteca()`.

**Adicionar bloco e um dropdown, nao uma fileira de botoes (11/09/2026)**: um so seletor "Adicionar" (mesmo componente `.editor-custom-select` dos outros selects do editor) abre um menu — antes eram 7 botoes lado a lado, cada vez mais apertados a cada tipo novo (a wishlist teria sido o 8°). O menu abre **pra cima** (`.editor-select-menu.opens-up`, mesma tecnica ja usada pelo seletor de cor global 🎨): a barra "Modo de edicao" e fixa no rodape, um menu abrindo pra baixo sairia da tela. Opcoes ja adicionadas (favoritos/biblioteca/atividade/platinados/wishlist sao unicos por perfil) ficam desabilitadas em vez de somem, pra o usuario entender que ja existem; "Lista de Desejos (Steam)" so aparece na lista quando `profile.temColecaoWishlistSteam` e verdadeiro (dono tem Steam conectada com wishlist populada).

O painel **Platinados** e derivado da biblioteca (`profile.biblioteca` filtrado no frontend por `conquistasTotal > 0 && conquistasDesbloqueadas >= conquistasTotal`, com prioridade pra `platinumPosition` quando definida e senao ordenado por horas jogadas). So pode existir um bloco desse tipo por perfil. Visibilidade pro visitante depende do toggle **Mostrar biblioteca** (nao existe toggle proprio), ja que os dados vem de la; `ServicoPerfis.blocosPublicos` filtra o bloco do mesmo jeito. Os blocos de **Favoritos** e **Platinados** podem ser reordenados por arrastar direto no proprio bloco (dentro do modo de edicao de layout), sem precisar entrar em "Gerenciar" — reaproveita o `cdkDropList`/`dropFavorite` da aba Gerenciar pros favoritos; pros platinados existe `PUT /api/conexoes/steam/platinados/ordem` + tabela `profile_platinum_order` dedicada (nao reaproveita coluna em `steam_library_games` porque essa tabela e substituida inteira a cada sync da Steam). A ordem dos platinados so persiste pra itens Steam; itens Xbox no mesmo bloco reordenam visualmente mas nao salvam entre sessoes.

O painel **Wishlist** (11/09/2026) e derivado da colecao de sistema da wishlist (ver "Colecoes de sistema" na API HTTP) — sem estado proprio, `wishlistPreview()` so pega `profile.colecoes.find(c => c.origemSistema)`. Sem reordenacao manual (segue a ordem da wishlist real da Steam). Visibilidade em `ServicoPerfis.blocosPublicos`: precisa de `mostrarColecoes()` (ou dono) **e** `mostrarWishlistSteam()` — esse ultimo esconde de todo mundo, inclusive o dono, mesma regra da aba Colecoes.

Todos os paineis de dados (favoritos, biblioteca, atividade, platinados e, desde 11/09/2026, wishlist, conquistas recentes e mais jogados) tem titulo editavel (01/08/2026), igual aos blocos personalizados de texto/links: campo de titulo no editor com o mesmo limite por tamanho (`maxTitleLength`), mostrando o nome padrao (`defaultBlockTitle`) como placeholder quando vazio. Sem titulo customizado, cai no nome padrao de sempre ("Jogos favoritos", "Biblioteca", "Atividade recente", "Platinados"). Nao ha validacao especifica no backend alem do limite de tamanho generico de `salvarBlocos` (mesma regra dos demais tipos).

Cada bloco pode usar fundo padrao, cor solida, gradiente ou imagem, alem de cor de texto hexadecimal livre. Cor solida e texto aceitam seletor visual e digitacao direta de `#RRGGBB`; o gradiente e montado visualmente por duas cores, sem exigir CSS. A opcao de texto fica dentro do menu de fundo e altera somente o conteudo do card, nunca os controles do editor. A imagem de fundo e escolhida por um comando explicito e enviada ao bucket `avatars`; nao existe privacidade por bloco. Blocos personalizados de imagem abrem um editor de enquadramento antes de salvar, exibido como modal grande sobre um fundo escurecido (nao mais embutido na lista de blocos), com pre-visualizacao ampla; o ajuste e feito arrastando a imagem com o mouse/touch para posicionar e girando o scroll (ou pinca no mobile) para dar zoom, sem sliders. O zoom minimo aplica uma pequena folga (115%) sobre o enquadramento padrao para garantir espaco de arraste em qualquer direcao, independente da proporcao da imagem enviada. O enquadramento (zoom e posicao) e calculado com base no tamanho real da imagem e do quadro (nao mais via `object-position` + `transform: scale`, que ficava preso na mesma janela de corte e podia travar um dos eixos do arraste); o mesmo calculo e usado tanto no editor quanto na exibicao final do bloco no perfil, garantindo que o resultado salvo seja igual ao que o dono ajustou. Esses dados sao persistidos junto da URL em formato compativel com blocos antigos que guardavam somente a URL. O campo "Usar URL" sempre abre vazio, mesmo quando o bloco ja tem uma imagem: ele nunca preenche com a URL interna do Supabase Storage, que nao deve ser exposta ao usuario. O editor de links separa titulo e lista de URLs no mesmo padrao visual dos demais campos.

Blocos de texto e links respeitam limites conforme o tamanho escolhido: pequeno (`42` caracteres de titulo e `180` de conteudo), medio (`64` e `420`), largo (`88` e `800`) e completo (`120` e `1400`). O conteudo aplica quebra de palavras longas para nunca vazar horizontalmente do card.

Os tamanhos sao composicoes diferentes, e nao apenas escala. Nos blocos de jogos, o pequeno mostra 1 card por linha, o medio 2, o largo 3 e o completo 4; os cards crescem verticalmente quando houver mais itens. A quantidade de itens exibida no bloco vem sempre do tamanho escolhido (pequeno 1, medio 4, largo 6, completo 8) e nao e configuravel separadamente. Favoritos pessoais e biblioteca usam cards visuais com capa, titulo e dados relevantes. Favoritos Steam usam a capa horizontal oficial `header.jpg`, igual aos cards da biblioteca, e exibem plataforma, horas jogadas e percentual de conquistas; favoritos do catalogo exibem a capa e o menor preco conhecido. O bloco de Biblioteca tem o comando **Ver biblioteca** no canto superior direito e abre a aba completa, que permite busca, ordenacao e favoritar jogos Steam mesmo quando eles nao existem no catalogo.

Os dropdowns de tamanho e fundo devem seguir o mesmo padrao visual do filtro de ordenacao do catalogo, nao usar `select` nativo. Links personalizados usam uma linha por item no formato `Nome - endereco.com` ou `Nome - https://url`; enderecos sem protocolo recebem `https://` automaticamente e devem abrir como links reais. No perfil publico, cada link renderiza como um card empilhado (nao mais uma pill inline), com o nome a esquerda e um icone de link externo a direita.

### Steam e Xbox

- Steam esta funcional por OpenID: nao solicitar Steam ID ou URL manualmente.
- Sincroniza biblioteca, horas totais/por jogo e conquistas gradualmente via Steam Web API.
- A aba Biblioteca busca, ordena por tempo/nome/conquistas e permite favoritar itens Steam ausentes do catalogo.
- **Wishlist Steam (10/09/2026)**: coletada automaticamente numa colecao de sistema, "Lista de Desejos (Steam)" — ver "Colecoes de sistema" abaixo.
- **Xbox implementado (06/08/2026)** via OpenXBL (xbl.io), usando o recurso "Xbox App" deles (nao a API key pessoal, que so serve pra consultar dados publicos de qualquer gamertag com uma chave do dono do site). O fluxo:
  1. O usuario cria um "Xbox App" no painel do OpenXBL — isso exige registrar tambem um app no Azure AD (Application/Client ID + Client Secret, tipo de conta "Somente contas pessoais", redirect URI `https://api.xbl.io/app/callback`) e colar essas credenciais de volta no OpenXBL. So precisa ser feito uma vez; gera a `app_key` ("Public Key") usada em `XBL_APP_KEY`.
  2. No site, o botao "Conectar" em Configuracoes > Conexoes busca a `app_key` (`GET /api/conexoes/xbox/app-key`, publico) e redireciona o navegador pra `https://api.xbl.io/app/auth/{app_key}` — o usuario loga com a conta Microsoft dele la.
  3. A OpenXBL redireciona de volta pra `https://ofertagames.vercel.app/configuracoes?code=...`. Como o usuario continua logado no site (sessao Supabase no localStorage sobrevive ao redirect), o frontend so chama `POST /api/conexoes/xbox/concluir` com esse `code` e o bearer token normal — sem precisar de uma tabela de "state"/CSRF como o OpenID do Steam usa, porque a correlacao com o usuario acontece direto pelo bearer.
  4. O backend troca o `code` por xuid/gamertag/avatar/gamerscore/token (`POST https://api.xbl.io/app/claim`) e salva em `xbox_connections`. O `token` devolvido e usado depois pra chamar a API da OpenXBL em nome desse usuario.
  - **Biblioteca**: `GET https://api.xbl.io/v2/player/titleHistory` devolve a biblioteca inteira numa unica chamada, ja com progresso de conquistas por jogo (`currentGamerscore`/`totalGamerscore`, mapeados pros mesmos campos de conquistas que a Steam usa — a OpenXBL as vezes devolve `totalAchievements=0` mesmo com progresso, mas o gamerscore bate certo com o `progressPercentage` que ela mesma reporta). Isso evita bater no rate limit apertado da OpenXBL (60 req/5min no free tier), que uma chamada por jogo estouraria rapido.
  - **Minutos jogados**: nao vem no `titleHistory` (so tem `lastTimePlayed`). Precisa de `POST https://api.xbl.io/v2/player/stats` com um item `{name: "MinutesPlayed", titleId}` por jogo — mas aceita todos em lote numa unica chamada (dividido em blocos de 200), entao ainda e barato. Esse endpoint **nao aparece na documentacao renderizada** (`api.xbl.io/docs`, um app JS que nao lista as rotas via scraping simples); foi encontrado no spec OpenAPI bruto do repositorio publico `github.com/OpenXBL/Docs`. Testado ao vivo e confere exatamente com o tempo jogado real.
  - **Sincronizacao e sempre upsert-only**: `xbox_library_games` nunca sofre DELETE, so INSERT/UPDATE por `title_id`. Isso foi uma decisao deliberada pra nunca apagar dados que o perfil do usuario ja tenha, mesmo que a OpenXBL pare de devolver algum titulo (ex: usuario escondeu do historico). **Correcao (31/08/2026)**: este trecho dizia que a Steam "substitui a biblioteca inteira a cada sync" — nao substitui mais. Isso valeu ate 06/08/2026, quando o DELETE em cascata do resync apagou favoritos e colecoes de usuarios; desde a correcao daquele incidente `steam_library_games` tambem e upsert-only (`INSERT ... ON CONFLICT (user_id, app_id) DO UPDATE`), sem nenhum DELETE no codigo. O metodo, que se chamava `substituirBiblioteca`, foi renomeado para `RepositorioConexoesSteam.salvarBiblioteca` na mesma data, ja que nao substitui nada.
  - **Biblioteca combinada**: `ServicoPerfis` mescla `steam.biblioteca()` + `xbox.biblioteca()` numa lista so, cada item com o campo `plataforma` ('steam'|'xbox'). `plataformasConectadas` passa a incluir `xbox` quando conectado. Os agregados do topo do perfil (total de horas, conquistas gerais) continuam vindo so da Steam — so a lista de biblioteca e o contador de jogos somam as duas plataformas. A aba Biblioteca ganha um filtro de plataforma (Steam/Xbox/Todas), visivel so quando o usuario tem mais de uma conectada.
  - **Limitacoes conhecidas**: favoritar, o link "Ver na [loja]" e a capa alternativa em cascata continuam Steam-only (favoritar por appId colidiria com titleId do Xbox, que sao so numeros indistinguiveis entre plataformas). Reordenar platinados por arrastar tambem so persiste pra itens Steam (o endpoint de ordem e especifico da Steam); itens Xbox reordenam na tela mas nao persistem entre sessoes ainda.

### Conquistas sao coletadas sob demanda, nao por varredura (01/09/2026)

A varredura agendada (`AgendadorColetas.coletarConquistasCatalogo`) foi **removida**. Ela percorria os 39 mil jogos com `steam_app_id` a cada 2 minutos; com a fila ja esgotada, gastava CPU e chamadas a Steam pra nao achar nada. `game_achievements` era a maior tabela do banco (121 MB de 361 MB) e completar a fila levaria o Supabase a ~590 MB, acima da cota de 0,5 GB do plano free.

> **Nota de 12/09/2026:** o argumento de cota nao vale mais — o catalogo saiu do Supabase e mora num Postgres proprio na VM, sem cota (ver "Migracao do catalogo pra fora do Supabase"). Medido hoje na VM: banco do catalogo **443 MB**, `game_achievements` **118 MB**, disco em 17 GB de 48 GB (35%). Espaco deixou de ser o gargalo; readicionar a varredura hoje e uma decisao de CPU (**1 vCPU**, que ja caiu sob carga real) e de volume de chamadas a Steam. **Continua desligada** por enquanto: os dois gatilhos sob demanda abaixo cobrem os casos que importam. O comentario no `AgendadorColetas` ainda cita a cota do Supabase — e a razao historica, nao a atual.

`ServicoSincronizacao.sincronizarRodadaConquistasCatalogo` continua existindo e o botao **"conquistas-catalogo" do painel de admin ainda dispara a varredura manualmente**, pra quando fizer sentido (por exemplo, depois de uma entrada grande de jogos novos no catalogo).

**O gatilho e abrir a pagina do jogo**, via `GET /api/games/{slug}/conquistas`, que a pagina ja chamava. Nao e o botao "Atualizar precos": a aba "Conquistas" so aparece quando ja ha conquistas gravadas (`*ngIf="temConquistas"`), entao quem abre um jogo sem elas nao ve aba nenhuma e nao teria motivo pra imaginar que atualizar precos faria uma surgir — a coleta so aconteceria por acidente.

**Cada jogo e consultado uma vez.** Quem garante sao *duas* condicoes, ambas necessarias, em `listarPendentesConquistas`/`contarPendentesConquistas`/`precisaColetarConquistas`:

1. `achievements_checked_at IS NULL` — o carimbo e gravado quando a Steam devolve **esquema vazio**, e e o que tira da fila os 25.750 jogos que a Steam confirmou nao ter conquista nenhuma.
2. `NOT EXISTS (SELECT 1 FROM game_achievements WHERE game_id = g.id)` — e o que tira da fila o jogo coletado **com sucesso**, porque `RepositorioJogos.salvarConquistas` **nao** carimba `achievements_checked_at` no caminho de sucesso.

**Nao remova a condicao 2 achando que e redundante.** Isso foi feito em 12/09/2026 e quebrou: 13.614 jogos que ja tinham conquistas voltaram a ser considerados pendentes, e como `precisaColetarConquistas` usa a mesma condicao, **toda visita a pagina de um jogo ja coletado disparava uma nova busca do esquema na Steam**. Revertido no mesmo dia.

### Recoleta de jogo live-service (12/09/2026)

Jogo que recebe conquista nova depois da nossa coleta (Dead by Daylight e afins) ficava desatualizado pra sempre: as duas condicoes acima, de proposito, nunca trazem de volta um jogo ja coletado. O sintoma aparecia no bloco **Conquistas recentes** do perfil — a conquista desbloqueada nao tinha linha em `game_achievements`, entao vinha sem icone e com nome derivado do `api_name` ("New achievement 334 3"). Medido na epoca: DBD tinha 303 conquistas no catalogo e 311 no esquema da Steam.

O gatilho e `ServicoConexoesSteam.conquistasRecentes`: quando alguma conquista recente **nao tem linha no catalogo**, ele chama `ServicoConquistasSobDemanda.agendarPreenchimentoCompleto(appIds)`, que roda `ServicoCatalogo.preencherTudoDoJogo` — exatamente o que o botao de admin "Preencher tudo agora" faz (metadados Steam forcados + detalhes + conquistas). Refaz o jogo inteiro e nao so as conquistas porque quem ganhou conquista nova normalmente tambem tem capa/descricao/review novos.

Nao passa pela fila de `listarPendentesConquistas` — aquela fila e so pra jogo que nunca foi coletado. Dois freios, porque o gatilho e leitura de perfil e repete muito:

- a trava `emAndamento` (`Set<Long>` concorrente, compartilhada com a coleta por pagina de jogo), pra visitas simultaneas nao duplicarem o trabalho;
- **intervalo minimo de 12h por jogo** (`INTERVALO_MINIMO`, cache em memoria por `steam_app_id`). Necessario porque conquista oculta/removida nao existe nem no esquema da Steam: sem o intervalo, jogo assim seria refeito em cada visita ao perfil, pra sempre. Reiniciar o backend limpa o cache e libera um preenchimento extra por jogo — barato e aceitavel.

Roda no `executorColetaManual`, fora da requisicao, e nunca lanca: e efeito colateral de uma leitura.

**Nome da conquista tambem tinha bug** (12/09/2026): `GetPlayerAchievements` ja e chamado com `l=brazilian` e devolve o nome oficial no campo `name`, mas `ClienteSteamWeb.buscarConquistas` ignorava o campo e derivava o titulo do `api_name` **sempre**. Conquista de `api_name` generico virava titulo lixo. Agora usa `name` e so cai no derivado se a Steam nao mandar. O titulo fica gravado em `steam_user_achievements.title` e e reescrito na proxima sincronizacao (o upsert tem `SET title = EXCLUDED.title`); na exibicao, `conquistasRecentes` prefere o `display_name` do catalogo, entao o preenchimento completo tambem conserta o nome sem depender de resync.

**A coleta roda fora da requisicao** e a resposta traz `coletando: true` quando a lista veio vazia *porque a coleta acabou de ser disparada* — e nao porque o jogo nao tem conquistas. So nesse caso o frontend reconsulta, uma vez, 5s depois (`game-detail.ts`, `carregarConquistas`). Sem esse sinal a alternativa seria tentar de novo em todo jogo de lista vazia: uma requisicao desperdicada por visita, na maioria dos jogos.

**Efeito colateral bom do SSR:** como o servidor renderiza a pagina e nisso ja chama `/conquistas`, a coleta costuma comecar antes do navegador pedir os dados — medido, a aba aparece **no primeiro frame, com uma unica requisicao**. A nova tentativa vira rede de seguranca pra quando a Steam demora, nao o caminho normal.

Pra testar de novo: apagar as linhas de `game_achievements` do jogo e zerar `achievements_checked_at`. Sao dados derivados da Steam, recolhidos sozinhos na visita seguinte.

## Seguranca

### RLS: a chave do Supabase e publica, e o RLS e a unica barreira (01/09/2026)

A chave anon do Supabase e **publica por design** — vai no bundle do frontend e qualquer visitante extrai em segundos. Ela nao e segredo; quem protege os dados e o Row Level Security.

Ate 01/09/2026 o RLS estava **desligado nas 28 tabelas** do schema `public`. Verificado com a propria chave do bundle: dava pra **ler** `profiles`, `favorites`, `steam_connections`, `xbox_connections`, `steam_library_games`, `price_notifications` e `profile_collections`, e **escrever/apagar** em `favorites`, `profiles` e `price_history`. Um `DELETE` sem filtro apagaria os jogos monitorados de todos os usuarios, ou o historico de preco dos 110k jogos.

**Agora todas tem RLS ligado e nenhuma policy** (`backend-java/sql/20260901_rls_todas_tabelas.sql`). A ausencia de policy e o desenho, nao um passo esquecido:

- o frontend **nunca** acessa tabela direto — so `supabase.auth.*` (sessao) e `supabase.storage.*` (avatares); todo dado passa pelo backend Java. Nao existe caso legitimo de acesso pela API publica, entao negar tudo e o correto, e evita escrever policy por tabela (trabalhoso e sujeito a erro);
- o backend nao e afetado porque conecta por JDBC como `postgres`, dono das 28 tabelas, e **dono ignora RLS**. **Nunca usar `FORCE ROW LEVEL SECURITY`**: passaria a valer pro dono tambem e derrubaria a API inteira;
- o linter do Supabase passa a apontar `rls_enabled_no_policy` (nivel INFO) nas 28 tabelas. E esperado. O que nao pode reaparecer e o `rls_disabled_in_public`, que e ERROR.

Se algum dia o frontend precisar ler uma tabela direto, **a policy vem junto** — nao desligue o RLS.

O `storage.objects` ja estava correto desde antes: leitura publica do bucket `avatars`, escrita restrita a pasta do proprio usuario (`foldername[1] = auth.uid()`).

**Ao testar escrita no PostgREST:** um `204` **nao** prova que a escrita passou — ele responde 204 mesmo afetando 0 linhas. So `Prefer: return=representation` e conclusivo: devolve as linhas afetadas, ou `[]` se o RLS bloqueou.

### O que ja estava certo

- **Admin validado no servidor** (`ControladorAdministracao.exigirAdministrador`, por UID). O `adminGuard` do frontend e so cosmetico — quem protege e o backend.
- **Sem IDOR**: nenhum endpoint aceita `userId` vindo do cliente; e sempre derivado do token.
- **Sem SQL injection**: as interpolacoes em `comum/` usam constantes do codigo (alias literal, regex fixa), e `ordenarPor()` e um `switch` com `default`, entao `sort` arbitrario cai no padrao.
- `auth.users` (e-mails e hashes de senha) nao e exposta pelo PostgREST.
- Dependencias do frontend sem vulnerabilidade conhecida (`npm audit`).

### Limite de requisicoes e cache de token (01/09/2026)

**`FiltroLimiteRequisicoes`** — janela fixa de 1 minuto por IP, com dois tetos:

| | Limite/min | Por que |
|---|---:|---|
| Escrita (POST/PUT/PATCH/DELETE) | 30 | So parte de navegador real, entao pode ficar perto do uso humano |
| Leitura (GET/HEAD/OPTIONS) | 600 | **Precisa acomodar o SSR** |

O teto de leitura alto nao e frouxidao: o SSR na Vercel chama esta API para renderizar cada pagina, e todas essas chamadas saem de um punhado de IPs da Vercel. Um limite de navegador em GET derrubaria o site sob trafego normal — nao o atacante. Se o trafego crescer a ponto de o SSR encostar nos 600, a saida **nao** e aumentar o numero: e isentar o SSR com um cabecalho secreto compartilhado, ai sim apertando a leitura.

Detalhes que nao sao obvios:

- **Usa a ultima entrada de `X-Forwarded-For`, nao a primeira.** O Caddy *anexa* o peer real ao que veio na requisicao, entao um cliente consegue plantar valores no inicio da lista — mas nunca no fim. Ler a primeira entrada deixaria qualquer um trocar de identidade a cada request e furar o limite por completo. Coberto por teste.
- **A recusa repete o `Access-Control-Allow-Origin`** (so para origem que ja esta na lista permitida). O CORS do Spring e aplicado no handler, depois dos filtros, entao uma resposta cortada no filtro sairia sem ele e o navegador reportaria "erro de CORS" no lugar do 429 — escondendo o motivo real.
- **Nao protege login**: a autenticacao vai direto do navegador para o Supabase, sem passar pelo backend. Forca bruta de senha e limitada pelo Supabase, nao aqui.
- Estado em memoria, por instancia. Basta para uma VM so; com mais de uma, o limite efetivo viraria a soma e seria preciso contador compartilhado.

**Cache de validacao de token** (`ServicoAutenticacao`) — 60s, chaveado pelo **hash SHA-256** do token. Antes era uma chamada ao Supabase por requisicao autenticada.

- O hash existe porque o id do usuario em cache nao serve para se autenticar, mas o token sim: guardar so o hash evita manter credencial reutilizavel viva em memoria, onde um dump de heap a pegaria pronta.
- **So o sucesso e cacheado.** Falha nao entra de proposito: como toda falha e indistinguivel — inclusive "Supabase fora do ar" —, cachear negativo faria uma instabilidade de um segundo virar um minuto de usuarios deslogados. O custo e que token invalido sempre bate no Supabase; quem contem enxurrada disso e o limite de requisicoes, nao o cache.
- Risco aceito: um token continua valido por ate 60s depois de invalidado. E pequeno porque o token de acesso ja e um JWT de ~1h — sair da conta nao o revoga de imediato de qualquer forma. Subir muito esse valor inverte a conta.

### Pendencias conhecidas

- **Protecao de senha vazada desligada** no Supabase Auth — toggle no painel, cruza a senha escolhida com a base do HaveIBeenPwned. E do lado do Supabase, nao do codigo.
- **Rate limiting nao cobre login**, porque o login nao passa pelo backend (ver acima).
- **A pagina de perfil em branco no `ng serve`** (dev local): recarregar direto numa URL `/{handle}` (F5) renderiza so o esqueleto de loading, com o estado do componente correto e `cdr.detectChanges()` sem efeito — artefato de hidratacao do dev server. Reproduzido no HEAD sem nenhuma alteracao local, e **nao acontece em producao**. Pra testar local, entre por outra pagina e navegue clicando. Nao investigado a fundo por nao afetar producao.

## Estrutura de Codigo

```text
backend-java/
  Dockerfile
  pom.xml
  sql/
  src/main/java/com/ofertagames/backend/
    administracao/       status e disparo manual de coleta
    autenticacao/        Bearer token Supabase
    configuracao/        CORS e banco
    conexoes/            Steam OpenID + Xbox OAuth (OpenXBL) e sincronizacao
    descontos/           home e melhores descontos
    favoritos/           Jogos Monitorados
    favoritosperfil/     favoritos pessoais
    colecoesperfil/      colecoes ("Minhas Listas") de jogos
    instantgaming/       preco extra da Instant Gaming via scraping (sem API)
    jogos/               catalogo, busca, detalhe e refresh
    notificacoes/        alertas de preco
    perfis/              perfil publico, avatar e blocos
    sincronizacao/       scheduler, ITAD e locks
    sitemap/             sitemap.xml (indice + paginas de jogos)
    steam/               metadados Steam de catalogo

frontend/src/
  server.ts              entrypoint Express do SSR (@angular/ssr)
  app/
    components/          sidebar, topbar, cards e carrosseis
    guards/               auth.guard
    pages/                home, catalog, game-detail, profile, settings,
                          public-profile, editar-blocos (/perfil/blocos),
                          favorites/monitorados, login, search e outras
    services/             API, Auth, Supabase, tema, preferencias,
                          favoritos, favoritos pessoais, perfis,
                          perfil-blocos (metadados dos blocos) e SEO
```

Convencao obrigatoria no backend: classes, pacotes, metodos e variaveis em portugues. Marcas e contratos JSON podem manter termos externos, por exemplo ITAD, Steam, Bearer, `coverUrl` e `minPrice`.

### Documentacao de codigo (Javadoc / TSDoc)

O objetivo e que quem abre um arquivo pela primeira vez entenda **por que** ele existe e onde estao as pegadinhas — nao descrever o que a assinatura ja diz. Documentacao redundante nao e neutra: ela envelhece (parametro renomeado, comportamento mudado) e afunda os comentarios que realmente importam no meio do boilerplate.

**Sempre documentar:**

- **Toda classe/interface**: o que ela resolve, e quando houver, a restricao de arquitetura que explica o desenho dela. Exemplo real: `RepositorioInstantGaming` grava direto em `offers`/`games` via SQL propria porque nao pode depender do pacote `jogos` (dependencia circular com `ServicoCatalogo`).
- **Metodo com contrato nao-obvio**, ou seja quando existe pelo menos um destes:
  - **unidade ou formato** que a assinatura nao revela (`minutos` vs `segundos`, centavos vs reais, timestamp em UTC);
  - **efeito colateral** fora do retorno (grava historico, registra atividade, dispara notificacao, invalida cache);
  - **caso vazio/limite/erro** relevante pro chamador (retorna lista vazia vs 404, o que acontece quando nao ha oferta, o que acontece dentro do cooldown);
  - **invariante que o chamador precisa respeitar** (precisa ter chamado X antes, nao pode rodar concorrente, espera lote de no maximo N);
  - **decisao deliberada que parece bug** — sempre com o porque (ex: upsert em vez de DELETE+INSERT por causa do cascade; `prepareThreshold=0` por causa do pooler do Supabase).

**Nao documentar:** getters/setters, `record`, DTOs, construtores triviais e CRUD direto cujo nome ja diz tudo (`remover(usuarioId, jogoId)`). Javadoc que so repete a assinatura e ruido e nao deve ser adicionado.

**Clausula SQL sem comentario nao e clausula redundante.** Licao de 12/09/2026: o `NOT EXISTS` das consultas de pendencia de conquistas foi removido como "redundante" porque o carimbo `achievements_checked_at` parecia cobrir o caso. Nao cobria — o carimbo so e gravado quando o esquema da Steam vem vazio, entao era o `NOT EXISTS` que tirava da fila o jogo coletado com sucesso. Resultado: 13.614 jogos voltaram a ser considerados pendentes e cada visita a pagina de um jogo ja coletado refazia a busca do esquema na Steam. Antes de remover uma condicao que "parece" duplicada, cheque quem grava a outra ponta dela — e, ao restaurar, deixe o comentario explicando por que ela existe (foi o que foi feito).

**Formato:**

- Portugues, igual ao resto do backend. Sem acento e aceitavel (o codebase ja e assim).
- `@param`/`@return` **so quando agregam** informacao alem do nome — unidade, formato, o que significa `null`, faixa valida. Nao escrever `@param jogoId o id do jogo`.
- `@throws` quando a excecao faz parte do contrato (ex: `JogoSemItadException`).
- Comentario `//` dentro do metodo continua sendo o lugar certo pra explicar um trecho especifico (uma linha de SQL, um truque de CSS). Javadoc e pro contrato; `//` e pro trecho. O projeto ja usa bem esse estilo — manter.

No frontend a mesma regra vale com TSDoc (`/** ... */`), aplicada principalmente a services e aos componentes com logica nao-trivial (posicionamento calculado, change detection manual, workaround de SSR). Template e SCSS continuam com `//`/`<!-- -->` quando precisar.

**Como aplicar:** todo arquivo tocado por uma mudanca deve sair dela documentado conforme essa regra. O backfill do que ja existe esta rastreado em issue propria, com escopo nos arquivos que concentram a logica dificil — nao e pra sair documentando os 108 arquivos de uma vez.

## Assets e Tema

- Cor principal: `#29A8E0`.
- PNGs de perfil em `frontend/public/`: `horas-jogadas.png`, `conquistas.png`, `biblioteca.png`, `jogos-favoritos.png` e `jogos-monitorados.png`, com variantes por tema quando existentes.
- Logos de lojas/plataformas em `frontend/public/store-logos/` e `frontend/public/platform-logos/`.
- `store-brand.ts` resolve icones de loja e plataforma no frontend.

## Estado e Proximos Passos

### Implementado

- Catalogo, busca, detalhe, refresh individual, descontos, DLCs e filtros.
- Scheduler interno de precos ITAD e metadados Steam com fila e lock no PostgreSQL.
- Jogos Monitorados, notificacoes de queda de preco e sino na topbar.
- Login Supabase, perfis compartilhaveis, avatar persistente, bio e privacidade geral.
- Steam OpenID, biblioteca, horas, conquistas, favoritos pessoais e atividade recente.
- Pagina admin de coleta protegida por UID.
- Editor de perfil persistido com blocos (favoritos, biblioteca, atividade, platinados, wishlist Steam, conquistas recentes, mais jogados, texto, links, imagem), titulo editavel nos paineis de dados, upload de imagens JPG/PNG/WebP de ate 2 MB e imagens externas por URL `http(s)`, validado em desktop. O enquadramento de imagens de bloco aceita zoom por scroll/pinca e reposicionamento por arrasto direto na previa, em mouse ou toque. Desde 11/09/2026 a organizacao dos blocos (ordem, visibilidade, tamanho, visualizacao, cores) vive na pagina `/perfil/blocos`, com previa real do rascunho; o modo inline ficou so pro ajuste de imagem.
- Faixa fixa de estatisticas no perfil e galerias responsivas para favoritos pessoais e biblioteca Steam. Cards da Steam tentam a capa horizontal e depois uma capsula alternativa; se nenhuma existir, usam um fallback visual sem imagem quebrada. No hover de um card da biblioteca, "Ver na Steam" sempre aparece e "Ver no catalogo" aparece quando o jogo tem uma entrada correspondente no nosso catalogo (cruzamento em lote por `steam_app_id`).
- Instant Gaming como fonte extra de preco via scraping (sem API publica), com afiliacao propria — ver secao dedicada em "Coletas e Atualizacao de Catalogo".
- Favoritos pessoais com ordenacao manual (arrastar e soltar, persistida) dentro do modo Organizar da aba.
- Colecoes ("Minhas Listas"): modelo N:N, CRUD completo, aba propria no perfil e toggle de privacidade. Jogos sao adicionados por dois caminhos: o modal "Gerenciar jogos" na aba (biblioteca Steam completa + busca no catalogo) e o menu de listas na pagina do jogo (com "Criar nova lista" no topo).
- Jogos platinados na faixa de estatisticas: contagem de jogos com 100% de conquistas, exibida so quando ha pelo menos um; respeita o toggle de conquistas. Usa `trofeu.png` (desde o redesign de 11/09/2026; antes reusava `conquistas.png` em prateado).
- PostgreSQL, Auth/OAuth e Storage foram migrados para o Supabase em Sao Paulo. A Oracle usa o novo banco, executa o scheduler e expoe a API por Caddy/HTTPS. O frontend publicado na Vercel usa o mesmo projeto Supabase. O Render esta desligado; o Supabase antigo permanece somente como rollback temporario.
- Validacao pos-migracao concluida: login Google/Discord, catalogo, perfil, avatares, blocos e scheduler confirmados funcionando na Oracle com o Supabase novo.
- Xbox conectado via OpenXBL (OAuth "Xbox App", nao API key pessoal): login, biblioteca (progresso de conquistas e minutos jogados reais via endpoint de stats em lote) e desconexao. Sincronizacao e sempre upsert-only, nunca apaga jogos ja salvos. Biblioteca combinada Steam+Xbox na mesma lista/perfil, com filtro de plataforma na aba Biblioteca quando ha mais de uma conectada — ver secao "Steam e Xbox".
- Precos com cupom da ITAD (`vouchers=true`): quando existe, o preco com cupom vira o principal exibido, com selo indicando o codigo — ver secao "Precos ITAD".
- Favoritos e Platinados reordenaveis por arrastar direto no bloco do perfil, sem precisar entrar em "Gerenciar" — ver "Editor de perfil".
- Filtro de lojas preferidas nas Configuracoes, aplicado automaticamente no Catalogo — ver "Home, catalogo e monitoramento".
- Historico de preco (90 dias, grava so em mudanca) com grafico na pagina do jogo — ver "Historico de precos".
- Perfil redesenhado (hero com banner, faixa de stats enxuta, abas com icone) + pagina `/perfil/blocos` com previa real do rascunho, toggle Ativo/Oculto funcionando de ponta a ponta e escolha de visualizacao (lista/cards) nos favoritos — ver "Redesign visual do perfil" e "Pagina Editar Blocos".
- Favorito da Steam no perfil linkando pro nosso catalogo quando o jogo existe nele — ver "Perfil publico e privado".
- Recoleta automatica de jogo live-service quando aparece conquista desbloqueada fora do catalogo, rodando o mesmo "Preencher tudo agora" do admin — ver "Recoleta de jogo live-service".

### Em validacao

- O modo de edicao **inline** do perfil (hoje so o ajuste de imagem de bloco) continua sem layout mobile e nao esta sendo trabalhado — prioridade e desktop. A pagina `/perfil/blocos`, que substituiu ele na organizacao dos blocos, **e** responsiva (testada em 390×844: coluna unica, biblioteca e dica abaixo da lista, linha do bloco quebrando com o texto em linha propria).

### Planejado

1. Colecoes concluidas (CRUD, exibicao, modal de adicionar jogos e menu na pagina do jogo). Evolucao futura opcional: reordenar jogos dentro da colecao e reordenar as proprias colecoes.
   - Filtro por ano de lancamento/genero segue inviavel: `games` nao guarda esses campos. Dependeria de coluna nova + backfill via ITAD/Steam.
2. Melhorar a pagina de administracao/observabilidade de coletas e erros ITAD/Steam.
3. Xbox: reordenar platinados por arrastar ainda nao persiste pra itens Xbox (so Steam). Favoritar/link externo tambem continuam Steam-only nos cards de biblioteca.
   - Perfil/editor de blocos: mover o recorte de imagem pro editor novo e apagar o modo inline; avisar de rascunho nao salvo ao sair de `/perfil/blocos` (`CanDeactivate`); permitir arrastar blocos direto na previa; trazer gradiente e cor global pro menu `⋮`; desenhar a visualizacao em lista pros outros blocos de jogos (`TIPOS_COM_VISUALIZACAO`).
4. Integrar Eneba depois de aprovar afiliacao. Instant Gaming ja implementada por scraping (ver secao propria) — melhorar cobertura da varredura (o catalogo pode ter jogos com id acima do que ja foi varrido) e considerar guardar `regular_price` se a pagina deles passar a expor desconto de forma confiavel.
5. Melhorar observabilidade operacional da Oracle: uso de memoria, erros do scheduler e status da API.
6. Depois de alguns dias de estabilidade, exportar um ultimo backup e excluir o projeto Supabase antigo.

## Checklist antes de Publicar

1. Conferir `git status --short` e nunca reverter mudancas do usuario.
2. Para frontend: `cd frontend; npm.cmd run build`.
3. Para backend: `cd backend-java; mvn -o test` (63 testes hoje). Nao ha `mvn` no PATH desta maquina nem `mvnw` no repo, mas existe um Maven baixado pelo wrapper em `%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.11-bin\<hash>\apache-maven-3.9.11\bin\mvn.cmd` — da pra chamar por esse caminho e rodar o build/test localmente, sem depender so do CI.
4. Conferir se uma migration nova precisa ser aplicada no Supabase antes do deploy.
5. Conferir CORS quando uma rota `PUT`, `PATCH` ou `POST` nova for adicionada.
6. Validar em producao: catalogo, detalhes, monitorados, perfil proprio, perfil anonimo e conexao Steam/Xbox quando afetados.
7. O deploy manual `scripts/deploy-oracle.ps1` valida a saude pela URL HTTPS publica do Caddy; a porta `8080` nao e exposta diretamente na VM.

## Regras de Seguranca e Produto

- Sem scraping de lojas ou plataformas, com uma excecao explicita: **Instant Gaming** (ver secao propria acima), porque nao tem API nem esta no ITAD e o contato oficial pedindo acesso foi negado. Qualquer nova excecao dessas deve ser decidida caso a caso, nao vira regra geral.
- Sem multi-moeda funcional por enquanto.
- Sem exibir dados privados de perfil na rota publica.
- Nao reintroduzir sincronizacao recorrente pelo GitHub Actions.
- Nao colocar chaves do backend no frontend.
- Nao transformar favoritos pessoais em Jogos Monitorados nem o contrario.
