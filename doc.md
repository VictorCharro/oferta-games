# doc.md

## Migração backend Java

- Decisão: manter frontend Angular no Vercel e Supabase como PostgreSQL/Auth.
- Novo backend em criação: `backend-java`, com Java 21 + Spring Boot 3.
- Hospedagem planejada: VM Oracle Cloud Always Free, preferencialmente `VM.Standard.A1.Flex`.
- Limite Always Free relevante para o backend: até 2 OCPUs e 12 GB de memória no total da tenancy para Ampere A1; 200 GB de Block Volume Always Free.
- Estratégia: migrar endpoint por endpoint mantendo o mesmo contrato HTTP do backend Node atual.
- Endpoints Java iniciados: `GET /api/games`, `GET /api/games/search`, `GET /api/games/{slug}`, `GET /api/deals/top`, `GET/POST/DELETE /api/favorites`.

> Este arquivo DEVE ser atualizado sempre que houver uma mudança significativa no projeto (nova decisão de arquitetura, nova fonte de dados, nova tecnologia, mudança de schema, etc). É a fonte de verdade sobre o que o projeto é e onde ele está.

## Objetivo do projeto

Site de catálogo de promoções de jogos. A ideia central: o usuário entra, vê uma lista de jogos com o **menor preço encontrado entre várias lojas**, clica no jogo e vê todas as ofertas daquele jogo ordenadas por preço (menor primeiro), com link direto pra loja.

Não é uma loja própria — é um agregador/comparador de preços.

## Stack

- **Backend:** Node.js + TypeScript, serverless functions no Vercel.
- **Frontend:** Angular (última versão estável), hospedado no Vercel.
- **Banco:** PostgreSQL no Supabase. Conexão via transaction pooler (porta 6543).
- **Auth:** Supabase Auth (email/senha). Frontend usa `@supabase/supabase-js`.

## Deploy

Dois projetos separados no Vercel, mesmo repositório:

| Projeto | Root Directory |
|---|---|
| Backend | `backend` |
| Frontend | `frontend` |

Sincronização de preços: GitHub Actions chama `POST /api/sync` a cada 6h, protegido por `X-Sync-Key`.

## Variáveis de ambiente (backend)

| Variável | Descrição |
|---|---|
| `DATABASE_URL` | URL do pooler do Supabase — formato `postgresql://...` |
| `ITAD_API_KEY` | Chave da API do IsThereAnyDeal |
| `SYNC_SECRET_KEY` | Chave secreta para o endpoint `/api/sync` |
| `SUPABASE_URL` | URL do projeto Supabase, usada para validar o token do usuário nos endpoints de favoritos |
| `SUPABASE_ANON_KEY` | Chave anônima do Supabase (pública), usada com `supabase.auth.getUser(token)` para autenticar requisições |

## Fontes de dados de preços

| Fonte | Status | Como integra |
|---|---|---|
| **IsThereAnyDeal (ITAD)** | Fonte principal, em uso | API oficial. Lojas configuradas: Nuuvem (50), Fanatical (6), GreenManGaming (36), Humble Store (37), GamersGate (24), IndieGala (42), 2game (19), Steam (61), Epic (16), Blizzard (4), EA Store (52), Microsoft Store (48), Ubisoft Store (62). |
| **Eneba** | Planejada | Feed de afiliados XML/CSV após aprovação no cadastro. |
| **Instant Gaming** | Sem integração automática | Aguardando aprovação no programa de afiliados deles. |

Todas as fontes gravam na mesma tabela `offers`, diferenciadas pela coluna `source`.

## Schema do banco

