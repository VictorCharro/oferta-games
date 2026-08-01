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
| Banco e Auth | PostgreSQL + Supabase Auth + Storage | Supabase |
| Precos | ITAD API | Consumida pelo backend |
| Perfil gamer | Steam OpenID + Steam Web API | Consumida pelo backend |

URLs de producao atuais:

- Frontend: `https://ofertagames.vercel.app`
- Backend Oracle temporario: `https://api.163.176.220.243.sslip.io`
- Health check Oracle: `https://api.163.176.220.243.sslip.io/actuator/health`

O banco usa o transaction pooler do Supabase. A conversao da `DATABASE_URL` para JDBC e feita pelo backend, com `prepareThreshold=0`, pois prepared statements persistentes nao sao compativeis com esse modo do Supavisor.

## Deploy e Operacao

O `Dockerfile` faz o build Maven em imagem Java 21 e inicia o JAR com limite de heap `-Xmx384m`. O Render esta desligado e nao deve receber novos deploys. Nao existe workflow de sincronizacao recorrente no GitHub Actions.

### Variaveis do backend

| Variavel | Obrigatoria | Uso |
|---|---:|---|
| `DATABASE_URL` | sim | PostgreSQL Supabase pelo pooler |
| `SUPABASE_URL` | sim | Validacao de tokens Supabase |
| `SUPABASE_ANON_KEY` | sim | Validacao de tokens Supabase |
| `ITAD_API_KEY` | sim | Coleta e refresh de ofertas |
| `STEAM_WEB_API_KEY` | sim para Steam | Biblioteca, horas e conquistas Steam |
| `PUBLIC_BACKEND_URL` | sim para Steam | URL publica do backend para retorno OpenID |
| `FRONTEND_URL` | sim para Steam | URL do frontend para redirecionamentos |
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

O workflow `.github/workflows/deploy-oracle.yml` atualiza o backend na VM por SSH em cada push relevante para `master`; exige os segredos `ORACLE_HOST` e `ORACLE_SSH_PRIVATE_KEY` no GitHub. No estado atual (bloqueado desde 18/07/2026, com normalizacao esperada no fim do mes), os GitHub Actions da conta estao bloqueados por cobranca/limite da conta e nao devem ser considerados para deploy ate a normalizacao no GitHub. O deploy manual oficial e:

```powershell
.\scripts\deploy-oracle.ps1 -ChaveSsh "C:\Users\VFulls\Downloads\oferta-games.key"
```

O script conecta na VM, atualiza o `master`, recria somente o container do backend e valida o health check. A chave da VM usada para baixar o repositorio continua somente leitura.

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

O endpoint `POST /api/sync?page=0` e legado: serve para diagnostico/manual e exige `X-Sync-Key`. Nao usar GitHub Actions para processar o catalogo completo.

### Metadados Steam de catalogo

Job separado, em geral a cada 15 minutos:

- Completa capa oficial quando ela esta ausente.
- Ajuda a identificar DLCs quando existe uma oferta Steam correspondente.
- Resolve e persiste `games.steam_app_id` (antes era resolvido a cada execucao e descartado).
- Processa um lote pequeno de jogos pendentes com `games.last_steam_sync_at`.
- Compartilha a mesma trava da coleta de preco.

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
- Frontend: `store-brand.ts` reconhece `/instant.?gaming/i` e usa `store-logos/instant-gaming.svg` (monograma "IG" na cor da marca deles, nao e o logo oficial redistribuido).

### Detalhes e conquistas de catalogo (Sobre/Review/Conquistas)

Dois jobs novos em `ServicoSincronizacao`/`AgendadorColetas`, ambos dependem de `games.steam_app_id` ja preenchido pelo job de metadados Steam acima. So processam jogos com oferta Steam; os demais ficam sem esses dados (mesma limitacao pre-existente de capa/DLC).

