# CLAUDE.md

> Este arquivo DEVE ser atualizado sempre que houver uma mudança significativa no projeto (nova decisão de arquitetura, nova fonte de dados, nova tecnologia, mudança de schema, etc). É a fonte de verdade sobre o que o projeto é e onde ele está.

## Objetivo do projeto

Site de catálogo de promoções de jogos. A ideia central: o usuário entra, vê uma lista de jogos com o **menor preço encontrado entre várias lojas**, clica no jogo e vê todas as ofertas daquele jogo ordenadas por preço (menor primeiro), com link direto pra loja.

Não é uma loja própria — é um agregador/comparador de preços.

## Stack

- **Backend:** Node.js + TypeScript, serverless functions no Vercel.
- **Frontend:** Angular (última versão estável), hospedado no Vercel.
- **Banco:** PostgreSQL no Supabase. Conexão via transaction pooler (porta 6543).

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

## Fontes de dados de preços

| Fonte | Status | Como integra |
|---|---|---|
| **IsThereAnyDeal (ITAD)** | Fonte principal, em uso | API oficial. Lojas configuradas: Nuuvem (50), Fanatical (6), GreenManGaming (36), Humble Store (37), GamersGate (24), IndieGala (42), 2game (19), Steam (61), Epic (16), Blizzard (4), EA Store (52), Microsoft Store (48), Ubisoft Store (62). |
| **Eneba** | Planejada | Feed de afiliados XML/CSV após aprovação no cadastro. |
| **Instant Gaming** | Sem integração automática | Cadastro manual (`source = 'manual'` na tabela `offers`). |

Todas as fontes gravam na mesma tabela `offers`, diferenciadas pela coluna `source`.

## Schema do banco

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

- Menor preço calculado via query (`MIN(price)`), não armazenado.
- Somente BRL por enquanto. Multi-moeda é possibilidade futura.
- Sem autenticação/usuários nesta fase.

## Endpoints da API

- `GET /api/games?page=0&size=20` — lista paginada de jogos com menor preço.
- `GET /api/games/search?q=nome` — busca jogos no banco; se não achar, busca na ITAD e insere automaticamente.
- `GET /api/games/{slug}` — detalhe do jogo + todas as ofertas ordenadas por preço.
- `POST /api/games/{slug}/refresh` — atualiza preços de um jogo específico na ITAD (chamado pelo botão no frontend).
- `POST /api/sync?page=0` — dispara busca de uma página de deals da ITAD (uso interno/Actions).

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
    sync/
      index.ts         → POST /api/sync
  lib/
    db.ts              → conexão Supabase
  scripts/
    sync.ts            → script de sync completo (rodado pelo GitHub Actions)
  package.json
  tsconfig.json
  vercel.json

/frontend              → Angular

/.github/workflows/
  sync.yml             → roda scripts/sync.ts todo dia às 03:00 UTC
```

## O que NÃO fazer

- Sem scraping de sites — frágil e viola termos de uso.
- Sem autenticação/cadastro de usuário nesta fase.
- Sem multi-moeda funcional.
- Sem Docker.
- Sem cron interno — sincronização sempre via GitHub Actions externo.

## Estado atual

- [x] Arquitetura definida
- [x] Backend Node.js/TypeScript estruturado (endpoints + integração ITAD)
- [x] Deploy configurado no Vercel (backend)
- [ ] Deploy configurado no Vercel (frontend)
- [x] GitHub Actions configurado (sync completo diário às 03:00 UTC)
- [ ] Telas do Angular implementadas (catálogo e detalhe)
- [ ] Integração com Eneba
- [ ] Cadastro manual de ofertas (Instant Gaming)

> Atualize esta seção conforme cada item avançar ou novas decisões forem tomadas.
