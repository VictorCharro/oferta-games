# Banco de catálogo (`catalogo-db`)

Desde a migração pra fora do Supabase free (cota de 500MB apertando — ver commits de 2026-09-10),
as tabelas `games`, `game_details`, `game_achievements`, `offers`, `price_history` e
`instant_gaming_catalog` moram num Postgres próprio, rodando como container Docker na VM Oracle
(serviço `catalogo-db` em [`compose.yml`](compose.yml)). Não tem FK com usuário nenhum — só dado
de catálogo de jogo, alimentado pelos jobs de sincronização.

Tudo que é ligado a usuário (favoritos, perfil, avaliações, notificações, conexão Steam) **continua
no Supabase**, incluindo o Auth.

## Diferença importante em relação ao Supabase

O Supabase faz backup automático sozinho. Esse Postgres novo **não tem nada disso por padrão** —
por isso existe [`backup-catalogo.sh`](backup-catalogo.sh).

## Backup automático

Configurar uma vez na VM (usuário `ubuntu`):

```bash
crontab -e
```

Adicionar a linha (roda todo dia às 4h da manhã):

```
0 4 * * * /opt/oferta-games/deploy/oracle/backup-catalogo.sh >> /var/log/backup-catalogo.log 2>&1
```

Os dumps ficam em `/opt/backups/catalogo/`, mantendo só os 7 mais recentes (script já apaga os
mais antigos sozinho).

**Importante:** esses backups ficam só no disco da própria VM. Se a VM inteira for perdida
(disco corrompido, instância deletada), os backups vão junto. Recomendado copiar o dump mais
recente pra fora da VM periodicamente (`scp` pro seu computador, ou subir num bucket) — não há
automação pra isso ainda.

## Como restaurar do zero (nova VM, ou volume `catalogo_db_data` perdido)

1. Suba o container vazio: `docker compose -f deploy/oracle/compose.yml up -d catalogo-db`
2. Copie um dump (`.dump`) de `/opt/backups/catalogo/` — ou de outra cópia externa — pra dentro
   do container:
   ```bash
   docker cp caminho/para/catalogo_AAAAMMDD_HHMMSS.dump oferta-games-catalogo-db:/tmp/restore.dump
   ```
3. Restaure:
   ```bash
   docker exec oferta-games-catalogo-db pg_restore -U catalogo -d catalogo --no-owner --no-privileges /tmp/restore.dump
   ```
4. Confirme as tabelas e alguns dados:
   ```bash
   docker exec oferta-games-catalogo-db psql -U catalogo -d catalogo -c '\dt'
   docker exec oferta-games-catalogo-db psql -U catalogo -d catalogo -c 'select count(*) from games;'
   ```
5. Suba o backend normalmente: `docker compose -f deploy/oracle/compose.yml up -d backend`

Sem esse passo manual, um `catalogo-db` novo sobe **vazio** (saudável, mas sem tabela nenhuma) —
o backend conecta normalmente e toda query de catálogo falha com `relation "games" does not exist`.
