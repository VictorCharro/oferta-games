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
.\scripts\deploy-oracle.ps1 -ChaveSsh "<caminho da chave SSH da VM>"
```

O script conecta na VM, atualiza o `master`, recria somente o container do backend e valida o health check. A chave da VM usada para baixar o repositorio continua somente leitura.

### Deploy sem derrubar o site (12/09/2026)

Sintoma relatado: depois de publicar, o site demorava muito pra voltar e as vezes nao carregava. **Cinco causas independentes**, todas achadas medindo a VM — e as duas ultimas so apareceram porque o resultado real foi conferido em vez de confiar no "deploy verde".

1. **Todo push recriava o container do backend**, mesmo commit so de frontend (`docker compose up -d --build` recria sempre; provado por `--dry-run`, que mostra `catalogo-db`/`caddy` como `Running` e `backend` como `Recreate`). Como o site e SSR, a Vercel precisa da API pra renderizar: API fora = **pagina em branco**, nao "dado faltando". Agora a VM decide pelo que mudou, e `build` + `up -d` no lugar de `up -d --build`.
2. **A referencia do "ja publicado" era o checkout, nao a imagem.** Um deploy que morre no meio deixa a VM no commit novo com a imagem velha; o proximo comparava HEAD-antes com HEAD-depois, via "backend nao mudou" e pulava. Agora existe o marcador `.deployed-sha`, gravado **so no fim** de um deploy bem sucedido (ignorado no git, vive so na VM).
3. **Trava de coleta orfa.** Deploy no meio de uma coleta deixava `sync_locks` preso ate o lease de 30 min expirar, e **toda** coleta era pulada nesse intervalo (caso real: trava tomada as 20:33:43, container recriado 35s depois, precos/steam/detalhes pulados). `ServicoExecucaoColeta` ganhou `@PreDestroy` que devolve a trava — so se for esta instancia que a tomou, porque liberar a de outro processo permitiria duas coletas simultaneas.
4. **O script do deploy era engolido pelo proprio stdin.** Ele ia pra VM por `bash -s` e o `docker compose exec -T` do reload do Caddy consumiu o resto: o deploy parava ali, nunca publicava o backend e **saia com sucesso**. Agora vai como arquivo pra `/tmp` e roda de la.
5. **`CMD ["sh","-c","java ..."]` deixava o shell como PID 1**, e o shell nao repassa SIGTERM: o Java so morria no SIGKILL. Ou seja, `server.shutdown: graceful`, `stop_grace_period` e o `@PreDestroy` eram **configuracao inerte**. O log denunciava (`INFO 8 ---` = java no PID 8). Com `exec` no CMD o java vira PID 1; confirmado depois: `INFO 1 ---` e "Commencing graceful shutdown" / "Graceful shutdown complete" no log.

**O que o deploy faz hoje** (`.github/workflows/deploy-oracle.yml`): compara `.deployed-sha` com o commit novo e toma **duas decisoes separadas** — recriar o backend (mudou `backend-java/` ou `compose.yml`, ou `workflow_dispatch`, ou container fora do ar) e mexer no Caddy (mudou o `Caddyfile`). No fim, um passo confere a API pela URL publica (ate 100s), validando Caddy + TLS + backend de uma vez; era o que teria pego a causa 4 na hora.

**Caddyfile e recriado, nao recarregado.** Ele entra por bind mount de *arquivo*, que aponta pro inode; `git pull` escreve um arquivo novo e renomeia, entao o container continua vendo o antigo e um `caddy reload` rele o arquivo velho (aconteceu: host com a config nova, container sem). Recriar custa ~1s e so acontece quando o Caddyfile muda; os certificados ficam no volume `caddy_data`.

**O proxy segura a requisicao durante o restart.** `lb_try_duration 25s` no `reverse_proxy`: enquanto o backend sobe, o Caddy espera em vez de devolver 502 na hora. So vale pra falha de **conexao**, quando nada foi enviado ainda, entao POST/PUT nao correm risco de rodar duas vezes. Medido num restart controlado: **nenhuma requisicao falhou**, e o pior caso foi uma que levou **11,7s** e respondeu 200 — antes, aquela janela virava 502 e, com SSR, pagina em branco.

**Delays iniciais do scheduler contam a partir do boot**, entao cada deploy reiniciava esse relogio e jogava a coleta de precos (a mais pesada) 60s depois de subir, com a JVM fria, no unico vCPU — load chegou a **10,68**. Escalonados pra 4min (precos), 8min (steam) e 11min (detalhes).

### Migracao do catalogo pra fora do Supabase (10/09/2026)

O banco Supabase free (cota de 500MB) bateu 89% de uso (442MB) mesmo depois da limpeza de 01/09/2026 — as tabelas de catalogo (`games`/`game_details`/`game_achievements`/`offers`/`price_history`/`instant_gaming_catalog`) sao 90%+ do banco e crescem organicamente pelos jobs de sincronizacao (nao e bug, e o catalogo funcionando). Como nao guardam dado de usuario (sem FK com `auth.users`), foram movidas pra um Postgres 17 self-hosted, container `catalogo-db` no mesmo `compose.yml` do backend, na VM Oracle — sem cota, sem porta exposta pro host (so acessivel pela rede interna do Docker compose). Banco caiu pra 15MB no Supabase.

**Auth e tudo ligado a usuario continuam 100% no Supabase** (favoritos, perfis, avaliacoes, notificacoes, conexoes Steam/Xbox) — inclusive o cascade delete de `auth.users` pra esses dados, que so funciona porque estao no mesmo Postgres que o Auth. Ver "Schema Relevante" abaixo pra saber qual tabela mora em qual banco.

`ConfiguracaoBancoDados` sobe dois `DataSource`/`JdbcClient`/`JdbcTemplate`/`PlatformTransactionManager` — o do Supabase e `@Primary` (usado por quem nao pede qualifier), o do catalogo e qualificado `"catalogo"`. Sete repositorios so usam catalogo (`RepositorioJogos`, `RepositorioDescontos`, boa parte de `RepositorioInstantGaming`); cinco fazem JOIN entre tabela de usuario e tabela de catalogo e foram reescritos pra buscar nos dois bancos e juntar em Java (`RepositorioFavoritos`, `RepositorioFavoritosPerfil`, `RepositorioAtividadesPerfil`, `RepositorioColecoesPerfil`, `RepositorioNotificacoes`) — perderam a garantia de integridade referencial entre as duas pontas (ex: nada impede um `favorites.game_id` apontar pra um jogo que nao existe mais no catalogo), mas o app nunca deleta jogos do catalogo (so upsert), entao isso e teorico ate hoje.

**Backup** (reescrito em 13/09/2026, issue #15): **nenhum dos dois bancos tem backup automatico por fora** — o `catalogo-db` e um container, e o Supabase **free** nao inclui backup (a frase antiga "o Supabase faz backup sozinho" estava errada; isso e do plano Pro). `deploy/oracle/backup.sh` roda via cron diario as 4h e faz os dois: `pg_dump` do catalogo e dos schemas `public` + `auth` do Supabase, valida cada dump, mantem 7 diarios + domingos de 5 semanas, e opcionalmente copia pra fora da VM (`BACKUP_RCLONE_REMOTE`) e avisa um heartbeat (`BACKUP_HEARTBEAT_URL`). Configuracao, copia externa na Oracle sem chave no disco e restauracao testada: `deploy/oracle/README-catalogo-db.md`.

O script anterior (`backup-catalogo.sh`) **nunca rodou pelo cron**: a linha redirecionava o log pra `/var/log/backup-catalogo.log`, que o usuario `ubuntu` nao pode criar, e o shell falhava no `>>` antes de executar o script — sem log, sem erro, sem nada. Ficou de 10/09 a 13/09 so com o dump manual da migracao. O log agora vai pra `/opt/backups/backup.log`. Sem catalogo restauravel, um `catalogo-db` novo sobe saudavel mas **vazio**, e toda query de catalogo quebra com "relation games does not exist" sem aviso nenhum antes disso.

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
- **Imagem de compartilhamento padrao (15/09/2026)**: `frontend/public/og-image.png`, 1200x630 (logo, chamada e lojas), usada pelas paginas sem imagem propria; `og:image:width/height` so vao junto quando e essa arte (capa de jogo e banner de perfil tem outro tamanho).
- **Vercel Web Analytics e Speed Insights (15/09/2026)**: scripts direto no `index.html` (`/_vercel/insights/script.js` e `/_vercel/speed-insights/script.js`), no lugar dos pacotes `@vercel/analytics`/`@vercel/speed-insights`, que conflitam com as peer deps do projeto (arrastam SvelteKit/Vite 8). Sem cookie; citados na Politica de Privacidade. Bloqueador de anuncio impede a contagem.
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

### Quando algo cair (monitoramento, 15/09/2026)

- **Aviso**: o workflow `Monitoramento` (`.github/workflows/monitoramento.yml`) roda de hora em hora e confere site, `/actuator/health`, `/api/deals/top`, disco (>=80%), memoria disponivel (<300 MB), os 3 containers e se os dois backups tem arquivo das ultimas 26h. Problema abre uma issue com a label `alerta` (o GitHub avisa por e-mail) e a proxima verificacao ok fecha. Rodar na mao: Actions > Monitoramento > Run workflow. De hora em hora e nao a cada 5 min por causa da cota gratis do Actions em repo privado; monitor de minuto a minuto fica pra UptimeRobot/Better Stack quando houver dominio.
- **Erros**: `/admin/coleta?aba=erros`. Erros nao tratados do navegador (`RelatorErros`, ErrorHandler do Angular) e 500 do backend (`ObservadorExcecoes`) vao pra `app_errors`, agrupados por assinatura com contador; resolvido que acontece de novo reabre. Erro HTTP 4xx, 404 de rota e cliente que desconectou nao entram.
- **Logs do backend**: `ssh -i <chave> ubuntu@163.176.220.243 'docker logs --since 1h oferta-games-backend'`. Coletas: aba Coleta do admin.
- **Uso e velocidade reais**: Vercel > oferta-games-frontend > Analytics e Speed Insights.
- **Backup**: `/opt/backups/backup.log` na VM (ver `deploy/oracle/README-catalogo-db.md`).

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
- **Gratuitos: so o que tem preco em alguma loja (16/09/2026, substitui a regra de 29/07 abaixo)**: oferta a `price = 0` so entra em `/api/deals/top` (e portanto em `/gratuitos`, na secao "Gratis da semana" e como 100% off) quando o jogo e **pago em alguma loja** — desconto de 100% com `regular_price` (gratis semanal da Epic) ou de graca numa loja e vendido em outra (For Honor gratis na Ubisoft, R$ 129,99 na Steam). Free-to-play (PUBG, Dota 2, Warframe, Marvel Rivals), que nao tem preco em lugar nenhum, fica fora de toda secao de promocao, mas continua no catalogo e na busca. Motivo: a descoberta de jogos novos importou os grandes F2P da Steam e a pagina Gratuitos encheu deles. Medido na mudanca: 170 jogos sempre gratis sairam, 68 de graca de verdade ficaram.
- *(regra antiga, substituida)* **Gratuitos e ofertas com regular_price 0 (29/07/2026)**: `RepositorioDescontos.listarTopo` (usada por `/api/deals/top` e pela pagina Gratuitos) calculava desconto so quando `regular_price > 0`, entao um jogo permanentemente gratis (giveaway sem "preco normal" registrado, ex: Amigdala na Steam) nunca entrava, mesmo sendo gratuito de verdade. Agora `price = 0` sempre conta como 100% off (`discount_pct` fixo em 100 nesse caso, sem dividir por `regular_price`, que pode ser 0/nulo), independente de outras lojas venderem o mesmo jogo pago.

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

`profile_blocks.view_mode` (12/09/2026) guarda a visualizacao escolhida pro bloco, com **vocabulario por tipo** — a coluna responde "como este bloco se mostra", e cada tipo le so o valor dele:

- blocos de jogos: `cards` (capa grande) ou `lista` (linha compacta). Hoje so o bloco de favoritos tem as duas desenhadas, e ele nasce em `lista`.
- bloco de imagem: `proporcao` (mostra a imagem inteira) ou `redimensionar` (recorta pra preencher o bloco). Nasce em `proporcao`.

`NULL` = padrao do tipo. `ServicoPerfis.salvarBlocos` valida o conjunto todo (`cards|lista|proporcao|redimensionar`) em vez de cruzar com o tipo: valor do vocabulario errado e inerte, porque quem le e sempre o tipo correspondente.

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
- `GET /api/games/search?q=nome` — mesma query do catalogo com `q`. **Busca (16/09/2026)**: casa em **inicio de palavra** (`title ~* '\mtermo'`), nao em qualquer posicao (antes "gta" achava "RagTag"); aceita **siglas** de `ApelidosBusca` (gta, cod, re, gow, mk, tlou, rdr, cs, bf, nfs, fifa, pes, dbd, poe, ff, dmc... ~30), que viram tambem o nome por extenso mantendo o complemento ("re 4" -> "resident evil 4"); e ordena por **relevancia** antes do rank: titulo igual ao termo, depois comeca com o nome por extenso da sigla, depois comeca com o termo, depois o resto. Com busca, a ordenacao pre-paginada e desligada. Sigla nova: acrescentar no mapa de `ApelidosBusca` (tem teste).
- `GET /api/games/{slug}` — inclui `dlcs[]` (DLCs desse jogo ja presentes no catalogo) e `jogosBase[]` (caminho inverso: se este jogo e uma DLC, o(s) jogo(s) base dela); ver "Plataformas e DLCs".
- `GET /api/games/{slug}/avaliacoes/steam?cursor=&ordenacao=recent&idioma=brazilian`: pagina de 10 reviews reais da Steam, com texto, recomendacao, tempo jogado, autor e avatar quando disponiveis. Retorna `proximoCursor`, `temMais`, `idiomaConsulta` e `ordenacao`; aceita `recent`, `all` (mais uteis) e `updated`. Resposta publica e cacheada.
- `GET /api/games/{slug}/detalhes` — descricao/generos/devs/publishers/data/screenshots/review, de `game_details`. 404 se o jogo ainda nao foi sincronizado (sem oferta Steam, ou aguardando o job).
- `GET /api/games/{slug}/conquistas` — auth opcional. `{ total, desbloqueadas, percentualConcluido, proxima, conquistas[] }`; cada conquista com `desbloqueada`/`desbloqueadaEm` cruzados com o progresso do visitante logado (ver "Conquistas com progresso pessoal" acima). Sempre 200, mesmo pra jogo inexistente (fica tudo zerado).
- `GET|POST|DELETE /api/games/{slug}/reviews`, `POST /api/games/{slug}/reviews/{id}/voto` — ver "Reviews (Steam + Oferta Games)" acima.
- `POST /api/games/{slug}/refresh`
- `GET /api/games/{slug}/historico-precos?dias=90` — pontos `{ price, capturadoEm }` de `price_history`, ordem cronologica; `dias` limitado a 1-90. 404 se o slug nao existir. Ver "Historico de precos" acima.
- `GET /api/deals/top?size=&sort=discount|rank&type=all|game|dlc` — usado pela Home (rank, discount e discount+type=dlc) e pela pagina Gratuitos (`size=100&sort=discount`, filtrando `discountPct === 100` no front); `RepositorioDescontos.listarTopo` tambem cacheado (Caffeine, `ConfiguracaoCache.CACHE_DESCONTOS`, 15min), pois e uma query com DISTINCT ON + join na tabela `offers` inteira. `ServicoAquecimentoCache` reaquece as combinacoes usadas (100/rank, 200/discount, 100/discount, 50/discount/dlc) logo apos cada rodada de precos, pra nenhuma delas (incluindo Gratuitos) pegar cache frio.
- `GET /api/deals/lancamentos` (18/09/2026) — secao "Lancamentos em alta" da Home: ate 30 jogos com rank <= 3.000, lancados nos ultimos 60 dias ou previstos pros proximos 180, ordenados por rank, com a oferta paga mais barata (com ou sem desconto; `discountPct` 0 quando nao ha). A data vem de `game_details.release_date`, texto da Steam em pt-BR ("24/set./2026") interpretado na propria query; formato fora disso ("Em breve", "2027") fica de fora. Cacheado em `CACHE_DESCONTOS` com a chave `lancamentos`.
  - **Parametros normalizados e topo unico (13/09/2026, issue #16)**: `size`, `sort` e `type` chegavam crus na chave do cache, entao cada variacao (`size=24`, `sort=xyz`) pagava a query inteira — `/api/deals/top?size=24` media **13s** (28s sob carga) contra 0,04s cacheado, e um script variando `size` derrubava o unico vCPU sem login. Agora `ParametrosPublicos` normaliza tudo (valor desconhecido cai no padrao) e `RepositorioDescontos.listarTopo(ordenacao, tipo)` sempre calcula os 200 primeiros, cacheados so por ordenacao/tipo (no maximo 6 chaves); o controller fatia o `size`. O mesmo vale pro `GET /api/games`: `sort`/`type`/`platform` por lista fechada, `size` so 20 ou 40 (o que o frontend usa), `page` ate 200, precos em reais inteiros (min pra baixo, max pra cima), desconto inteiro, busca em minusculas e lojas validas ordenadas. **Mexeu num valor aceito no frontend, mexe em `ParametrosPublicos`** — senao a opcao nova vira o padrao em silencio.
  - **Por que a query era lenta — regex, nao paralelismo**: `EXPLAIN ANALYZE` na VM mostrou 6,8s so no filtro de loja bloqueada sobre as 166 mil ofertas e 3,5s no filtro de "nao e jogo" sobre os 110 mil titulos. O Postgres gasta ~30 µs por linha num regex de alternativas (`!~ '^(gog|humblestore|...)$'`), enquanto `lower()`/`regexp_replace` sozinhos sao rapidos. `LojasBloqueadas`, `ConteudosNaoJogos` e `ClassificadorDlc` passaram a gerar `<> ALL (ARRAY[...])` e `LIKE ANY (ARRAY['%termo%'])` a partir de **uma unica lista** por classe (usada tambem pelo Java, ver `TermosSql`): 4,9s → 148ms e 3,2s → 235ms, com 0 linhas de diferenca contra o regex. `TermosSqlTest` fixa essa equivalencia caso a caso. Paralelismo e JIT foram testados e nao eram a causa (desligar os dois tirava ~20%). Os filtros de plataforma e de lojas preferidas continuam com regex: so entram quando o usuario escolhe o filtro.
  - **LIMIT antes do LATERAL**: a oferta mais barata era buscada pros ~31 mil jogos elegiveis e o LIMIT descartava quase tudo. Como a ordenacao final so usa `discount_pct` e `rank`, o topo e cortado primeiro. Resultado medido: **856ms contra 28,7s**, mesmas 200 linhas (slug, desconto, loja, preco e URL identicos).
  - **Protecao contra rajada**: `@Cacheable(sync = true)` em `listarTopo` e `RepositorioJogos.listar` (requisicoes iguais simultaneas esperam uma execucao so) e `LimiteConsultasPesadas` — no maximo 2 consultas que agregam o catalogo inteiro ao mesmo tempo; o excesso espera ate 15s e recebe 503. Pagina ja cacheada e consulta pre-paginada (~30ms) nao passam pelo limite, entao Home e catalogo seguem no ar durante uma rajada.
  - **Aquecimento no boot**: `ServicoAquecimentoCache.aquecerNoBoot` popula o cache em segundo plano assim que a aplicacao sobe. Antes o primeiro aquecimento so vinha com a primeira rodada de precos (4 min apos o deploy), e todo SSR nesse intervalo pagava a query fria.
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

Os endpoints autenticados recebem token Bearer do Supabase. A administracao exige um UID listado em `ADMIN_USER_IDS` (variavel de ambiente do backend; sem padrao no codigo desde 18/09/2026). O guard do frontend pergunta ao backend (`GET /api/admin/acesso`, 204 ou 401/403) em vez de comparar com um UID fixo.

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
| **Inicio** (`/`) | Apresentar ofertas de interesse imediato. | Banner de melhor oferta, carrosseis de Jogos Monitorados, plataforma favorita quando definida, Lancamentos em alta, Promocoes nos jogos mais populares, Gratis da semana e maiores descontos em jogos e em DLCs (regras em "Home, catalogo e monitoramento"). O conteudo geral nunca deve desaparecer ao selecionar uma plataforma favorita. |
| **Catalogo** (`/catalogo`) | Explorar todo o catalogo. | Scroll infinito; filtros de tipo, plataforma, desconto minimo e faixa de preco; ordenacao por dropdown customizado. As preferencias preenchem os filtros iniciais, mas o usuario pode mudar tudo manualmente. |
| **Detalhe do jogo** (`/jogo/:slug`) | Comparar lojas e reunir informacoes do jogo. | Hero com capa, melhor preco, botao `Atualizar precos`, acoes pessoais, tabela de ofertas e abas Precos/Sobre/Review/Conquistas quando ha dados. Review separa o resumo e as avaliacoes recentes reais da Steam do sistema proprio do Oferta Games. |
| **Busca** | Encontrar jogos, lojas e categorias pela topbar. | Sugestoes devem navegar diretamente para o jogo escolhido; a mudanca de URL precisa recarregar o detalhe mesmo quando o usuario ja esta em outro detalhe. |
| **Jogos Monitorados** (`/monitorados`) | Listar jogos acompanhados por preco. | Usa a tabela `favorites` e os endpoints `/api/favorites`. E diferente de favoritos pessoais do perfil. A rota antiga `/favoritos` somente redireciona para aqui. O popover de meta diz **"Opcional."**: sem meta o jogo continua monitorado e a notificacao de `queda` vem quando a variacao passa do limiar (ver "Reviews"/`RepositorioNotificacoes.registrarQueda`); a meta so troca isso por `meta_atingida` na travessia do valor. |
| **Mais vendidos** | Mostrar jogos relevantes/populares. | Usa rank ITAD e deve respeitar as mesmas regras de filtro de conteudo nao-jogo, DLC e lojas bloqueadas. |
| **Gratuitos** | Mostrar o que esta de graca agora e custa dinheiro em alguma loja. | Free-to-play fica de fora (regra em "ITAD"). Itens com link invalido ou filtrados por `JogosBloqueados` nao devem aparecer. |
| **Login** | Autenticar por Supabase Auth. | Nao usa sidebar nem topbar. Depois do login, a navegacao volta ao fluxo normal do aplicativo. |
| **Configuracoes** (`/configuracoes`) | Centralizar opcoes da conta. | Abas separadas: Conta, Conexoes, Preferencias e Privacidade. Nao misturar assuntos entre abas. Preferencias afetam home/catalogo; Privacidade afeta o perfil publico; Conexoes concentra Steam e Xbox. |
| **Perfil proprio** (`/perfil` -> `/:handle`) | Personalizar e visualizar o perfil do usuario. | `/perfil` redireciona para o handle canonico. O dono pode editar bio, foto e pedir atualizacao Steam ali; ordem/visibilidade/tamanho dos blocos ficam em `/perfil/blocos`. A pagina canonica e a mesma que visitantes veem, com controles extras apenas para o dono. |
| **Editar Blocos** (`/perfil/blocos`) | Organizar os blocos do perfil. | Aberta pelo botao "Editar perfil" do cabecalho. Lista reordenavel com toggle Ativo/Oculto, menu por bloco (tamanho, visualizacao, cores, conteudo de texto/links) e Biblioteca de Blocos. A aba "Visualizar Perfil" e uma previa real do rascunho, renderizando o proprio `app-public-profile`. |
| **Perfil publico** (`/:handle`) | Compartilhar biblioteca e perfil gamer. | Respeita privacidade geral e dos dados escolhidos. Mostra uma faixa fixa com biblioteca, horas, conquistas desbloqueadas, jogos platinados (so quando ha algum) e icones das plataformas conectadas; abaixo, mostra Resumo, Jogos favoritos, Colecoes e Biblioteca quando liberados. Nunca mostra e-mail, UUID, Jogos Monitorados ou controles de edicao a visitantes. |
| **Administracao de coleta** (`/admin/coleta`) | Acompanhar e disparar jobs internos. | Exclusiva do UID administrador. Resumo no topo e abas Visao geral / Moderacao / Mensagens / Erros / Coleta / Ferramentas; na Coleta, grupos Precos e Steam (precos, metadados, descoberta, ranking) / Detalhes e Conquistas / Instant Gaming, cada uma com seus cards de status e sua propria fila; permite disparar coleta manual em segundo plano, mas nao substitui o scheduler. |

**Card do carrossel de monitorados** (12/09/2026): `.monitor-card` tem `flex: 0 0 210px`, mas sem `min-width: 0` o `flex-basis` nao era respeitado — flex item com `overflow: visible` ganha `min-width: auto`, e o min-content daqui e o titulo INTEIRO porque `.monitor-title` usa `white-space: nowrap`. Resultado no carrossel da Home: "Dying Light 2 Stay Human - Reloaded Edition" esticava o card pra 308px enquanto "7 Days to Die" ficava com 210px e, como `.monitor-cover` e 16/9, capa mais larga virava capa mais alta. O `.deal-card` nao tem o problema porque tem `overflow: hidden` (que tambem zera o minimo automatico); aqui o `overflow: hidden` nao pode existir por causa do popover de meta, entao a trava e o `min-width: 0`. A pagina `/monitorados` nunca sofreu disso: usa grid com `minmax(260px, 1fr)`, onde `flex-basis` e ignorado e o minimo automatico nao se aplica.

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
- **Secoes da Home (18/09/2026)**, todas dependentes do ranking diario (ver "Ranking de popularidade"):
  - **Banner** (o carrossel que passa sozinho) e **Promocoes nos jogos mais populares** (antes "Descontacos"): `/api/deals/top?sort=rank`, so desconto **a partir de 20%** (`DESCONTO_MINIMO_DESTAQUE` em `home.ts`; antes entravam 2% e 10% de jogo famoso), pega os 40 primeiros por popularidade e sorteia 5 pro banner e 20 pro carrossel a cada visita.
  - **Lancamentos em alta**: `/api/deals/lancamentos`, sem sorteio (ordem do rank), com ou sem desconto. Existe porque lancamento quase nunca esta em promocao e nunca aparecia nas secoes de desconto. Muda sozinha: janela de datas que anda todo dia, rank diario, descoberta de jogos a cada 3h e precos a cada 10 min. Limite conhecido: indie que viraliza nas listas da Steam tambem entra.
  - **Gratis da semana** e **Maiores descontos em Jogos/DLCs**: como antes (Gratis segue a regra nova de Gratuitos).
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

- **Altura uniforme, largura variavel** (12/09/2026): todo bloco da mesma linha tem a mesma altura — o tamanho muda so a largura. O grid ja esticava os blocos, mas o mais curto ficava com um buraco dentro (medido: bloco pequeno de Platinados em 514px com 263px vazios). Tres pecas resolvem: `.profile-block` e coluna flex, `.profile-game-grid` usa `flex: 1` + `grid-auto-rows: 1fr` (ocupa o que sobra e divide entre as linhas) e `.profile-block .profile-game-card` ganha `aspect-ratio: auto` — sem isso o card nao esticava e o vazio so migrava pro vao entre as linhas. A capa e `object-fit: cover`, entao card mais alto recorta mais imagem, sem distorcer. Fora dos blocos (aba Biblioteca) o 16/9 segue valendo, e no mobile tambem: la os blocos empilham, nao existe vizinho forcando altura, e o esticamento so deixaria o card achatado.
- **A altura e a MESMA em todos os tamanhos, nao so na mesma linha** (12/09/2026): `app-public-profile { --altura-bloco: 600px }` e `.profile-block { height: var(--altura-bloco) }`. Trocar o tamanho de um bloco muda **so a largura** (quantas das 12 colunas ele ocupa). Antes cada tamanho tinha seu proprio `min-height` (138/220/270/310px), entao Pequeno → Completo esticava o bloco e quebrava o alinhamento da linha. Dois detalhes que custaram tempo:
  - **`height`, nao `min-height`**: `min-height` e piso, o bloco seguia crescendo com o conteudo — a Biblioteca em Completo chegou a **922px** medidos. Com altura definida o conteudo se ajusta DENTRO dela, e pra isso cada area de conteudo precisa de `flex: 1` + `min-height: 0` (`.profile-game-grid`, `.profile-favorite-list`, `.achievement-list`, `.profile-block > p`): sem o `min-height: 0` o tamanho minimo automatico do flex ignora o teto e o bloco volta a crescer.
  - **Nada de `overflow: hidden` no `.profile-block`**: o menu `⋮` do editor vive dentro dele e seria cortado, igual ao que aconteceu com o dropdown do cabecalho. O corte fica nas listas de dentro.
  - **As listas crescem PRO LADO**, nunca pra baixo: 1 coluna no pequeno/medio (374px nao cabem duas capas + metas), 2 em largo/completo.
  - **As listas preenchem o bloco sozinhas, sem contagem calibrada** (12/09/2026): a primeira versao tinha limites exatos por tamanho calibrados pra 460px; quando a altura subiu pra 600px o bloco de conquistas ficou com 5 itens e um terco vazio. Agora `limiteLista` (12/12/20/20) e so um **teto de sobra**, e quem decide quantos aparecem e o CSS: `.achievement-list`/`.profile-favorite-list` dentro do bloco sao `flex-flow: column wrap` com `overflow: hidden` e cada item tem a largura de uma coluna — o que nao cabe na altura quebra pra uma coluna alem da borda e some, **nunca um item cortado ao meio**. Medido a 600px com 20 itens: 8/7/14/14 visiveis, 0 cortados. Qualquer `--altura-bloco` funciona sem recalibrar. Ordem de leitura: desce a 1a coluna, depois a 2a. O backend passou a mandar 20 conquistas recentes (era 12) pra encher as 2 colunas. No mobile a lista volta a nao quebrar e corta em 6 por `:nth-child(n + 7)`.
  - No **mobile** (≤620px) a altura volta pra `auto` e as listas perdem o `overflow: hidden`: os blocos empilham, nao existe vizinho pra igualar, e o teto so cortaria conteudo. Junto vai `.custom-image-frame.preenche` de volta pra uma altura propria, porque sem altura definida no bloco o `flex: 1` dele nao teria o que dividir.
  - **O valor do teto nao pode ser menor que a altura que o perfil ja tinha**: 460px encolheu o perfil aos olhos do usuario ("pq diminuiu a altura? esse tamanho tava bom") e 520px ainda ficou baixo. O teto existe pra impedir o bloco de CRESCER (Biblioteca em Completo chegou a 922px), nao pra apertar o que ja estava bom. Fechado em **600px**, escolhido pelo usuario. Medir "quanto o bloco teria sem o teto" **nao ajuda**: com `height: auto` o `flex: 1` do grid perde a base e o bloco desaba pra ~332px, numero que nao corresponde a nada que se via na tela.
  - Medido depois da mudanca: a mesma altura nos 7 tipos de bloco x 4 tamanhos.
- **Quantos jogos cada bloco mostra**: `previewLimit` = 8/6/9/12 (pequeno/medio/largo/completo). O pequeno era 1 card unico gigante (1 coluna, 1 item) — virou 2 colunas, como os outros. Cada tamanho tem largura de coluna diferente e capa 16/9, entao coluna estreita da card mais baixo: e por isso que o pequeno leva mais itens que o medio, nao menos.
- **Bloco de imagem** (12/09/2026): o quadro (`.custom-image-frame`) tinha altura `clamp(180px, 28vw, 420px)` — independente do bloco, o que deixava sobra quando a linha era mais alta. Agora o quadro **estica pela altura do bloco nos dois modos** (`.preenche`, `flex: 1`) e quem muda e o encaixe da imagem dentro dele: `redimensionar` usa cover (recorta) e `proporcao` usa contain (cabe inteira, com tarja nas laterais ou em cima/embaixo) — `coverGeometry` recebe o ajuste e troca `Math.max` por `Math.min` na escala. **Nao** dar `aspect-ratio` da imagem ao quadro: foi a primeira tentativa e uma foto em pe esticava o bloco, que passava a definir a altura da linha em vez de segui-la (o `max-height: 100%` nao segura nada, porque a altura da linha vem do bloco mais alto — circular). Verificado com um retrato 720×1280: bloco na altura padrao nos dois modos, igual aos vizinhos da linha. O select fica na barra de edicao inline do bloco, ao lado de tamanho e fundo, e aparece **so** no bloco de imagem. Junto vai uma dica com o tamanho recomendado, **medido do bloco renderizado** (area x 2, pra nao ficar borrada em retina) e guardado num Map por `medirTamanhoIdeal` — ler layout direto do template rodaria a cada ciclo de deteccao e devolveria valor diferente no meio dele (NG0100). No bloco medio a 1440px de largura da `1052 × 808 px`; num bloco pequeno ou largo o numero e outro, e por isso a dica nao pode ser fixa.
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

> **Leia antes:** existem **dois editores, os dois legitimos**, e o botao "Editar perfil" do cabecalho abre um dropdown com os dois (12/09/2026):
>
> - **Organizar blocos** → `/perfil/blocos` (secao acima): lista com arrastar, biblioteca de blocos e previa.
> - **Editar na pagina** → o modo inline descrito abaixo, ligado no proprio perfil (`startLayoutEdit`), que mostra o resultado ao vivo. A pagina de blocos tambem tem o botao "Editar na pagina" (que avisa antes se houver rascunho nao salvo, porque o inline le o que esta salvo), e o menu de um bloco de imagem leva pra ca por `/{handle}?editor=1`.
>
> Ate 12/09/2026 o inline era tratado como legado ("modo antigo"): isso mudou quando ele passou a usar o mesmo menu `⋮` da pagina nova. O plano antigo de apaga-lo saiu de pauta — o que sobra e trazer pra pagina o que so existe aqui (recorte de imagem, gradiente, cor global). O texto abaixo descreve o que ele faz quando aberto; parte das funcoes (ordem, visibilidade, tamanho, visualizacao, cores, conteudo de texto/links) tambem existe — e e o caminho preferido — na pagina nova. O que **so** existe aqui: recorte/enquadramento de imagem, gradiente de fundo e o atalho de cor global. Mover o recorte de imagem pra um modal na pagina nova e o passo que permitiria apagar este modo por inteiro.

**Controles no menu "⋮", nao numa barra sobre o bloco (12/09/2026).** Antes cada bloco em edicao carregava uma barra empilhada acima dele com todos os controles — selects de tamanho e fundo, seletores de cor, botoes de imagem e, em texto/links, um formulario inteiro (titulo + textarea). Ocupava mais altura que o proprio conteudo do bloco. Agora a barra tem so a alca de arrastar e um botao `⋮` (43px de altura total), e o menu abre com as mesmas secoes do editor de `/perfil/blocos`: Tamanho, Visualizacao (blocos de jogos), Ajuste da imagem + Imagem (bloco de imagem), Conteudo (texto/links), Fundo, cores e Remover bloco. O modal de recorte fica **fora** do menu de proposito: ele precisa continuar aberto depois que o menu fecha. Sairam junto os controles que viraram orfaos (`editorSelectOpen`, `textColorMenuBlockId`, `isEditorSelectOpen`, `toggleEditorSelect`, `selectedEditorOption`, `isTextColorMenuOpen`, `toggleTextColorMenu`, o tipo `CampoEditor` e 15 regras de CSS) — `selectEditorOption` virou `definirFundo`, que e o unico caso que sobrou (ele semeia cor/gradiente inicial).

O modo de edicao permite reorganizar blocos por arrastar e soltar, mudar tamanho, remover e adicionar blocos. Ele existe somente na aba **Resumo**: ao abrir, as abas Jogos favoritos e Biblioteca ficam indisponiveis e os comandos internos Gerenciar/Ver biblioteca somem para evitar navegacao acidental. A faixa de estatisticas logo abaixo do cabecalho e fixa, portanto nao entra no editor: mostra jogos na biblioteca, horas jogadas, somente conquistas desbloqueadas e icones das plataformas conectadas. A barra "Modo de edicao" fica fixa na parte inferior da tela (nao rola com a pagina), com os botoes Cancelar/Salvar visualmente destacados a direita, separados do seletor de adicionar bloco. Tipos suportados:

- paineis de favoritos pessoais, biblioteca, atividade, platinados (01/08/2026), wishlist da Steam, conquistas recentes e mais jogados (11/09/2026);
- blocos personalizados de texto, imagem e links.

**Conquistas recentes** (11/09/2026) vem pronto do backend em `PerfilPublico.conquistasRecentes` (`ServicoConexoesSteam.conquistasRecentes`, que cruza `steam_library_games` com nome/icone do catalogo). O backend manda ate 20; quantos aparecem depende do que cabe inteiro na altura do bloco (ver "As listas preenchem o bloco sozinhas" no redesign acima). Visibilidade pro visitante segue `mostrarConquistas()`. **Mais jogados** nao tem dado proprio: e `profile.biblioteca` ordenada por horas, e segue `mostrarBiblioteca()`.

**Adicionar bloco e um dropdown, nao uma fileira de botoes (11/09/2026)**: um so seletor "Adicionar" (mesmo componente `.editor-custom-select` dos outros selects do editor) abre um menu — antes eram 7 botoes lado a lado, cada vez mais apertados a cada tipo novo (a wishlist teria sido o 8°). O menu abre **pra cima** (`.editor-select-menu.opens-up`, mesma tecnica ja usada pelo seletor de cor global 🎨): a barra "Modo de edicao" e fixa no rodape, um menu abrindo pra baixo sairia da tela. Opcoes ja adicionadas (favoritos/biblioteca/atividade/platinados/wishlist sao unicos por perfil) ficam desabilitadas em vez de somem, pra o usuario entender que ja existem; "Lista de Desejos (Steam)" so aparece na lista quando `profile.temColecaoWishlistSteam` e verdadeiro (dono tem Steam conectada com wishlist populada).

O painel **Platinados** e derivado da biblioteca (`profile.biblioteca` filtrado no frontend por `conquistasTotal > 0 && conquistasDesbloqueadas >= conquistasTotal`, com prioridade pra `platinumPosition` quando definida e senao ordenado por horas jogadas). So pode existir um bloco desse tipo por perfil. Visibilidade pro visitante depende do toggle **Mostrar biblioteca** (nao existe toggle proprio), ja que os dados vem de la; `ServicoPerfis.blocosPublicos` filtra o bloco do mesmo jeito. Os blocos de **Favoritos** e **Platinados** podem ser reordenados por arrastar direto no proprio bloco (dentro do modo de edicao de layout), sem precisar entrar em "Gerenciar" — reaproveita o `cdkDropList`/`dropFavorite` da aba Gerenciar pros favoritos; pros platinados existe `PUT /api/conexoes/steam/platinados/ordem` + tabela `profile_platinum_order` dedicada (nao reaproveita coluna em `steam_library_games` porque essa tabela e substituida inteira a cada sync da Steam). A ordem dos platinados so persiste pra itens Steam; itens Xbox no mesmo bloco reordenam visualmente mas nao salvam entre sessoes.

O **"Ver todos ›" do bloco de Platinados** (12/09/2026) abre a aba Biblioteca **filtrada nos platinados** (`openLibrary(true)` liga `librarySoPlatinados`, e `libraryGames` passa a partir de `platinumGames` em vez de `profile.biblioteca`) — antes levava pra biblioteca inteira, o oposto do que o bloco mostra (5 jogos viravam 224). O filtro aparece como chip "Só platinados ✕" ao lado dos controles, e nao como filtro escondido: sem ele a lista so apareceria curta e pareceria que a biblioteca encolheu. Clicar no chip, ou usar o "Ver todos" do bloco de Biblioteca, limpa. Busca, plataforma e ordenacao continuam valendo por cima do filtro.

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

> **Atualizacao de 15/09/2026: varredura RELIGADA** (250 jogos a cada 10 min, `conquistas-catalogo-varredura-delay-ms`), a pedido do dono, pra popular os ~18,8 mil pendentes. Medido antes: a rodada de 250 jogos levou 22s (a maioria nao tem conquista e so ganha o carimbo), ~5% do tempo segurando a trava unica de coleta; ~1-2 chamadas a Steam por jogo, abaixo do limite diario da chave. Os gatilhos sob demanda continuam valendo pra jogo aberto antes de a varredura chegar nele.

### Descoberta de jogos novos (15/09/2026)

Ate aqui o catalogo so ganhava jogo novo quando alguem buscava pelo nome (`buscarComFallbackItad`): a coleta de precos atualiza o que ja existe. Caso real: "Resonance: A Plague Tale Legacy" so entrou em 12/09 porque o dono buscou. `ServicoDescobertaJogos` roda a cada 3h (`descoberta-delay-ms`): pega as listas `topsellers`, `popularnew` e `popularcomingsoon` da busca da loja Steam (100 cada, so jogos), descarta app ids ja no catalogo, converte o resto pra id da ITAD (`/lookup/id/shop/61/v1`), importa ate 40 jogos novos por rodada (`/games/info/v2`, so `type=game`, rank = posicao na lista) e ja preenche preco, capa, detalhes e conquistas na mesma rodada. Jogo que ja existia pela ITAD mas sem `steam_app_id` so ganha o app id. Card "Descoberta de jogos novos" no admin, aba Coleta > Precos e Steam. Limite: jogo exclusivo de outra loja (Epic, por exemplo) continua dependendo da busca.

`ServicoSincronizacao.sincronizarRodadaConquistasCatalogo` continua existindo e o botao **"conquistas-catalogo" do painel de admin ainda dispara a varredura manualmente**, pra quando fizer sentido (por exemplo, depois de uma entrada grande de jogos novos no catalogo).

**O gatilho e abrir a pagina do jogo**, via `GET /api/games/{slug}/conquistas`, que a pagina ja chamava. Nao e o botao "Atualizar precos": a aba "Conquistas" so aparece quando ja ha conquistas gravadas (`*ngIf="temConquistas"`), entao quem abre um jogo sem elas nao ve aba nenhuma e nao teria motivo pra imaginar que atualizar precos faria uma surgir — a coleta so aconteceria por acidente.

**Cada jogo e consultado uma vez.** Quem garante sao *duas* condicoes, ambas necessarias, em `listarPendentesConquistas`/`contarPendentesConquistas`/`precisaColetarConquistas`:

1. `achievements_checked_at IS NULL` — o carimbo e gravado quando a Steam devolve **esquema vazio**, e e o que tira da fila os 25.750 jogos que a Steam confirmou nao ter conquista nenhuma.
2. `NOT EXISTS (SELECT 1 FROM game_achievements WHERE game_id = g.id)` — e o que tira da fila o jogo coletado **com sucesso**, porque `RepositorioJogos.salvarConquistas` **nao** carimba `achievements_checked_at` no caminho de sucesso.

**Nao remova a condicao 2 achando que e redundante.** Isso foi feito em 12/09/2026 e quebrou: 13.614 jogos que ja tinham conquistas voltaram a ser considerados pendentes, e como `precisaColetarConquistas` usa a mesma condicao, **toda visita a pagina de um jogo ja coletado disparava uma nova busca do esquema na Steam**. Revertido no mesmo dia.

### Ranking de popularidade (16/09/2026)

`games.rank` ordena a Home, o catalogo (em "Popularidade" e dentro dos destaques), a busca (depois da relevancia) e a fila de precos (rank <= 200 = "relevantes"). Ate 16/09 ele era gravado **uma vez**, na importacao inicial de cada jogo, e nunca mudava: a Home mostrava sempre os mesmos jogos, o topo do catalogo tinha The Darkness II e jogo vindo da busca (Resonance, GTA VI) ficava sem rank, no fim de 190 mil.

`ServicoRankingJogos` roda 1x por dia (`ranking-delay-ms`, card "Ranking de popularidade" no admin) e combina tres fontes pelo **menor** valor (o jogo fica com a melhor posicao que tiver): listas da Steam (`topsellers`, `popularnew`, `popularcomingsoon`, as mesmas da descoberta, convertidas pra id da ITAD), ITAD `/stats/most-popular/v1` (a API so pagina ate o offset 500: no maximo 1.000 jogos) e ITAD `/deals/v2?sort=rank` (25 paginas de 200). So atualiza jogo que ja existe (`RepositorioJogos.atualizarRanksPorItadId`, um UPDATE com `unnest`); jogo fora das listas mantem o rank que tinha. Primeira rodada: 5.555 jogos nas listas, 5.345 com rank novo (Baldur's Gate 3 = 1, Cyberpunk = 2, Elden Ring = 3). Limite: jogo que ainda nao tem pagina na Steam nem aparece nas listas da ITAD (GTA VI em 09/2026) fica sem rank; a busca por relevancia cobre esse caso.

### Recoleta de jogo live-service (12/09/2026)

Jogo que recebe conquista nova depois da nossa coleta (Dead by Daylight e afins) ficava desatualizado pra sempre: as duas condicoes acima, de proposito, nunca trazem de volta um jogo ja coletado. O sintoma aparecia no bloco **Conquistas recentes** do perfil — a conquista desbloqueada nao tinha linha em `game_achievements`, entao vinha sem icone e com nome derivado do `api_name` ("New achievement 334 3"). Medido na epoca: DBD tinha 303 conquistas no catalogo e 311 no esquema da Steam.

O gatilho e `ServicoConexoesSteam.conquistasRecentes`: quando alguma conquista recente **nao tem linha no catalogo**, ele chama `ServicoConquistasSobDemanda.agendarPreenchimentoCompleto(appIds)`, que roda `ServicoCatalogo.preencherTudoDoJogo` — exatamente o que o botao de admin "Preencher tudo agora" faz (metadados Steam forcados + detalhes + conquistas). Refaz o jogo inteiro e nao so as conquistas porque quem ganhou conquista nova normalmente tambem tem capa/descricao/review novos.

Nao passa pela fila de `listarPendentesConquistas` — aquela fila e so pra jogo que nunca foi coletado. Dois freios, porque o gatilho e leitura de perfil e repete muito:

- a trava `emAndamento` (`Set<Long>` concorrente, compartilhada com a coleta por pagina de jogo), pra visitas simultaneas nao duplicarem o trabalho;
- **intervalo minimo de 12h por jogo** (`INTERVALO_MINIMO`, cache em memoria por `steam_app_id`). Necessario porque conquista oculta/removida nao existe nem no esquema da Steam: sem o intervalo, jogo assim seria refeito em cada visita ao perfil, pra sempre. Reiniciar o backend limpa o cache e libera um preenchimento extra por jogo — barato e aceitavel.

Roda no `executorColetaManual`, fora da requisicao, e nunca lanca: e efeito colateral de uma leitura.

**Icone da conquista vinha de um caminho legado** (12/09/2026): `GetSchemaForGame` devolve a URL do icone em `steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/{appId}/{hash}.jpg`, mas a Steam migrou a arte pra `shared.fastly.steamstatic.com/community_assets/images/apps/...` — **mesmo hash, caminho diferente**. O caminho legado continua servindo arte antiga, entao o problema aparecia so nas conquistas novas: 404 no legado, 200 no novo. Medido nos dois casos e nos hosts `fastly`/`akamai` antes de mudar; a propria pagina `steamcommunity.com/stats/{appId}/achievements/` usa o caminho novo. Como `game_achievements.icon_url` guarda so `{appId}/{hash}.jpg` (511.319 linhas, zero absolutas), a correcao foi trocar o prefixo em `IconeConquista.expandir` — conserta todas de uma vez, sem migration. `compactar` passou a aceitar os dois caminhos, pra quando a API comecar a mandar o novo. Efeito colateral bom: o caminho novo devolve a arte em 256px, contra 64px do legado.

No frontend, o `(error)` da `<img>` do bloco cai no mesmo fallback `🏅` do `iconeUrl` null (`iconesConquistaIndisponiveis`, mesma tecnica de `capasSteamIndisponiveis`) — foi o que revelou esse bug e continua como rede de seguranca pra troca de CDN futura.

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

`storage.objects` (bucket `avatars`): escrita, atualizacao e remocao restritas a pasta do proprio usuario (`foldername[1] = auth.uid()`). **Leitura mudou em 13/09/2026** (`sql/20260913_storage_leitura_propria_pasta.sql`, issue #30): havia uma policy de SELECT pra `public` que deixava qualquer um, so com a chave anon, **listar o bucket inteiro** — e cada pasta e o UUID de um usuario (verificado: a listagem anonima devolvia as pastas). Bucket publico nao precisa de SELECT pra servir arquivo por URL, entao a policy virou "usuario logado lista a propria pasta" (usada pela exclusao de conta). Conferido depois: avatar, banner e imagem de bloco seguem com 200; listagem anonima devolve `[]`.

**Ao testar escrita no PostgREST:** um `204` **nao** prova que a escrita passou — ele responde 204 mesmo afetando 0 linhas. So `Prefer: return=representation` e conclusivo: devolve as linhas afetadas, ou `[]` se o RLS bloqueou.

### O que ja estava certo

- **Admin validado no servidor** (`Administradores.exigir`, por UID; desde 13/09/2026 a lista vem de `ADMIN_USER_IDS`, com o UID antigo como padrao). O `adminGuard` do frontend e so cosmetico — quem protege e o backend.
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

O teto de leitura alto nao e frouxidao: o SSR na Vercel chama esta API para renderizar cada pagina, e todas essas chamadas saem de um punhado de IPs da Vercel. Um limite de navegador em GET derrubaria o site sob trafego normal — nao o atacante.

**Token do SSR (13/09/2026, issue #21).** O SSR manda `X-SSR-Token` (`SSR_API_TOKEN`, so no servidor: interceptor em `configuracao/token-ssr.ts`, provido so no `AppServerModule`, nunca no bundle do navegador). Token valido (comparacao em tempo constante) usa um balde proprio de 6.000/min. **O limite de navegador (120/min) so aperta depois que o backend VE o SSR mandando token valido nos ultimos 15 min** — antes disso segue o 600 de sempre. Isso elimina a armadilha de ordem de configuracao (VM com token e Vercel sem ele derrubaria o proprio SSR) e, se a Vercel perder a variavel, o limite volta a afrouxar sozinho. O valor fica em `deploy/oracle/.env` na VM e precisa ser o mesmo na Vercel.

Detalhes que nao sao obvios:

- **Usa a ultima entrada de `X-Forwarded-For`, nao a primeira.** O Caddy *anexa* o peer real ao que veio na requisicao, entao um cliente consegue plantar valores no inicio da lista — mas nunca no fim. Ler a primeira entrada deixaria qualquer um trocar de identidade a cada request e furar o limite por completo. Coberto por teste.
- **A recusa repete o `Access-Control-Allow-Origin`** (so para origem que ja esta na lista permitida). O CORS do Spring e aplicado no handler, depois dos filtros, entao uma resposta cortada no filtro sairia sem ele e o navegador reportaria "erro de CORS" no lugar do 429 — escondendo o motivo real.
- **Nao protege login**: a autenticacao vai direto do navegador para o Supabase, sem passar pelo backend. Forca bruta de senha e limitada pelo Supabase, nao aqui.
- Estado em memoria, por instancia. Basta para uma VM so; com mais de uma, o limite efetivo viraria a soma e seria preciso contador compartilhado.

**Cache de validacao de token** (`ServicoAutenticacao`) — 60s, chaveado pelo **hash SHA-256** do token. Antes era uma chamada ao Supabase por requisicao autenticada.

- O hash existe porque o id do usuario em cache nao serve para se autenticar, mas o token sim: guardar so o hash evita manter credencial reutilizavel viva em memoria, onde um dump de heap a pegaria pronta.
- **So o sucesso e cacheado.** Falha nao entra de proposito: como toda falha e indistinguivel — inclusive "Supabase fora do ar" —, cachear negativo faria uma instabilidade de um segundo virar um minuto de usuarios deslogados. O custo e que token invalido sempre bate no Supabase; quem contem enxurrada disso e o limite de requisicoes, nao o cache.
- Risco aceito: um token continua valido por ate 60s depois de invalidado. E pequeno porque o token de acesso ja e um JWT de ~1h — sair da conta nao o revoga de imediato de qualquer forma. Subir muito esse valor inverte a conta.

### Preparacao pro lancamento (13/09/2026)

Resultado da auditoria de ponta a ponta (issues #15 a #30). Cada item tem o detalhe na propria issue; aqui fica o que muda o jeito de trabalhar no codigo.

- **Erros 4xx chegam na tela** (`TratadorErrosApi`): o Spring omitia o `reason` das `ResponseStatusException`, entao nenhuma mensagem de validacao aparecia pro usuario. Agora sai `{"status": 400, "error": "..."}` — **o texto do `reason` e lido pelo usuario**: escreva em pt-BR, com acento, dizendo o que corrigir. 5xx continua sem detalhe. No frontend, `mensagemDaApi(erro, padrao)`.
- **Parametros publicos normalizados antes do cache** (`ParametrosPublicos`) e **teto de consultas pesadas simultaneas** (`LimiteConsultasPesadas`) — ver "Parametros normalizados e topo unico" na API.
- **Filtros de catalogo sem regex de alternativas** (`TermosSql`): regex `(a|b|c)` custa ~30 µs/linha no Postgres. Lista nova de termos bloqueados vai nas listas das classes, nunca num regex.
- **Blocos do perfil validados no servidor** (`ValidadorBlocos`): gradiente so no formato do editor, imagem so do bucket do proprio usuario (URL externa ja gravada no bloco continua valendo), limites de texto e links.
- **Moderacao**: `profile_reports` + `profiles.blocked_at`, denuncia com login, painel no `/admin/coleta`.
- **Exclusao de conta** (`RepositorioConta`): apaga `auth.users` e as tabelas SEM cascade. **Tabela nova com dado de usuario: ou FK com `ON DELETE CASCADE` pra `auth.users`, ou entra em `RepositorioConta.TABELAS_SEM_CASCADE`.**
- **Steam OpenID**: alem da assinatura, confere `op_endpoint`, `return_to` (com o `state` deste login) e `claimed_id == identity`.
- **CSP no SSR** (`server.ts`): aplicada so com `object-src/base-uri/form-action/frame-ancestors`; a politica completa vai em `Content-Security-Policy-Report-Only`. Integracao nova que carrega script/estilo/iframe de outro dominio: conferir o console e ajustar a Report-Only antes de promover.
- **`GET /error` direto responde 404** (antes 500 com status 999).

### E-mails de conta e recuperacao de senha (15/09/2026)

- **SMTP provisorio**: o Supabase so libera editar os templates com SMTP proprio. Enquanto nao ha dominio, o envio sai pelo Gmail `ofertagamescontato@gmail.com` (senha de app, configurada pelo dono no painel; limite de ~500 e-mails/dia do Gmail). Rate limit de e-mails do Supabase Auth em **60/h** (padrao 30). Na migracao pro dominio (#19/#20), trocar por Resend/Brevo com `nao-responda@dominio`.
- **Templates** em pt-BR com a identidade do site: `supabase/email-templates/` (confirmar cadastro, redefinir senha, link de acesso, trocar e-mail, convite, codigo). Gerados por `gerar.mjs` a partir de um layout unico — editar la e colar de novo no painel (passo a passo e assuntos no README da pasta). Logo e links apontam pra `SITE` do script: trocar na migracao do dominio.
- **Link de recuperacao nao vira login**: o link do e-mail cria sessao (e assim que o Supabase deixa trocar a senha). `AuthService` marca a recuperacao como pendente (`localStorage`, sobrevive a F5) no evento `PASSWORD_RECOVERY` e, enquanto pendente, qualquer navegacao volta pra `/redefinir-senha`. Saidas: salvar a senha nova ou "Prefiro sair e entrar com a senha atual" (logout). Antes dava pra abrir o link e navegar logado sem nunca definir senha.
- **E-mail de contato** nos textos legais: o mesmo Gmail provisorio (`EMAIL_CONTATO` em `configuracao/contato.ts`), junto com o Fale conosco como canal do titular. Trocar por `contato@dominio` na migracao.

### Pendencias conhecidas

- **Protecao de senha vazada desligada** no Supabase Auth — toggle no painel, cruza a senha escolhida com a base do HaveIBeenPwned. E do lado do Supabase, nao do codigo (issue #20).
- **Rate limiting nao cobre login**, porque o login nao passa pelo backend (ver acima).
- **Pagina de perfil so com o esqueleto de loading no SSR** — era o mesmo problema que aparecia no `ng serve` ao dar F5 em `/{handle}`: o app e zoneless e o `await supabase.auth.getSession()` antes da chamada HTTP nao era rastreado, entao o servidor entregava a pagina antes dos dados. Corrigido em 13/09/2026 com `PendingTasks` (issue #22). Se aparecer de novo em outra pagina que faz `await` antes do HTTP, e a mesma causa.

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
- Editor de perfil persistido com blocos (favoritos, biblioteca, atividade, platinados, wishlist Steam, conquistas recentes, mais jogados, texto, links, imagem), titulo editavel nos paineis de dados, upload de imagens JPG/PNG/WebP de ate 2 MB (desde 13/09/2026 **sem** imagem externa por URL — ver "Preparacao pro lancamento"), validado em desktop. O enquadramento de imagens de bloco aceita zoom por scroll/pinca e reposicionamento por arrasto direto na previa, em mouse ou toque. Desde 11/09/2026 a organizacao dos blocos (ordem, visibilidade, tamanho, visualizacao, cores) vive na pagina `/perfil/blocos`, com previa real do rascunho; o modo inline ficou so pro ajuste de imagem.
- Faixa fixa de estatisticas no perfil e galerias responsivas para favoritos pessoais e biblioteca Steam. Cards da Steam tentam a capa horizontal e depois uma capsula alternativa; se nenhuma existir, usam um fallback visual sem imagem quebrada. No hover de um card da biblioteca, "Ver na Steam" sempre aparece e "Ver no catalogo" aparece quando o jogo tem uma entrada correspondente no nosso catalogo (cruzamento em lote por `steam_app_id`).
- Instant Gaming como fonte extra de preco via scraping (sem API publica), com afiliacao propria — ver secao dedicada em "Coletas e Atualizacao de Catalogo".
- Favoritos pessoais com ordenacao manual (arrastar e soltar, persistida) dentro do modo Organizar da aba.
- Colecoes ("Minhas Listas"): modelo N:N, CRUD completo, aba propria no perfil e toggle de privacidade. Jogos sao adicionados por dois caminhos: o modal "Gerenciar jogos" na aba (biblioteca Steam completa + busca no catalogo) e o menu de listas na pagina do jogo (com "Criar nova lista" no topo).
- Jogos platinados na faixa de estatisticas: contagem de jogos com 100% de conquistas, exibida so quando ha pelo menos um; respeita o toggle de conquistas. Usa `trofeu.png` (desde o redesign de 11/09/2026; antes reusava `conquistas.png` em prateado).
- PostgreSQL, Auth/OAuth e Storage foram migrados para o Supabase em Sao Paulo. A Oracle usa o novo banco, executa o scheduler e expoe a API por Caddy/HTTPS. O frontend publicado na Vercel usa o mesmo projeto Supabase. O Render esta desligado; o Supabase antigo permanece somente como rollback temporario.
- Validacao pos-migracao concluida: login Google/Discord, catalogo, perfil, avatares, blocos e scheduler confirmados funcionando na Oracle com o Supabase novo.
- Xbox conectado via OpenXBL (OAuth "Xbox App", nao API key pessoal): login, biblioteca (progresso de conquistas e minutos jogados reais via endpoint de stats em lote) e desconexao. Sincronizacao e sempre upsert-only, nunca apaga jogos ja salvos. **Desconectar apaga a biblioteca Xbox** junto com o vinculo, igual a Steam (decisao de 13/09/2026: desconectar e revogar o consentimento de importar esses dados; antes a biblioteca ficava). Biblioteca combinada Steam+Xbox na mesma lista/perfil, com filtro de plataforma na aba Biblioteca quando ha mais de uma conectada — ver secao "Steam e Xbox".
- Precos com cupom da ITAD (`vouchers=true`): quando existe, o preco com cupom vira o principal exibido, com selo indicando o codigo — ver secao "Precos ITAD".
- Favoritos e Platinados reordenaveis por arrastar direto no bloco do perfil, sem precisar entrar em "Gerenciar" — ver "Editor de perfil".
- Filtro de lojas preferidas nas Configuracoes, aplicado automaticamente no Catalogo — ver "Home, catalogo e monitoramento".
- Historico de preco (90 dias, grava so em mudanca) com grafico na pagina do jogo — ver "Historico de precos".
- Perfil redesenhado (hero com banner, faixa de stats enxuta, abas com icone) + pagina `/perfil/blocos` com previa real do rascunho, toggle Ativo/Oculto funcionando de ponta a ponta e escolha de visualizacao (lista/cards) nos favoritos — ver "Redesign visual do perfil" e "Pagina Editar Blocos".
- Favorito da Steam no perfil linkando pro nosso catalogo quando o jogo existe nele — ver "Perfil publico e privado".
- Recoleta automatica de jogo live-service quando aparece conquista desbloqueada fora do catalogo, rodando o mesmo "Preencher tudo agora" do admin — ver "Recoleta de jogo live-service".
- Pronto pra usuarios reais (13/09/2026): recuperacao de senha (`/redefinir-senha`), exclusao de conta, Politica de Privacidade e Termos (`/privacidade`, `/termos`), denuncia e bloqueio de perfil, login que volta pra onde a pessoa estava (`returnUrl`), cache na CDN, 404/503 reais no SSR, SEO do perfil e JSON-LD do jogo — ver "Preparacao pro lancamento" em Seguranca.
- Fale conosco (`/contato`, 14/09/2026): elogio, sugestao, problema, denuncia ou outro, com a pagina de origem (o campo de e-mail saiu em 14/09: a resposta vai pelo site); aceita anonimo (freios: campo isca, 10/dia por conta, 30/h anonimas no total, limite por IP). Tabela `contact_messages` (`sql/20260914_mensagens_contato.sql`), lida em `/admin/coleta` > "Mensagens abertas". Sem aviso por e-mail: precisa abrir o admin. Resposta do admin entregue no site (sino + "Minhas mensagens"), so pra quem mandou logado. Anexos (ate 3; imagem 8 MB, video 50 MB, tipo conferido pelos bytes) ficam no volume `anexos_contato` da VM, teto de 5 GB, servidos so pro admin; NAO entram no backup.sh.

- Descoberta de jogos novos (a cada 3h), ranking de popularidade diario e varredura de conquistas religada (15-16/09/2026) — ver secoes proprias.
- Busca com siglas, inicio de palavra e relevancia; Gratuitos sem free-to-play; Home com Lancamentos em alta e promocoes dos mais populares (16-18/09/2026).
- Monitoramento de hora em hora com issue de alerta e rastreamento de erros proprio (aba Erros do admin) (15/09/2026) — ver "Quando algo cair".
- E-mails de conta em pt-BR via SMTP provisorio e recuperacao de senha que exige trocar a senha (15/09/2026) — ver "E-mails de conta e recuperacao de senha".

### Em validacao

- O modo de edicao **inline** do perfil (hoje so o ajuste de imagem de bloco) continua sem layout mobile e nao esta sendo trabalhado — prioridade e desktop. A pagina `/perfil/blocos`, que substituiu ele na organizacao dos blocos, **e** responsiva (testada em 390×844: coluna unica, biblioteca e dica abaixo da lista, linha do bloco quebrando com o texto em linha propria).

### Planejado

1. Colecoes concluidas (CRUD, exibicao, modal de adicionar jogos e menu na pagina do jogo). Evolucao futura opcional: reordenar jogos dentro da colecao e reordenar as proprias colecoes.
   - Filtro por ano de lancamento/genero segue inviavel: `games` nao guarda esses campos. Dependeria de coluna nova + backfill via ITAD/Steam.
2. Melhorar a pagina de administracao/observabilidade de coletas e erros ITAD/Steam.
3. Xbox: reordenar platinados por arrastar ainda nao persiste pra itens Xbox (so Steam). Favoritar/link externo tambem continuam Steam-only nos cards de biblioteca.
   - Perfil/editor de blocos: trazer pro editor de `/perfil/blocos` o que so existe na edicao na pagina (recorte de imagem, gradiente, cor global); avisar de rascunho nao salvo ao sair de `/perfil/blocos` (`CanDeactivate`); permitir arrastar blocos direto na previa; desenhar a visualizacao em lista pros outros blocos de jogos (`TIPOS_COM_VISUALIZACAO`).
4. Integrar Eneba depois de aprovar afiliacao — prova de conceito de 17/09/2026 na issue #32: preco sai da busca Algolia deles (sem navegador), mas so 9 de 20 jogos casaram com seguranca e a Eneba ganhou de verdade em 1; esperar resposta do afiliado e pedir feed oficial com id de produto. Instant Gaming ja implementada por scraping (ver secao propria) — melhorar cobertura da varredura (o catalogo pode ter jogos com id acima do que ja foi varrido) e considerar guardar `regular_price` se a pagina deles passar a expor desconto de forma confiavel.
5. Melhorar observabilidade operacional da Oracle: uso de memoria, erros do scheduler e status da API.
6. Depois de alguns dias de estabilidade, exportar um ultimo backup e excluir o projeto Supabase antigo.

## Checklist antes de Publicar

1. Conferir `git status --short` e nunca reverter mudancas do usuario.
2. Para frontend: `cd frontend; npm.cmd run build`.
3. Para backend: `cd backend-java; mvn -o test` (100 testes em 13/09/2026; frontend 41). Nao ha `mvn` no PATH desta maquina nem `mvnw` no repo, mas existe um Maven baixado pelo wrapper em `%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.11-bin\<hash>\apache-maven-3.9.11\bin\mvn.cmd` — da pra chamar por esse caminho e rodar o build/test localmente, sem depender so do CI.
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