- **Detalhes** (`coletarDetalhesJogos`, ~15min): reaproveita a mesma chamada `appdetails` do job de metadados Steam, solicitando `l=brazilian&cc=br`, e extrai descricao curta, generos, desenvolvedores, publicadoras, data de lancamento e screenshots; complementa com `store.steampowered.com/appreviews/{appid}` (nota, positivas, negativas). Grava em `game_details` (1 linha por jogo, upsert). Jogos sem detalhes entram imediatamente na fila; dados existentes com mais de 30 dias sao atualizados novamente.
- **Conquistas de catalogo** (`coletarConquistasCatalogo`, ~3h — schema e % global mudam devagar): `ISteamUserStats/GetSchemaForGame/v2` (precisa de `STEAM_WEB_API_KEY`, chamado com `l=brazilian` desde 19/07/2026 — antes vinha em ingles por faltar esse parametro) mesclado com `ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2` (publico). Grava em `game_achievements` (upsert por `api_name`). Nao tem relacao com `conexoes/AgendadorConquistasSteam`, que sincroniza as conquistas *desbloqueadas por um usuario logado* — este job novo e catalogo-wide, sem usuario.
  - Estado em 19/07/2026: `game_achievements` foi zerada em producao pra reprocessar tudo em portugues, e o job esta temporariamente acelerado (`LIMITE_CONQUISTAS_CATALOGO=250` a cada 2min, em `ServicoSincronizacao`/`AgendadorColetas`, normalmente 15 jogos a cada 3h) ate o backlog de ~2160 jogos com `steam_app_id` ficar praticamente zerado; uma tarefa agendada monitora o progresso via Supabase e reverte os dois valores sozinha quando terminar. Jogos sem nenhuma conquista na Steam ficam perpetuamente pendentes nessa fila (nunca geram linha em `game_achievements`), o que e esperado.
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

## Schema Relevante

### Catalogo

```text
games
  id, itad_id, title, slug, cover_url, rank, is_dlc, steam_app_id
  last_price_sync_at, last_steam_sync_at, created_at

offers
  id, game_id, source, store_name, price, regular_price
  currency, url, updated_at
  UNIQUE (game_id, source, store_name)

game_details
  game_id (PK, FK games), short_description, genres[], developers[]
  publishers[], release_date, screenshots[]
  review_score_desc, review_positive, review_negative
  trailer_url, trailer_thumbnail, about_full, feature_highlights (jsonb)
  categories[], requirements_min, requirements_rec, updated_at

game_achievements
  id, game_id (FK games), api_name, display_name, description
  icon_url, icon_gray_url, global_percent, position
  UNIQUE (game_id, api_name)

game_reviews
  id, game_id (FK games), user_id (FK auth.users), rating (1-5), comentario
  created_at, updated_at
  UNIQUE (game_id, user_id)

game_review_votes
  review_id (FK game_reviews), user_id (FK auth.users), util
  PK (review_id, user_id)

sync_locks
  name, locked_until
```

### Conta, monitoramento e notificacoes

```text
favorites
  user_id, game_id, created_at
  -- representa Jogos Monitorados, nunca favoritos pessoais

price_notifications
  user_id, game_id, previous_price, current_price, store_name
  read_at, created_at
```

### Perfis e Steam

```text
profiles
  user_id, handle, display_name, bio, avatar_url, is_public
  show_game_hours, show_achievements, show_library
  show_favorite_games, show_recent_activity
  avatar_zoom, avatar_position_x, avatar_position_y
  banner_url, banner_zoom, banner_position_x, banner_position_y
  show_collections

steam_connections
  user_id, steam_id, persona_name, avatar_url
  connected_at, last_library_sync_at, last_achievement_sync_at, last_error

steam_library_games
  user_id, app_id, title, playtime_minutes, icon_hash, last_synced_at

steam_game_achievements
  user_id, app_id, unlocked_count, total_count, last_synced_at

profile_favorites
  user_id, game_id, created_at, position

profile_steam_favorites
  user_id, app_id, created_at, position
  -- usado para jogos da biblioteca Steam ausentes do catalogo
  -- position: sequencia manual unica por usuario, combinando as duas tabelas

profile_collections
  id, user_id, name, position, created_at
  -- "Minhas Listas": grupos nomeados de jogos, independentes dos favoritos

profile_collection_items
  id, collection_id, user_id, game_id, app_id, position, created_at
  -- game_id (catalogo) OU app_id (biblioteca Steam), nunca os dois (CHECK)
  -- N:N: o mesmo jogo pode estar em varias colecoes

profile_activities
  user_id, type, game_id, detail, created_at

profile_blocks
  user_id, block_id, block_type, title, content, position, size
  visible, background_type, background_value, overlay_opacity, text_color
```

