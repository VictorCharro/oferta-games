# doc.md

> Este arquivo DEVE ser atualizado sempre que houver uma mudança significativa no projeto: decisão de arquitetura, fonte de dados, tecnologia, schema, deploy ou contrato de API. É a fonte de verdade sobre o que o projeto é e onde ele está.

## Objetivo do projeto

Site de catálogo de promoções de jogos. O usuário entra, vê uma lista de jogos com o menor preço encontrado entre várias lojas, clica no jogo e vê todas as ofertas daquele jogo ordenadas por preço, com link direto para a loja.

Não é uma loja própria. É um agregador/comparador de preços.

## Stack

- **Backend:** Java 21 + Spring Boot 3 em `backend-java`.
- **Frontend:** Angular, hospedado no Vercel.
- **Banco:** PostgreSQL no Supabase. Conexão via transaction pooler (porta 6543).
- **Auth:** Supabase Auth (email/senha e OAuth). Frontend usa `@supabase/supabase-js`; backend valida Bearer token via Supabase Auth.
- **Deploy backend:** Render via Docker.

## Deploy

| Parte | Onde roda |
|---|---|
| Backend | Render |
| Frontend | Vercel |
| Banco/Auth | Supabase |

O backend usa `backend-java/Dockerfile`. O Render pode ser criado manualmente ou via `render.yaml`.

Configuração no Render:

| Campo | Valor |
|---|---|
| Runtime | Docker |
| Root Directory | `backend-java` |
| Health Check Path | `/actuator/health` |
| Plan | Free por enquanto |

Sincronização de preços: GitHub Actions chama `POST /api/sync` a cada 6h, protegido por `X-Sync-Key`.

Keep alive: GitHub Actions chama `/actuator/health` a cada 10 minutos usando `BACKEND_URL`.

Secrets dos workflows:

| Secret | Descrição |
|---|---|
| `BACKEND_API_URL` | URL pública do backend Java, sem barra final |
| `BACKEND_URL` | URL pública do backend Java usada no keep alive |
| `SYNC_SECRET_KEY` | Chave secreta usada no header `X-Sync-Key` |

## Variáveis de ambiente do backend

| Variável | Descrição |
|---|---|
| `DATABASE_URL` | URL do pooler do Supabase, formato `postgresql://...` |
| `ITAD_API_KEY` | Chave da API do IsThereAnyDeal |
| `SYNC_SECRET_KEY` | Chave secreta para o endpoint `/api/sync` |
| `SUPABASE_URL` | URL do projeto Supabase |
| `SUPABASE_ANON_KEY` | Chave anônima do Supabase usada para validar tokens |
| `CORS_ALLOWED_ORIGINS` | Origens permitidas separadas por vírgula |
| `PORT` | Porta HTTP do Spring Boot, padrão `8080` |

## Fontes de dados de preços

| Fonte | Status | Como integra |
|---|---|---|
| **IsThereAnyDeal (ITAD)** | Fonte principal, em uso | API oficial. Lojas configuradas: Nuuvem (50), Fanatical (6), GreenManGaming (36), Humble Store (37), GamersGate (24), IndieGala (42), 2game (19), Steam (61), Epic (16), Blizzard (4), EA Store (52), Microsoft Store (48), Ubisoft Store (62). |
| **Steam** | Complementar | Usada para derivar capa oficial e classificar DLC quando há oferta Steam. |
| **Eneba** | Planejada | Feed de afiliados XML/CSV após aprovação no cadastro. |
| **Instant Gaming** | Sem integração automática | Aguardando aprovação no programa de afiliados deles. |

Todas as fontes gravam na tabela `offers`, diferenciadas pela coluna `source`.

## Schema do banco

```sql
games
  id            bigserial PK
  itad_id       uuid UNIQUE NULL
  title         text NOT NULL
  slug          text UNIQUE NOT NULL
  cover_url     text NULL
  rank          integer NULL
  is_dlc        boolean NULL
  created_at    timestamptz DEFAULT now()

offers
  id            bigserial PK
  game_id       bigint FK -> games.id
  source        text NOT NULL
  store_name    text NOT NULL
  price         numeric(10,2) NOT NULL
  regular_price numeric(10,2) NULL
  currency      text NOT NULL DEFAULT 'BRL'
  url           text NOT NULL
  updated_at    timestamptz NOT NULL
  UNIQUE (game_id, source, store_name)

favorites
  id            bigserial PK
  user_id       uuid NOT NULL
  game_id       bigint FK -> games.id
  created_at    timestamptz DEFAULT now()
  UNIQUE (user_id, game_id)
```

- Menor preço é calculado via query (`MIN(price)`), não armazenado.
- `rank` vem do feed ITAD; menor = mais popular.
- Somente BRL por enquanto.

## Endpoints da API

- `GET /api/games?page=0&size=20&sort=rank&type=all&minPrice=&maxPrice=&q=`
  - `sort`: `rank`, `discount`, `price_asc`, `price_desc`
  - `type`: `all`, `game`, `dlc`
  - Ordenação padrão: top 200 por rank com desconto ativo sobem ao topo.
