# CLAUDE.md

> Este arquivo DEVE ser atualizado sempre que houver uma mudança significativa no projeto (nova decisão de arquitetura, nova fonte de dados, nova tecnologia, mudança de schema, etc). É a fonte de verdade sobre o que o projeto é e onde ele está.

## Objetivo do projeto

Site de catálogo de promoções de jogos. A ideia central: o usuário entra, vê uma lista de jogos com o **menor preço encontrado entre várias lojas**, clica no jogo e vê todas as ofertas daquele jogo ordenadas por preço (menor primeiro), com link direto pra loja.

Não é uma loja própria — é um agregador/comparador de preços.

## Decisões de arquitetura (e por quê)

- **Backend:** Java 21 + Spring Boot 4.1.0 (Maven). Decisão do usuário, sem alternativa considerada.
- **Frontend:** Angular (última versão estável). Decisão do usuário, sem alternativa considerada.
- **Banco:** PostgreSQL, hospedado no Supabase.
- **Deploy:**
  - Backend → Render (free tier).
  - Frontend → Vercel.
  - **Importante:** Spring Boot não roda na Vercel (Vercel é serverless/frontend). Por isso o backend foi separado pro Render.
- **Sincronização de preços:** NÃO usa `@Scheduled` dentro do Spring. Em vez disso, GitHub Actions chama um endpoint `POST /api/sync` a cada 6h. Motivo: o Render free tier "dorme" sem tráfego, e depender de `@Scheduled` interno é frágil nesse cenário — a chamada externa do Actions já acorda o serviço se necessário.
  - Workflow extra do GitHub Actions: ping de health-check a cada ~10 min, só pra manter o Render acordado.
  - O endpoint `/api/sync` é protegido por uma chave secreta via header (variável `SYNC_SECRET_KEY`), configurada como GitHub Secret.

## Fontes de dados de preços

| Fonte | Status | Como integra |
|---|---|---|
| **IsThereAnyDeal (ITAD)** | Fonte principal, em uso | API oficial. Cobre Steam, Nuuvem, GOG, Epic, etc. Preços podem ser pedidos em BRL. |
| **Eneba** | Planejada, ainda não implementada | Tem programa de afiliados com feed de preços em XML/CSV (após aprovação no cadastro). Não é a API GraphQL deles (essa é só pra sellers). |
| **Instant Gaming** | Sem integração automática possível | Não existe feed de preços/API pública. Só programa de afiliados informal (link `?igr=codigo`, comissão por clique/venda). Se for incluído no catálogo, será via **cadastro manual** (`source = 'manual'` na tabela `offers`). |

Todas as fontes (atuais e futuras) gravam na mesma tabela `offers`, diferenciadas pela coluna `source`. Isso permite adicionar uma fonte nova sem alterar o schema — só escrever um novo "adapter" que busca os dados e grava no formato comum.

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
- Campo `currency` existe desde já, mas por enquanto o sistema só trata **BRL**. Multi-moeda é possibilidade futura, não implementada.
- Sem suporte a usuário logado/favoritos/alertas nesta fase. Catálogo é público.

## Endpoints da API (planejados/atuais)

- `GET /api/games` — lista paginada de jogos, cada um já com o menor preço.
- `GET /api/games/{slug}` — detalhe do jogo + todas as ofertas ordenadas por preço.
- `POST /api/sync` — protegido por chave secreta, dispara a busca de preços (hoje: ITAD; no futuro: também Eneba).

## Estrutura de pastas

Monorepo:
```
/backend   → Spring Boot
/frontend  → Angular
```

Pacotes do backend (`com.ofertagames.backend`):
```
game/          → entidade Game, repositório
offer/         → entidade Offer, repositório
source/itad/   → cliente da API do ITAD
api/           → controllers REST
config/        → configuração geral
```

## O que NÃO fazer (escopo intencionalmente fora por agora)

- Sem scraping de sites (Steam, Eneba, Instant Gaming) — frágil, viola termos de uso, evitar.
- Sem autenticação/cadastro de usuário nesta fase.
- Sem multi-moeda funcional (campo existe, lógica não).
- Sem Docker (decisão explícita do usuário: deploy direto Render/Vercel).
- Sem `@Scheduled` interno pra sincronização — sempre via GitHub Actions externo.

## Estado atual do projeto

- [x] Arquitetura decidida (stack, hospedagem, schema, fontes de dados)
- [ ] Estrutura inicial do backend e frontend gerada
- [ ] Integração real com a API do ITAD implementada
- [ ] Lógica do `/api/sync` implementada (upsert em games/offers)
- [ ] Telas do Angular (catálogo e detalhe) implementadas
- [ ] Deploy configurado (Render, Vercel, GitHub Actions)
- [ ] Integração com Eneba (feed de afiliado)
- [ ] Cadastro manual de ofertas (Instant Gaming)

> Atualize esta seção (e o restante do arquivo) conforme cada item avançar ou novas decisões forem tomadas.
