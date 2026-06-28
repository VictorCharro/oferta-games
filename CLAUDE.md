# CLAUDE.md

> Este arquivo DEVE ser atualizado sempre que houver uma mudança significativa no projeto (nova decisão de arquitetura, nova fonte de dados, nova tecnologia, mudança de schema, etc). É a fonte de verdade sobre o que o projeto é e onde ele está.

## Objetivo do projeto

Site de catálogo de promoções de jogos. A ideia central: o usuário entra, vê uma lista de jogos com o **menor preço encontrado entre várias lojas**, clica no jogo e vê todas as ofertas daquele jogo ordenadas por preço (menor primeiro), com link direto pra loja.

Não é uma loja própria — é um agregador/comparador de preços.

## Decisões de arquitetura (e por quê)

- **Backend:** Node.js + TypeScript, deploy como serverless functions no Vercel. Migrado do Java/Spring Boot em 2026-06-28 — o Render (onde o Java estava hospedado) bloqueava conexões com o Supabase no free tier, inviabilizando o deploy gratuito.
- **Frontend:** Angular (última versão estável). Decisão do usuário, sem alternativa considerada.
- **Banco:** PostgreSQL, hospedado no Supabase. Conexão via **transaction pooler** (porta 6543) — obrigatório no free tier, pois a conexão direta (porta 5432) é bloqueada em ambientes cloud gratuitos.
- **Deploy:**
  - Backend → Vercel (serverless functions, pasta `backend/`, root directory `backend`).
  - Frontend → Vercel (pasta `frontend/`, root directory `frontend`).
  - Dois projetos separados no Vercel apontando para o mesmo repositório.
- **Sincronização de preços:** GitHub Actions chama `POST /api/sync` a cada 6h. O endpoint é protegido por chave secreta via header `X-Sync-Key` (variável `SYNC_SECRET_KEY`).

## Fontes de dados de preços

| Fonte | Status | Como integra |
|---|---|---|
| **IsThereAnyDeal (ITAD)** | Fonte principal, em uso | API oficial. Cobre Steam, Nuuvem, GOG, Epic, etc. Preços pedidos em BRL. |
| **Eneba** | Planejada, ainda não implementada | Feed de afiliados XML/CSV após aprovação no cadastro. |
| **Instant Gaming** | Sem integração automática possível | Cadastro manual (`source = 'manual'` na tabela `offers`). |

Todas as fontes gravam na mesma tabela `offers`, diferenciadas pela coluna `source`.

## Schema do banco (atual)

```sql
games
  id            bigserial PK
  itad_id       uuid UNIQUE NULL
  title         text NOT NULL
  slug          text UNIQUE NOT NULL
  cover_url     text NULL
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
```

Decisões sobre o schema:
- Não existe coluna "menor preço" em `games`. O menor preço é sempre calculado via query (`MIN(price)` agrupado por `game_id`).
- Campo `currency` existe desde já, mas por enquanto o sistema só trata **BRL**.
- Sem suporte a usuário logado/favoritos/alertas nesta fase. Catálogo é público.

## Endpoints da API

- `GET /api/games?page=0&size=20` — lista paginada de jogos com menor preço.
- `GET /api/games/{slug}` — detalhe do jogo + todas as ofertas ordenadas por preço.
- `POST /api/sync` — protegido por `X-Sync-Key`, dispara busca de preços (ITAD).

## Estrutura de pastas

Monorepo:
```
/backend               → Node.js/TypeScript (Vercel serverless)
  api/
    games/
      index.ts         → GET /api/games
      [slug].ts        → GET /api/games/:slug
    sync/
      index.ts         → POST /api/sync
  lib/
    db.ts              → conexão Supabase (postgres, SSL, prepare:false para PgBouncer)
  package.json
  tsconfig.json
  vercel.json

/frontend              → Angular
```

## Variáveis de ambiente (backend no Vercel)

| Variável | Descrição |
|---|---|
| `DATABASE_URL` | URL do pooler do Supabase — formato `postgresql://...` (sem `jdbc:`) |
| `ITAD_API_KEY` | Chave da API do IsThereAnyDeal |
| `SYNC_SECRET_KEY` | Chave secreta para o endpoint `/api/sync` |

## O que NÃO fazer (escopo intencionalmente fora por agora)

- Sem scraping de sites (Steam, Eneba, Instant Gaming) — frágil, viola termos de uso.
- Sem autenticação/cadastro de usuário nesta fase.
- Sem multi-moeda funcional (campo existe, lógica não).
- Sem Docker.
- Sem `@Scheduled` ou cron interno — sincronização sempre via GitHub Actions externo.

## Estado atual do projeto

- [x] Arquitetura decidida (stack, hospedagem, schema, fontes de dados)
- [x] Estrutura do backend gerada (Node.js/TypeScript, Vercel serverless)
- [x] Integração com a API do ITAD implementada (client + sync)
- [x] Lógica do `/api/sync` implementada (upsert em games/offers)
- [ ] Deploy configurado no Vercel (backend + frontend)
- [ ] GitHub Actions configurado (sync a cada 6h)
- [ ] Telas do Angular (catálogo e detalhe) implementadas
- [ ] Integração com Eneba (feed de afiliado)
- [ ] Cadastro manual de ofertas (Instant Gaming)

> Atualize esta seção (e o restante do arquivo) conforme cada item avançar ou novas decisões forem tomadas.
