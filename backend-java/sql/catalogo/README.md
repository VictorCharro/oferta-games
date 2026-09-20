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
psql "$CATALOG_DATABASE_URL" -v ON_ERROR_STOP=1 -f backend-java/sql/catalogo/002_busca_titulo.sql
```

A base deve ser aplicada **somente em banco vazio**. Nao usa `IF NOT EXISTS` para nao esconder
divergencias estruturais. Banco vazio inicializa o schema, mas nao recupera o acervo: para
restauracao de producao, use o backup conforme `deploy/oracle/README-catalogo-db.md`.

## Banco existente

Nao reaplique `000_base.sql`. Aplique apenas as migrations posteriores ainda pendentes,
em ordem e antes do deploy correspondente. `001_validade_ranking.sql` e idempotente.
As migrations antigas de `backend-java/sql/` registram a evolucao anterior a esta base;
nao devem ser reaplicadas sobre ela. Novas alteracoes de catalogo entram nesta pasta.

`001` deve preceder o backend que usa `rank_updated_at`. `002` exige permissao para
instalar `pg_trgm` e deve rodar **sem `--single-transaction`**: cria o indice com
`CONCURRENTLY`. Se a criacao for interrompida, confira `pg_index.indisvalid`;
um indice invalido deve ser removido antes da nova tentativa, pois `IF NOT EXISTS`
nao o reconstroi. A consulta usa `title ~*`, portanto o indice e sobre `title`.

## Validacao local

`CatalogoPostgresTest` usa PostgreSQL real e desfaz os dados e DDL de cada teste.
Execute apenas em instancia descartavel localhost, com `000` e `001` aplicados:

```powershell
$env:TEST_CATALOGO_JDBC='jdbc:postgresql://127.0.0.1:55439/postgres?user=revisao'
mvn test
```

Sem essa variavel, os quatro testes de integracao sao ignorados. Para medir a busca,
carregue somente `games` e `offers` de um backup de catalogo, execute `ANALYZE` e
mantenha a base inicialmente sem `002`. O teste compara resultados e imprime
`EXPLAIN (ANALYZE, BUFFERS)` antes/depois; os tempos nao sao limites de aprovacao.

Em 20/09/2026, com 110.337 jogos locais: `portal` 57,8 -> 1,1 ms; `gta` 148,5 ->
0,7 ms; `re` 189,6 -> 153,3 ms; `dark` 65,8 -> 147,1 ms. Sao amostras locais,
nao SLA nem benchmark de producao. O ganho depende da seletividade e do plano;
termos amplos continuam protegidos pelo limite de consultas pesadas. O indice
nao justifica remover essa protecao nem alterar a relevancia da busca.
