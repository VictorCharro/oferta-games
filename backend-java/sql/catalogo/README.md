# Schema do catalogo

Estes scripts pertencem ao PostgreSQL do catalogo, nao ao Supabase.

`000_base.sql` e a base completa das seis tabelas, sequences, constraints e indices.
Foi extraida **somente do schema** do backup de 10/09/2026, anterior a separacao dos bancos.
Nao inclui usuarios, dados, credenciais, owners, grants nem as politicas de RLS do Supabase.
As alteracoes posteriores ficam nos arquivos numerados seguintes.

## Ambiente vazio

Execute com `psql`, conectado ao banco de catalogo desejado:

```sh
psql "$CATALOG_DATABASE_URL" -v ON_ERROR_STOP=1 --single-transaction -f backend-java/sql/catalogo/000_base.sql
psql "$CATALOG_DATABASE_URL" -v ON_ERROR_STOP=1 --single-transaction -f backend-java/sql/catalogo/001_validade_ranking.sql
```

A base deve ser aplicada **somente em banco vazio**. Nao usa `IF NOT EXISTS` para nao esconder
divergencias estruturais. Banco vazio inicializa o schema, mas nao recupera o acervo: para
restauracao de producao, use o backup conforme `deploy/oracle/README-catalogo-db.md`.

## Banco existente

Nao reaplique `000_base.sql`. Aplique apenas as migrations posteriores ainda pendentes,
em ordem e antes do deploy correspondente. `001_validade_ranking.sql` e idempotente.
As migrations antigas de `backend-java/sql/` registram a evolucao anterior a esta base;
nao devem ser reaplicadas sobre ela. Novas alteracoes de catalogo entram nesta pasta.