- `GET /api/games/search?q=nome`
  - Busca primeiro no banco.
  - Se não achar, busca na ITAD e insere os jogos encontrados.
- `GET /api/games/{slug}`
  - Retorna detalhe do jogo e ofertas ordenadas por preço.
- `POST /api/games/{slug}/refresh`
  - Atualiza preços do jogo na ITAD.
  - Complementa capa e classificação DLC via Steam quando possível.
- `GET /api/deals/top?size=20&sort=discount`
  - Retorna melhores descontos deduplicados por jogo.
  - `sort=rank` prioriza jogos mais famosos com desconto.
- `POST /api/sync?page=0`
  - Sincroniza uma página de ofertas da ITAD.
  - Exige header `X-Sync-Key`.
  - Não executa backfill Steam durante o sync no Render free, para manter o processo leve.
- `GET /api/favorites`
  - Lista jogos favoritados do usuário autenticado.
- `POST /api/favorites`
  - Adiciona jogo aos favoritos (`{ slug }`).
- `DELETE /api/favorites/{slug}`
  - Remove jogo dos favoritos.
- `GET /actuator/health`
  - Health check usado por Render e keep alive.

## Estrutura de pastas

```text
/backend-java
  Dockerfile
  pom.xml
  src/main/java/com/ofertagames/backend/
    AplicacaoOfertaGames.java
    autenticacao/       -> valida Bearer token via Supabase Auth
    comum/              -> utilitários compartilhados
    configuracao/       -> CORS e conexão PostgreSQL
    descontos/          -> endpoint /api/deals/top
    favoritos/          -> endpoints /api/favorites
    itad/               -> cliente e modelos da API ITAD
    jogos/              -> catálogo, detalhe, busca e refresh
    saude/              -> endpoint /actuator/health
    sincronizacao/      -> endpoint /api/sync
    steam/              -> capa oficial e detecção de DLC

/frontend
  src/app/
    pages/
      home/
      catalog/
      best-sellers/
      game-detail/
      free-games/
      search/
      login/
      profile/
      settings/
      favorites/
    components/
      sidebar/
      topbar/
      game-card/
      deals-carousel/
    services/
      game.ts            -> chamadas para `https://oferta-games.onrender.com/api`
      auth.ts
      favorites.ts       -> favoritos via backend Render
      supabase.ts
      theme.ts
      filters.ts
      store-brand.ts      -> resolve logos locais e plataformas inferidas pelo nome da loja
    guards/
      auth.guard.ts

/.github/workflows/
  keepalive.yml         -> chama GET /actuator/health
  sync.yml              -> chama POST /api/sync em páginas sucessivas
```

## Padrão de código do backend

- Classes, pacotes, métodos e variáveis em português.
- Exemplos: `ControladorJogos`, `RepositorioJogos`, `ServicoCatalogo`, `ServicoAutenticacao`.
- Marcas, termos externos e contrato JSON podem manter o nome original: `ITAD`, `Steam`, `Spring`, `Bearer`, `coverUrl`, `minPrice`, `regularPrice`, `discountPct`.

## Decisões de design e produto

- **Cor principal:** `#29A8E0` (azul).
- **Ícones:** PNGs em `frontend/public/`.
- **Logos de loja/plataforma:** SVGs locais em `frontend/public/store-logos/` e `frontend/public/platform-logos/`, resolvidos no frontend por `store-brand.ts` a partir de `storeName`.
- **Plataformas:** enquanto o backend não persiste `platforms/drm` do ITAD, o frontend infere PC, Xbox e PlayStation pelo nome da loja.
- **DLC detection:** `games.is_dlc` é preenchido via Steam quando há oferta Steam; enquanto `is_dlc IS NULL`, o frontend usa heurística por título.
- **Deduplicação de deals:** `DISTINCT ON (g.id)` mantém apenas a oferta mais barata por jogo.
- **Catálogo rotativo:** top 200 por rank com desconto ativo sobem ao topo.
- **Login:** página de login sem sidebar/topbar.
- **Home:** banner com autoplay e seções em carrossel.
- **Capa ausente:** fallback visual em `no-cover.svg`; backend tenta preencher capa oficial da Steam quando possível.
- **Catálogo:** scroll infinito via `window:scroll` com throttle por `requestAnimationFrame`.

## O que NÃO fazer

- Sem scraping de sites.
- Sem multi-moeda funcional por enquanto.
- Sem cron interno; sincronização é acionada externamente pelo GitHub Actions.

## Estado atual

- [x] Backend Spring Boot estruturado em `backend-java`
- [x] Endpoints de catálogo, busca, detalhe, refresh, descontos, sync e favoritos implementados
- [x] Integração ITAD implementada
- [x] Complemento Steam para capa/DLC implementado
- [x] Supabase PostgreSQL mantido como banco
- [x] Supabase Auth usado nos favoritos
- [x] Frontend Angular implementado
- [x] Dockerfile do backend Java configurado para Render
- [x] GitHub Actions configurado para sync e keep alive
- [ ] Deploy no Render
- [ ] Integração com Eneba
- [ ] Integração com Instant Gaming