Apesar da coluna `profile_blocks.visible` existir por compatibilidade, nao ha privacidade por secao: a privacidade e sempre do perfil como um todo e dos controles gerais de dados.

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

As migrations 1 a 23 ja foram aplicadas ao projeto Supabase de producao. A migration 21 marcou 408 detalhes existentes como antigos em 18/07/2026 para a fila substitui-los gradualmente pelo conteudo pt-BR, sem apagar o texto atual durante o processamento. Em outro ambiente, executar as migrations estruturais em ordem antes de publicar o backend. Em especial, a coluna `profile_activities.detail` e obrigatoria para atividade recente detalhada; se ela estiver ausente, a rota de perfil pode retornar HTTP 500. A migration do banner (15) precisa ser aplicada antes do deploy do backend que a usa: o backend seleciona `banner_url`/`banner_zoom`/`banner_position_x`/`banner_position_y` em toda consulta de perfil, entao sem essas colunas qualquer pagina de perfil (propria ou publica) quebra com erro 500. A migration 16 adiciona `position` aos favoritos e tambem precisa ser aplicada antes do deploy do backend que ordena por essa coluna. A 17 cria as colecoes e adiciona `profiles.show_collections`, lido em toda consulta de perfil: sem ela, qualquer pagina de perfil quebra com 500. A migration 22 adiciona `cover_url`/`cover_synced_at` a `steam_library_games` para guardar a capa real de cada jogo (resolvida via appdetails, ja que a Steam mudou o CDN das capas pra um caminho com hash imprevisivel em `shared.akamai.steamstatic.com`); precisa ser aplicada antes do deploy do backend que preenche/le essas colunas. A migration 23 adiciona `instant_gaming_url`/`last_instant_gaming_sync_at` a `games` e cria `instant_gaming_catalog`/`instant_gaming_scan_cursor` (ver secao "Instant Gaming" acima); precisa ser aplicada antes do deploy do backend que usa essas tabelas/colunas.

O bucket publico `avatars` do Supabase Storage guarda avatar, imagens dos blocos e seus fundos. Cada usuario so pode gravar na propria pasta. As politicas RLS de `SELECT`, `INSERT`, `UPDATE` e `DELETE` foram aplicadas ao projeto novo em 15/07/2026; sem elas o Storage retorna HTTP 400 nos uploads. Limite de upload de imagem no frontend: 2 MB, JPG/PNG/WebP. Blocos de imagem e fundos tambem aceitam URL externa `http(s)`.

## API HTTP

### Catalogo

- `GET /api/games?page=&size=&sort=&type=&platform=&minPrice=&maxPrice=&minDiscount=&q=`
  - `sort`: `rank`, `discount`, `price_asc`, `price_desc`.
  - `type`: `all`, `game`, `dlc`.
  - Retorna a oferta minima, incluindo loja e URL quando disponiveis.
  - `RepositorioJogos.listar` e cacheado em memoria (Caffeine, `comum/ConfiguracaoCache`) por 10min por combinacao de parametros, pra nao repetir a query a cada abertura do catalogo. Expira sozinho; nao ha invalidacao manual quando a sincronizacao de precos roda. A pagina Mais Vendidos usa este mesmo endpoint (`getGames`), entao ja se beneficia do cache.