```sql
games
  id            bigserial PK
  itad_id       uuid UNIQUE NULL
  title         text NOT NULL
  slug          text UNIQUE NOT NULL
  cover_url     text NULL
  rank          integer NULL         -- posição no feed ITAD (popularidade)
  is_dlc        boolean NULL         -- classificação real via Steam (NULL = ainda não verificado, cai no heurístico por título)
  created_at    timestamptz DEFAULT now()

offers
  id            bigserial PK
  game_id       bigint FK -> games.id
  source        text NOT NULL        -- 'itad', 'eneba', 'manual'
  store_name    text NOT NULL        -- 'Steam', 'Nuuvem', 'Eneba'...
  price         numeric(10,2) NOT NULL
  regular_price numeric(10,2) NULL
  currency      text NOT NULL DEFAULT 'BRL'
  url           text NOT NULL
  updated_at    timestamptz NOT NULL
  UNIQUE (game_id, source, store_name)

favorites
  id            bigserial PK
  user_id       uuid NOT NULL       -- referencia auth.users(id) do Supabase
  game_id       bigint FK -> games.id
  created_at    timestamptz DEFAULT now()
  UNIQUE (user_id, game_id)
```

- Menor preço calculado via query (`MIN(price)`), não armazenado.
- `rank` populado durante o sync — posição no feed ITAD, menor = mais popular.
- Somente BRL por enquanto. Multi-moeda é possibilidade futura.

## Endpoints da API

- `GET /api/games?page=0&size=20&sort=rank&type=all&minPrice=&maxPrice=` — lista paginada com filtros.
  - `sort`: `rank` (padrão), `discount`, `price_asc`, `price_desc`
  - `type`: `all`, `game`, `dlc`
  - Ordenação padrão: top 200 por rank com desconto ativo sobem ao topo
- `GET /api/games/search?q=nome` — busca jogos no banco; se não achar, busca na ITAD e insere automaticamente.
- `GET /api/games/{slug}` — detalhe do jogo + todas as ofertas ordenadas por preço.
- `POST /api/games/{slug}/refresh` — atualiza preços de um jogo específico na ITAD.
- `GET /api/deals/top?size=20&sort=discount` — melhores descontos. `sort=rank` retorna famosos com desconto deduplicados por jogo.
- `POST /api/sync?page=0` — dispara busca de uma página de deals da ITAD (uso interno/Actions).
- `GET /api/favorites` — lista jogos favoritados do usuário autenticado (header `Authorization: Bearer <token>`).
- `POST /api/favorites` — adiciona jogo aos favoritos (`{ slug }`).
- `DELETE /api/favorites/{slug}` — remove jogo dos favoritos.

## Estrutura de pastas

```
/backend
  api/
    games/
      index.ts         → GET /api/games
      search.ts        → GET /api/games/search?q=
      [slug].ts        → GET /api/games/:slug
      [slug]/
        refresh.ts     → POST /api/games/:slug/refresh
    deals/
      top.ts           → GET /api/deals/top
    sync/
      index.ts         → POST /api/sync
    favorites/
      index.ts         → GET/POST /api/favorites
      [slug].ts        → DELETE /api/favorites/:slug
  lib/
    db.ts              → conexão Supabase
    auth.ts            → valida token do usuário via Supabase Auth
    steam.ts           → extrai appid da URL da oferta Steam, deriva capa via CDN e classifica DLC via appdetails
  scripts/
    sync.ts            → script de sync completo (rodado pelo GitHub Actions)

/frontend              → Angular (NgModule, não standalone)
  src/app/
    pages/
      home/            → tela inicial: banner/carrossel de melhores ofertas (autoplay) + seções em carrossel (jogos mais famosos, favoritos, grátis da semana, maiores descontos, maiores descontos em DLCs)
      catalog/         → catálogo com filtros (sort, tipo, faixa de preço, toggle de grade), scroll infinito
      best-sellers/    → mais populares por rank
      game-detail/     → detalhe do jogo + comparação de preços
      free-games/      → jogos gratuitos (discountPct=100)
      search/          → busca de jogos
      login/           → login e cadastro (Supabase Auth, Google e Discord OAuth)
      profile/         → editar nome do perfil
      settings/        → alterar senha (exige senha atual), encerrar sessão
      favorites/       → jogos favoritados pelo usuário
    components/
      sidebar/         → navegação lateral com ícones PNG
      topbar/          → busca central, toggle de tema, avatar/dropdown ou botões login
      game-card/       → card reutilizável com badge de desconto, DLC e botão de favoritar
      deals-carousel/  → carrossel horizontal reutilizável de jogos (usado nas seções da home), setas aparecem só quando há overflow, scroll animado com easing
    services/
      game.ts          → chamadas à API do backend
      auth.ts          → AuthService com Supabase Auth (email/senha, Google, Discord)
      favorites.ts     → FavoritesService, sincroniza favoritos com o backend
      supabase.ts      → cliente Supabase
      theme.ts         → toggle dark/light mode
      filters.ts       → isDlc() heurística por título, resolveDlc() prioriza is_dlc real vindo da API
    guards/
      auth.guard.ts    → redireciona para /login se não autenticado

/.github/workflows/
  sync.yml             → roda scripts/sync.ts todo dia às 03:00 UTC
```

