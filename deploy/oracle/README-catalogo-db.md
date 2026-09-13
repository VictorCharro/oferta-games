# Banco de catálogo (`catalogo-db`) e backups

Desde a migração pra fora do Supabase free (cota de 500MB apertando — ver commits de 2026-09-10),
as tabelas `games`, `game_details`, `game_achievements`, `offers`, `price_history` e
`instant_gaming_catalog` moram num Postgres próprio, rodando como container Docker na VM Oracle
(serviço `catalogo-db` em [`compose.yml`](compose.yml)). Não tem FK com usuário nenhum — só dado
de catálogo de jogo, alimentado pelos jobs de sincronização.

Tudo que é ligado a usuário (favoritos, perfil, avaliações, notificações, conexão Steam) **continua
no Supabase**, incluindo o Auth.

## Nenhum dos dois bancos tem backup automático por fora

- O `catalogo-db` é um Postgres num container: nada faz backup dele.
- O Supabase **free** também **não** tem backup automático (backup diário é recurso do plano Pro).
  Contas, perfis e personalizações só existem lá.

Por isso existe [`backup.sh`](backup.sh), que faz os dois.

## Backup automático

O que o script faz, todo dia às 4h:

1. `pg_dump` do catálogo → `/opt/backups/catalogo/catalogo_AAAAMMDD_HHMMSS.dump` (~100 MB).
2. `pg_dump` dos schemas `public` + `auth` do Supabase → `/opt/backups/supabase/supabase_*.dump`.
   Os arquivos do Storage (avatares, banners, imagens de bloco) **não** entram: ficam no bucket,
   não no banco.
3. Valida cada dump (tamanho mínimo + `pg_restore --list`) antes de considerá-lo bom.
4. Rotação: mantém os 7 mais recentes + os de domingo das últimas 5 semanas.
5. Se `BACKUP_RCLONE_REMOTE` estiver no `.env`, copia pra fora da VM.
6. Se `BACKUP_HEARTBEAT_URL` estiver no `.env`, avisa sucesso ou falha.

Os dumps são criados com permissão `600`: o do Supabase tem e-mail e dado pessoal de todo usuário.

### Instalar o cron (uma vez, usuário `ubuntu`)

```
0 4 * * * /opt/oferta-games/deploy/oracle/backup.sh >> /opt/backups/backup.log 2>&1
```

**Não** use `/var/log/...` no redirecionamento: `/var/log` pertence ao root, o cron roda como
`ubuntu`, e o shell falha no `>>` antes de executar o script. Foi o que deixou o backup parado de
10/09 a 13/09/2026 sem log nenhum (issue #15).

Rodar na mão (dá pra fazer a qualquer hora, leva ~20s):

```bash
nice -n 10 /opt/oferta-games/deploy/oracle/backup.sh
```

### Cópia fora da VM (recomendado)

Sem isso, perder a VM (disco, instância recriada, conta Oracle) leva os backups junto. O script
usa [rclone](https://rclone.org), que fala com praticamente qualquer storage. Na Oracle, dá pra
autenticar **pela própria instância**, sem guardar chave no disco:

1. Console OCI → Object Storage → criar bucket privado `oferta-games-backups` (na mesma região).
2. Identity → Dynamic Groups → criar `oferta-games-vm` com a regra
   `instance.id = '<OCID da instância>'`.
3. Identity → Policies (no compartimento raiz) →
   `Allow dynamic-group oferta-games-vm to manage objects in tenancy where target.bucket.name='oferta-games-backups'`
4. Na VM: `sudo apt install -y rclone` e criar `~/.config/rclone/rclone.conf`:
   ```ini
   [oci]
   type = oracleobjectstorage
   provider = instance_principal_auth
   namespace = <namespace do Object Storage>
   compartment = <OCID do compartimento>
   region = sa-saopaulo-1
   ```
5. Testar: `rclone lsd oci:` e depois `rclone ls oci:oferta-games-backups`.
6. No `.env`: `BACKUP_RCLONE_REMOTE=oci:oferta-games-backups`.

Na próxima execução o log mostra `Copia externa enviada`. Enquanto não estiver configurado, toda
execução registra `AVISO: BACKUP_RCLONE_REMOTE nao configurado`.

### Alerta quando o backup não roda (recomendado)

Criar um check em [healthchecks.io](https://healthchecks.io) (grátis) com período de 1 dia e
tolerância de 2h, e colocar a URL no `.env`: `BACKUP_HEARTBEAT_URL=https://hc-ping.com/<uuid>`.
O script faz ping em `/start`, na URL em caso de sucesso e em `/fail` em caso de erro. Se o cron
parar de rodar de vez (o caso de 10/09), o ping simplesmente não chega e o serviço alerta.

## Como restaurar

Os dois procedimentos abaixo foram testados em 13/09/2026 num container descartável: o catálogo
voltou com as mesmas contagens da produção em todas as 6 tabelas (~37s), e o Supabase com as
mesmas contagens de `auth.users`, `profiles`, `profile_blocks` e `steam_connections`.

### Catálogo (nova VM, ou volume `catalogo_db_data` perdido)

1. Suba o container vazio: `docker compose -f deploy/oracle/compose.yml up -d catalogo-db`
2. Restaure direto do arquivo (sem precisar copiar pra dentro do container):
   ```bash
   docker exec -i oferta-games-catalogo-db pg_restore -U catalogo -d catalogo --no-owner --no-privileges \
     < /opt/backups/catalogo/catalogo_AAAAMMDD_HHMMSS.dump
   ```
3. Confira:
   ```bash
   docker exec oferta-games-catalogo-db psql -U catalogo -d catalogo -c 'select count(*) from games;'
   docker exec oferta-games-catalogo-db psql -U catalogo -d catalogo -c 'select count(*) from pg_sequences;'  # 4
   ```
   O dump completo (não `-t <tabela>`) já traz as sequences — restaurar tabela por tabela as perde
   (ver doc.md, migração do catálogo).
4. Suba o backend normalmente: `docker compose -f deploy/oracle/compose.yml up -d backend`

Sem esse passo, um `catalogo-db` novo sobe **vazio** (saudável, mas sem tabela nenhuma) — o
backend conecta normalmente e toda query de catálogo falha com `relation "games" does not exist`.

### Supabase (dados de usuário)

O caminho real é restaurar num **projeto Supabase novo** (que já vem com os roles e extensões do
Supabase): pegar a connection string do projeto novo e rodar, de dentro do container que tem o
`pg_restore` 17:

```bash
docker exec -i -e DATABASE_URL='postgresql://...projeto-novo...' oferta-games-catalogo-db \
  sh -c 'pg_restore -d "$DATABASE_URL" --no-owner --no-privileges --data-only' \
  < /opt/backups/supabase/supabase_AAAAMMDD_HHMMSS.dump
```

`--data-only` porque o projeto novo precisa das tabelas criadas antes: aplique os SQL de
`backend-java/sql/` em ordem, depois restaure os dados. Para só **inspecionar** um backup, restaure
num Postgres descartável criando antes os roles do Supabase (`anon`, `authenticated`,
`service_role`, `supabase_auth_admin`). O único erro esperado é `schema "public" already exists`.

Depois de restaurar num projeto novo: atualizar `DATABASE_URL`, `SUPABASE_URL` e
`SUPABASE_ANON_KEY` (VM e Vercel) e reenviar os arquivos do Storage, que não estão no dump.