- `GET /api/games/search?q=nome`
- `GET /api/games/{slug}`
- `GET /api/games/{slug}/avaliacoes/steam?cursor=&ordenacao=recent&idioma=brazilian`: pagina de 10 reviews reais da Steam, com texto, recomendacao, tempo jogado, autor e avatar quando disponiveis. Retorna `proximoCursor`, `temMais`, `idiomaConsulta` e `ordenacao`; aceita `recent`, `all` (mais uteis) e `updated`. Resposta publica e cacheada.
- `GET /api/games/{slug}/detalhes` — descricao/generos/devs/publishers/data/screenshots/review, de `game_details`. 404 se o jogo ainda nao foi sincronizado (sem oferta Steam, ou aguardando o job).
- `GET /api/games/{slug}/conquistas` — auth opcional. `{ total, desbloqueadas, percentualConcluido, proxima, conquistas[] }`; cada conquista com `desbloqueada`/`desbloqueadaEm` cruzados com o progresso do visitante logado (ver "Conquistas com progresso pessoal" acima). Sempre 200, mesmo pra jogo inexistente (fica tudo zerado).
- `GET|POST|DELETE /api/games/{slug}/reviews`, `POST /api/games/{slug}/reviews/{id}/voto` — ver "Reviews (Steam + Oferta Games)" acima.
- `POST /api/games/{slug}/refresh`
- `GET /api/deals/top?size=&sort=discount|rank` — usado 2x pela Home (rank e discount); `RepositorioDescontos.listarMelhores` tambem cacheado (Caffeine, `ConfiguracaoCache.CACHE_DESCONTOS`, 10min), pois e uma query com DISTINCT ON + join na tabela `offers` inteira.
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
- `GET|PUT /api/perfis/me/blocos`
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

Toda rota exige token e valida que a colecao e do usuario autenticado; colecao de outro usuario responde 404, nunca 403, para nao revelar a existencia dela. Limites: nome de 1 a 40 caracteres, 20 colecoes por usuario e 200 jogos por colecao.

### Steam e administracao

- `POST /api/conexoes/steam/iniciar`
- `GET /api/conexoes/steam/retorno`
- `GET|DELETE /api/conexoes/steam`
- `GET /api/conexoes/steam/biblioteca` (endpoint do dono; retorna a biblioteca inteira, ate 2000 jogos, para montar colecoes. A previa do perfil publico segue limitada a 100)
- `POST /api/conexoes/steam/sincronizar`
- `GET /api/admin/coleta`
- `POST /api/admin/coleta/precos`
- `POST /api/admin/coleta/steam`
- `GET /actuator/health`

Os endpoints autenticados recebem token Bearer do Supabase. A administracao exige o UID autorizado no backend: `0a6eb06b-756e-4434-899b-33420bed8609`.

## Frontend e UX

### Rotas e navegacao

- `/` home.
- `/catalogo` usa scroll infinito e filtros.
- `/jogo/:slug` mostra ofertas e atualizacao individual.
- `/monitorados` mostra a lista de precos acompanhados. `/favoritos` redireciona por compatibilidade.
- `/perfil` e uma ponte autenticada: resolve/cria o handle e redireciona para a URL canonica.
- `/:handle` e a pagina publica do perfil. As rotas de produto sao reservadas e nao podem ser handles.
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
| **Configuracoes** (`/configuracoes`) | Centralizar opcoes da conta. | Abas separadas: Conta, Conexoes, Preferencias e Privacidade. Nao misturar assuntos entre abas. Preferencias afetam home/catalogo; Privacidade afeta o perfil publico; Conexoes concentra Steam e futura Xbox. |
| **Perfil proprio** (`/perfil` -> `/:handle`) | Personalizar e visualizar o perfil do usuario. | `/perfil` redireciona para o handle canonico. O dono pode editar bio, foto, layout, blocos e pedir atualizacao Steam. A pagina canonica e a mesma que visitantes veem, com controles extras apenas para o dono. |
| **Perfil publico** (`/:handle`) | Compartilhar biblioteca e perfil gamer. | Respeita privacidade geral e dos dados escolhidos. Mostra uma faixa fixa com biblioteca, horas, conquistas desbloqueadas, jogos platinados (so quando ha algum) e icones das plataformas conectadas; abaixo, mostra Resumo, Jogos favoritos, Colecoes e Biblioteca quando liberados. Nunca mostra e-mail, UUID, Jogos Monitorados ou controles de edicao a visitantes. |
| **Administracao de coleta** (`/admin/coleta`) | Acompanhar e disparar jobs internos. | Exclusiva do UID administrador. Exibe status das filas de preco/Steam e permite disparar coleta manual em segundo plano; nao substitui o scheduler. |