## Decisões de design

- **Cor principal:** `#29A8E0` (azul)
- **Ícones:** PNGs em `frontend/public/` (home, catalogo, mais-vendidos, gratuito, favorito, perfil, configuracoes, logout, pesquisar, notificacao, sol-tema-claro, lua-tema-escuro, logo)
- **DLC detection:** classificação real via Steam quando disponível (`games.is_dlc`, preenchida pelo `backend/lib/steam.ts` a partir do campo `type` do endpoint público `store.steampowered.com/api/appdetails`, sem precisar de chave); cai para heurística por regex no título (`isDlc()`/`resolveDlc()` em `filters.ts`) enquanto `is_dlc IS NULL`. Backfill roda no sync completo (até 300 jogos/execução, throttled) e no refresh individual do jogo
- **Deduplicação de deals:** `DISTINCT ON (g.id)` mantém apenas a oferta mais barata por jogo
- **Catálogo rotativo:** top 200 por rank com desconto ativo sobem ao topo — muda conforme promoções do dia
- Página de login sem sidebar/topbar (app shell oculto em `/login`)
- **Home:** banner com autoplay (6s, pausa quando a aba fica em segundo plano via `visibilitychange`) sorteando 5 entre os top 50 por rank, linkando direto pro jogo (sem botão externo), com transição deslizante via `transform`; seções secundárias em carrossel de até 15-20 jogos, ordenadas: Jogos mais famosos (top 50 por rank, embaralhados) → Favoritos (só se logado e com favoritos) → Grátis da semana → Maiores descontos (jogos) → Maiores descontos (DLCs), filtradas por `rank != null` para relevância
- **Capa ausente:** fallback visual em `no-cover.svg` (ícone da marca sobre fundo escuro); no backend, se a ITAD não retorna capa mas há oferta Steam, deriva a capa oficial extraindo o `appid` da própria URL da oferta e montando `cdn.akamai.steamstatic.com/steam/apps/{appid}/header.jpg` (sem scraping nem chamada extra de API) — aplicado no sync completo e no refresh individual do jogo
- **Catálogo:** scroll infinito via `window:scroll` com throttle por `requestAnimationFrame` (não usa `IntersectionObserver` — instável com o `position: sticky` do painel de filtros); rechecagem automática após cada carga para o caso do usuário continuar dentro da zona de gatilho

## O que NÃO fazer

- Sem scraping de sites — frágil e viola termos de uso.
- Sem multi-moeda funcional.
- Sem Docker.
- Sem cron interno — sincronização sempre via GitHub Actions externo.

## Estado atual

- [x] Arquitetura definida
- [x] Backend Node.js/TypeScript estruturado (endpoints + integração ITAD)
- [x] Deploy configurado no Vercel (backend e frontend)
- [x] GitHub Actions configurado (sync completo diário às 03:00 UTC)
- [x] Todas as telas do Angular implementadas
- [x] Autenticação com Supabase Auth (login, cadastro, perfil, configurações)
- [x] Filtros no catálogo (sort, tipo, faixa de preço, toggle de grade)
- [x] Página de gratuitos (/gratuitos)
- [x] DLC badge e detecção automática por título
- [x] rank populado no sync para ordenação por popularidade
- [x] Login social com Google e Discord (Supabase Auth OAuth)
- [x] Página de favoritos (tabela `favorites` + endpoints `/api/favorites`)
- [ ] Integração com Eneba
- [ ] Integração com Instant Gaming (aguardando afiliados)

> Atualize esta seção conforme cada item avançar ou novas decisões forem tomadas.
