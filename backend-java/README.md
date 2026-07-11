# Backend Java

Backend Spring Boot do Oferta Games.

## Arquitetura

- Java 21 + Spring Boot 3.
- PostgreSQL no Supabase via `DATABASE_URL`.
- Supabase Auth para autenticar favoritos.
- ITAD como fonte principal de ofertas.
- Steam usado para complementar capa e classificar DLC quando existe oferta da loja Steam.
- Frontend Angular continua hospedado separadamente no Vercel.
- Deploy do backend no Render via Docker.

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

Os workflows de sync e keep alive do GitHub Actions foram removidos. O bot que ja mantem o Render ativo assume essa responsabilidade.

## Rodando localmente

Este projeto usa Java 21 e Maven.

```bash
cd backend-java
mvn spring-boot:run
```

## Deploy no Render

Criacao manual:

- Runtime: `Docker`
- Root Directory: `backend-java`
- Health Check Path: `/actuator/health`
- Plan: `Free` por enquanto

Tambem existe `render.yaml` na raiz do repositorio para criar via Blueprint.

Variaveis obrigatorias no Render:

```bash
DATABASE_URL=postgresql://...
SUPABASE_URL=https://...
SUPABASE_ANON_KEY=...
ITAD_API_KEY=...
SYNC_SECRET_KEY=...
APP_SYNC_SCHEDULER_ENABLED=true
CORS_ALLOWED_ORIGINS=https://seu-front.vercel.app,http://localhost:4200
```