### Componentes globais

- **Sidebar:** navegacao principal. O item de monitoramento deve se chamar **Jogos Monitorados** e usar o icone correspondente, nunca o de favoritos pessoais.
- **Topbar:** busca global, alternancia de tema, notificacoes e menu da conta. Ao clicar em Perfil, deve resolver o handle do usuario e navegar diretamente para `/:handle`, nunca permanecer em `/perfil`.
- **Notificacoes:** sino da topbar; lista quedas de preco de Jogos Monitorados, permite marcar como lida ou remover e mostra badge de nao lidas.
- **Card de jogo:** exibe capa, desconto, preco, loja e plataforma quando conhecidos. O icone de acao nele monitora preco, nao adiciona aos favoritos pessoais.

### Home, catalogo e monitoramento

- A home mantem conteudo geral misturado. Se houver plataforma preferida, cria uma secao adicional **Jogos da sua plataforma favorita** abaixo de Jogos Monitorados, sem esconder o restante.
- Preferencias de plataforma, ocultar DLC, desconto minimo e preco maximo preenchem inicialmente os filtros do catalogo; o usuario ainda pode altera-los.
- Cards e detalhe usam o icone `jogos-monitorados.png` para monitoramento de preco. Nunca usar o icone de favorito pessoal nesse fluxo.
- **Colecoes ("Minhas Listas")** sao uma feature separada dos favoritos, na aba **Colecoes** do perfil: grupos nomeados criados pelo dono, com um jogo podendo estar em varias colecoes (N:N). Diferente dos favoritos, aceitam jogos do catalogo que o usuario **nao possui** (ex: "Quero jogar em 2027") alem de jogos da biblioteca Steam. Visibilidade pelo toggle proprio **Mostrar colecoes** em Configuracoes > Privacidade. O dono usa o modo **Organizar** da aba para criar, renomear e excluir; fora dele a aba so exibe as listas.
- Favoritos pessoais sao outra funcionalidade, mostrada no perfil e biblioteca Steam. Na aba **Jogos favoritos**, o dono entra no modo **Organizar** (botao no cabecalho da aba) para reordenar por arrastar e soltar e remover itens; fora desse modo os controles ficam escondidos e os cards navegam normalmente. A ordem e salva automaticamente via `PUT /api/profile-favorites/ordem`; visitantes so visualizam. A ordem manual e unica por usuario e vale para favoritos de catalogo e Steam juntos.

### Perfil publico e privado

- Perfis novos sao publicos por padrao. O dono pode tornar o perfil privado em Configuracoes > Privacidade.
- E-mail, UUID, Jogos Monitorados e dados de autenticacao nunca sao publicos.
- O dono escolhe a exposicao de horas, conquistas, biblioteca, favoritos pessoais, colecoes e atividade recente. O backend filtra a resposta publica.
- O dono na propria URL canonica ve controles de avatar, banner, bio, atualizacao e modo de edicao; visitantes nao veem esses comandos.
- O avatar e o banner do topo do perfil sao salvos no Storage com zoom e posicao persistidos para todos verem o mesmo enquadramento. O ajuste usa o mesmo editor em modal dos blocos de imagem: arrastar com mouse/touch para posicionar e scroll/pinca para zoom, com folga minima de 115% para sempre permitir arrastar em qualquer direcao, calculado em pixels reais (imagem x quadro) tanto no modal quanto na exibicao final. Os botoes "Trocar foto" e "Trocar banner" só aparecem no modo de edicao do perfil.
- A primeira sincronizacao Steam gera somente os resumos. Nas posteriores, novos jogos e conquistas viram atividades individuais.
- A atividade recente e publica por padrao, salvo escolha do dono na privacidade.

