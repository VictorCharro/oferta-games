# Backend Java

Backend Spring Boot que vai substituir gradualmente o backend serverless em Node/Vercel.

## Decisao de arquitetura

- Frontend Angular continua no Vercel.
- Banco e Auth continuam no Supabase.
- Backend Java roda em uma VM Oracle Cloud Always Free, preferencialmente Ampere A1.
- O contrato HTTP deve continuar igual ao backend atual para evitar refatoracao grande no frontend.

## Recursos Oracle recomendados

Pelo Always Free da Oracle, o melhor alvo para este backend e:

- Compute: `VM.Standard.A1.Flex`
- Limite Always Free: ate 2 OCPUs e 12 GB de memoria no total da tenancy
- Imagem: Ubuntu
- Disco: volume de boot padrao, contando dentro dos 200 GB de Block Volume Always Free

Evite usar a micro AMD de 1 GB para Spring Boot em producao. Ela serve para testes simples, mas fica apertada.

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

`DATABASE_URL` pode continuar no formato do Supabase pooler. A aplicacao converte internamente para JDBC e usa `sslmode=require`.

## Rodando localmente

Este projeto usa Java 21 e Maven.

```bash
cd backend-java
mvn spring-boot:run
```

Como o Maven ainda nao esta instalado nesta maquina, o proximo passo local e instalar Maven ou adicionar Maven Wrapper.

## Endpoints iniciados

- `GET /api/games`
- `GET /api/games/search?q=nome`
- `GET /api/games/{slug}`
- `GET /api/deals/top`
- `GET /api/favorites`
- `POST /api/favorites`
- `DELETE /api/favorites/{slug}`

## Proximas etapas

- Implementar `POST /api/sync`.
- Implementar `POST /api/games/{slug}/refresh`.
- Implementar busca externa na ITAD quando `/api/games/search` nao encontrar resultados locais.
- Adicionar guia de deploy com `systemd`, Nginx e Certbot.
