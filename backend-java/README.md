# Backend Java

Backend Spring Boot do Oferta Games.

## Arquitetura

- Java 21 + Spring Boot 3.
- Dois bancos PostgreSQL: dados de usuario no Supabase (`DATABASE_URL`) e catalogo num Postgres proprio na VM (`CATALOG_DATABASE_URL`).
- Supabase Auth para autenticar.
- ITAD como fonte principal de ofertas.
- Steam usado para complementar capa e classificar DLC quando existe oferta da loja Steam.
- Frontend Angular hospedado separadamente na Vercel (SSR).
- Deploy do backend na VM Oracle via Docker Compose + Caddy, disparado pelo GitHub Actions depois da CI (ver `deploy/oracle/` e `doc.md`).

## Padrao de codigo

Use portugues nos nomes do codigo Java:

- `ControladorJogos`
- `RepositorioJogos`
- `ServicoCatalogo`
- `ServicoAutenticacao`
- `ConfiguracaoBancoDados`

Termos externos ou marcas podem permanecer como no original, por exemplo `ITAD`, `Steam`, `Spring`, `Bearer` e nomes dos campos JSON que o frontend ja consome.

## Variaveis de ambiente

```bash
DATABASE_URL=postgresql://...
SUPABASE_URL=https://...
SUPABASE_ANON_KEY=...
ITAD_API_KEY=...
SYNC_SECRET_KEY=...
APP_SYNC_SCHEDULER_ENABLED=true
CORS_ALLOWED_ORIGINS=https://seu-front.vercel.app,http://localhost:4200
PORT=8080
```

`DATABASE_URL` pode continuar no formato do Supabase pooler. A aplicacao converte internamente para JDBC e usa `sslmode=require` quando a URL nao trouxer query string.

## Endpoints

- `GET /api/games?page=0&size=20&sort=rank&type=all&minPrice=&maxPrice=&q=`
- `GET /api/games/search?q=nome`
- `GET /api/games/{slug}`
- `POST /api/games/{slug}/refresh`
- `GET /api/deals/top?size=20&sort=discount`
- `POST /api/sync?page=0`
- `GET /api/favorites`
- `POST /api/favorites`
- `DELETE /api/favorites/{slug}`
- `GET /actuator/health`

## Coleta agendada

O backend executa a coleta internamente quando `APP_SYNC_SCHEDULER_ENABLED=true`.

- A cada 10 minutos, atualiza 5.000 jogos: 200 do top 2.000 por `rank` e 4.800 do restante.
- Os jogos sao escolhidos por `games.last_price_sync_at`, sempre do mais antigo para o mais recente. Apos atualizados, voltam naturalmente ao fim da fila.
- Os precos sao coletados em lotes sequenciais de ate 200 IDs por chamada da ITAD. Uma falha em um lote nao impede os demais.
- A cada 15 minutos, uma rotina separada complementa metadados Steam de ate 25 jogos pendentes de capa ou classificacao de DLC.
- As duas rotinas compartilham uma trava no banco, portanto nunca executam em paralelo, inclusive durante um novo deploy.

Antes de ativar, execute no SQL Editor do Supabase o arquivo `backend-java/sql/20260711_coleta_agendada.sql`.

Variaveis opcionais para ajustar o intervalo:

```bash
APP_SYNC_SCHEDULER_PRICE_DELAY_MS=600000
APP_SYNC_SCHEDULER_STEAM_DELAY_MS=900000
```

O endpoint `POST /api/sync?page=0` continua disponivel para diagnostico/manual e exige:

```http
X-Sync-Key: valor-de-SYNC_SECRET_KEY
```

A coleta roda agendada dentro do proprio backend (`APP_SYNC_SCHEDULER_ENABLED=true`); nao ha workflow de sync no GitHub Actions.

## Rodando localmente

Este projeto usa Java 21 e Maven.

```bash
cd backend-java
mvn spring-boot:run
```

## Deploy

O backend roda na VM Oracle (`deploy/oracle/compose.yml`: backend, Postgres do catalogo e Caddy com
HTTPS). Todo push em `master` que passa na CI dispara `.github/workflows/deploy-oracle.yml`, que so
reconstroi o container quando algo em `backend-java/` ou no `compose.yml` mudou.

As variaveis ficam em `deploy/oracle/.env` na VM; a lista completa, com a explicacao de cada uma,
esta em [`deploy/oracle/.env.example`](../deploy/oracle/.env.example). Backup e restauracao:
[`deploy/oracle/README-catalogo-db.md`](../deploy/oracle/README-catalogo-db.md).