### Editor de perfil

O modo de edicao permite reorganizar blocos por arrastar e soltar, mudar tamanho, remover e adicionar blocos. Ele existe somente na aba **Resumo**: ao abrir, as abas Jogos favoritos e Biblioteca ficam indisponiveis e os comandos internos Gerenciar/Ver biblioteca somem para evitar navegacao acidental. A faixa de estatisticas logo abaixo do cabecalho e fixa, portanto nao entra no editor: mostra jogos na biblioteca, horas jogadas, somente conquistas desbloqueadas e icones das plataformas conectadas. A barra "Modo de edicao" fica fixa na parte inferior da tela (nao rola com a pagina), com os botoes Cancelar/Salvar visualmente destacados a direita, separados dos botoes de adicionar bloco. Tipos suportados:

- paineis de favoritos pessoais, biblioteca, atividade e platinados (01/08/2026);
- blocos personalizados de texto, imagem e links.

O painel **Platinados** e derivado da biblioteca (`profile.biblioteca` filtrado no frontend por `conquistasTotal > 0 && conquistasDesbloqueadas >= conquistasTotal`, ordenado por horas jogadas), sem endpoint proprio no backend. Titulo fixo "Platinados", igual biblioteca/atividade; so pode existir um bloco desse tipo por perfil. Visibilidade pro visitante depende do toggle **Mostrar biblioteca** (nao existe toggle proprio), ja que os dados vem de la; `ServicoPerfis.blocosPublicos` filtra o bloco do mesmo jeito.

Cada bloco pode usar fundo padrao, cor solida, gradiente ou imagem, alem de cor de texto hexadecimal livre. Cor solida e texto aceitam seletor visual e digitacao direta de `#RRGGBB`; o gradiente e montado visualmente por duas cores, sem exigir CSS. A opcao de texto fica dentro do menu de fundo e altera somente o conteudo do card, nunca os controles do editor. A imagem de fundo e escolhida por um comando explicito e enviada ao bucket `avatars`; nao existe privacidade por bloco. Blocos personalizados de imagem abrem um editor de enquadramento antes de salvar, exibido como modal grande sobre um fundo escurecido (nao mais embutido na lista de blocos), com pre-visualizacao ampla; o ajuste e feito arrastando a imagem com o mouse/touch para posicionar e girando o scroll (ou pinca no mobile) para dar zoom, sem sliders. O zoom minimo aplica uma pequena folga (115%) sobre o enquadramento padrao para garantir espaco de arraste em qualquer direcao, independente da proporcao da imagem enviada. O enquadramento (zoom e posicao) e calculado com base no tamanho real da imagem e do quadro (nao mais via `object-position` + `transform: scale`, que ficava preso na mesma janela de corte e podia travar um dos eixos do arraste); o mesmo calculo e usado tanto no editor quanto na exibicao final do bloco no perfil, garantindo que o resultado salvo seja igual ao que o dono ajustou. Esses dados sao persistidos junto da URL em formato compativel com blocos antigos que guardavam somente a URL. O campo "Usar URL" sempre abre vazio, mesmo quando o bloco ja tem uma imagem: ele nunca preenche com a URL interna do Supabase Storage, que nao deve ser exposta ao usuario. O editor de links separa titulo e lista de URLs no mesmo padrao visual dos demais campos.

