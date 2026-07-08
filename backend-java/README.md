# Backend Java

Backend Spring Boot do Oferta Games.

## Arquitetura

- Java 21 + Spring Boot 3.
- PostgreSQL no Supabase via `DATABASE_URL`.
- Supabase Auth para autenticar favoritos.
- ITAD como fonte principal de ofertas.
- Steam usado para complementar capa e classificar DLC quando existe oferta da loja Steam.
- Frontend Angular continua hospedado separadamente no Vercel.
- Deploy planejado em VM Oracle Cloud Always Free, preferencialmente Ampere A1.

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

## Sync

O endpoint `POST /api/sync` exige o header:

```http
X-Sync-Key: valor-de-SYNC_SECRET_KEY
```

Ele sincroniza uma pagina da ITAD, salva jogos/ofertas e executa um pequeno backfill de metadados Steam para preencher `is_dlc` e capa quando possivel.

O workflow `.github/workflows/sync.yml` chama esse endpoint em paginas sucessivas usando os secrets:

```bash
BACKEND_API_URL=https://api.seu-dominio.com
SYNC_SECRET_KEY=sua-chave-sync
```

## Rodando localmente

Este projeto usa Java 21 e Maven.

```bash
cd backend-java
mvn spring-boot:run
```

Nesta maquina o Maven ainda nao esta instalado no PATH, entao o build local precisa desse passo antes.