Blocos de texto e links respeitam limites conforme o tamanho escolhido: pequeno (`42` caracteres de titulo e `180` de conteudo), medio (`64` e `420`), largo (`88` e `800`) e completo (`120` e `1400`). O conteudo aplica quebra de palavras longas para nunca vazar horizontalmente do card.

Os tamanhos sao composicoes diferentes, e nao apenas escala. Nos blocos de jogos, o pequeno mostra 1 card por linha, o medio 2, o largo 3 e o completo 4; os cards crescem verticalmente quando houver mais itens. A quantidade de itens exibida no bloco vem sempre do tamanho escolhido (pequeno 1, medio 4, largo 6, completo 8) e nao e configuravel separadamente. Favoritos pessoais e biblioteca usam cards visuais com capa, titulo e dados relevantes. Favoritos Steam usam a capa horizontal oficial `header.jpg`, igual aos cards da biblioteca, e exibem plataforma, horas jogadas e percentual de conquistas; favoritos do catalogo exibem a capa e o menor preco conhecido. O bloco de Biblioteca tem o comando **Ver biblioteca** no canto superior direito e abre a aba completa, que permite busca, ordenacao e favoritar jogos Steam mesmo quando eles nao existem no catalogo.

Os dropdowns de tamanho e fundo devem seguir o mesmo padrao visual do filtro de ordenacao do catalogo, nao usar `select` nativo. Links personalizados usam uma linha por item no formato `Nome - endereco.com` ou `Nome - https://url`; enderecos sem protocolo recebem `https://` automaticamente e devem abrir como links reais. No perfil publico, cada link renderiza como um card empilhado (nao mais uma pill inline), com o nome a esquerda e um icone de link externo a direita.

### Steam e Xbox

- Steam esta funcional por OpenID: nao solicitar Steam ID ou URL manualmente.
- Sincroniza biblioteca, horas totais/por jogo e conquistas gradualmente via Steam Web API.
- A aba Biblioteca busca, ordena por tempo/nome/conquistas e permite favoritar itens Steam ausentes do catalogo.
- Wishlist Steam nao e coletada.
- Xbox permanece planejado. Nao usar scraping nem API nao oficial: nao ha um fluxo publico equivalente ao Steam OpenID + Web API para importar a biblioteca de qualquer conta.

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
    conexoes/            Steam OpenID e sincronizacao
    descontos/           home e melhores descontos
    favoritos/           Jogos Monitorados
    favoritosperfil/     favoritos pessoais
    colecoesperfil/      colecoes ("Minhas Listas") de jogos
    jogos/               catalogo, busca, detalhe e refresh
    notificacoes/        alertas de preco
    perfis/              perfil publico, avatar e blocos
    sincronizacao/       scheduler, ITAD e locks
    steam/               metadados Steam de catalogo

frontend/src/app/
  components/            sidebar, topbar, cards e carrosseis
  guards/                auth.guard
  pages/                 home, catalog, game-detail, profile, settings,
                         favorites/monitorados, login, search e outras
  services/              API, Auth, Supabase, tema, preferencias,
                         favoritos, favoritos pessoais e perfis
```

Convencao obrigatoria no backend: classes, pacotes, metodos e variaveis em portugues. Marcas e contratos JSON podem manter termos externos, por exemplo ITAD, Steam, Bearer, `coverUrl` e `minPrice`.

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
- Editor de perfil persistido com blocos (favoritos, biblioteca, atividade, texto, links, imagem), upload de imagens JPG/PNG/WebP de ate 2 MB e imagens externas por URL `http(s)`, validado em desktop. O enquadramento de imagens de bloco aceita zoom por scroll/pinca e reposicionamento por arrasto direto na previa, em mouse ou toque.
- Faixa fixa de estatisticas no perfil e galerias responsivas para favoritos pessoais e biblioteca Steam. Cards da Steam tentam a capa horizontal e depois uma capsula alternativa; se nenhuma existir, usam um fallback visual sem imagem quebrada.
- Favoritos pessoais com ordenacao manual (arrastar e soltar, persistida) dentro do modo Organizar da aba.
- Colecoes ("Minhas Listas"): modelo N:N, CRUD completo, aba propria no perfil e toggle de privacidade. Jogos sao adicionados por dois caminhos: o modal "Gerenciar jogos" na aba (biblioteca Steam completa + busca no catalogo) e o menu de listas na pagina do jogo (com "Criar nova lista" no topo).
- Jogos platinados na faixa de estatisticas: contagem de jogos com 100% de conquistas, exibida so quando ha pelo menos um; respeita o toggle de conquistas. Ainda sem icone proprio (reusa `conquistas.png` em prateado).
- PostgreSQL, Auth/OAuth e Storage foram migrados para o Supabase em Sao Paulo. A Oracle usa o novo banco, executa o scheduler e expoe a API por Caddy/HTTPS. O frontend publicado na Vercel usa o mesmo projeto Supabase. O Render esta desligado; o Supabase antigo permanece somente como rollback temporario.
- Validacao pos-migracao concluida: login Google/Discord, catalogo, perfil, avatares, blocos e scheduler confirmados funcionando na Oracle com o Supabase novo.

### Em validacao

- O layout mobile do editor de perfil ainda nao e responsivo e nao esta sendo trabalhado por enquanto (prioridade e desktop).

### Planejado

1. Colecoes concluidas (CRUD, exibicao, modal de adicionar jogos e menu na pagina do jogo). Evolucao futura opcional: reordenar jogos dentro da colecao e reordenar as proprias colecoes.
   - Filtro por ano de lancamento/genero segue inviavel: `games` nao guarda esses campos. Dependeria de coluna nova + backfill via ITAD/Steam.
2. Melhorar a pagina de administracao/observabilidade de coletas e erros ITAD/Steam.
3. Implementar Xbox somente com um caminho oficial suportado (pausado ate acesso ao Azure).
4. Integrar Eneba depois de aprovar afiliacao. Instant Gaming ja implementada por scraping (ver secao propria) — melhorar cobertura da varredura (o catalogo pode ter jogos com id acima do que ja foi varrido) e considerar guardar `regular_price` se a pagina deles passar a expor desconto de forma confiavel.
5. Melhorar observabilidade operacional da Oracle: uso de memoria, erros do scheduler e status da API.
6. Depois de alguns dias de estabilidade, exportar um ultimo backup e excluir o projeto Supabase antigo.

## Checklist antes de Publicar

1. Conferir `git status --short` e nunca reverter mudancas do usuario.
2. Para frontend: `cd frontend; npm.cmd run build`.
3. Para backend: `cd backend-java; mvn -q -DskipTests package` quando Maven estiver disponivel.
4. Conferir se uma migration nova precisa ser aplicada no Supabase antes do deploy.
5. Conferir CORS quando uma rota `PUT`, `PATCH` ou `POST` nova for adicionada.
6. Validar em producao: catalogo, detalhes, monitorados, perfil proprio, perfil anonimo e conexao Steam quando afetados.
7. O deploy manual `scripts/deploy-oracle.ps1` valida a saude pela URL HTTPS publica do Caddy; a porta `8080` nao e exposta diretamente na VM.

## Regras de Seguranca e Produto

- Sem scraping de lojas ou plataformas, com uma excecao explicita: **Instant Gaming** (ver secao propria acima), porque nao tem API nem esta no ITAD e o contato oficial pedindo acesso foi negado. Qualquer nova excecao dessas deve ser decidida caso a caso, nao vira regra geral.
- Sem multi-moeda funcional por enquanto.
- Sem exibir dados privados de perfil na rota publica.
- Nao reintroduzir sincronizacao recorrente pelo GitHub Actions.
- Nao colocar chaves do backend no frontend.
- Nao transformar favoritos pessoais em Jogos Monitorados nem o contrario.
