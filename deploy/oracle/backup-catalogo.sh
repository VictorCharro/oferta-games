#!/bin/bash
# Backup diario do banco de catalogo (games/game_details/game_achievements/offers/price_history/
# instant_gaming_catalog) - roda self-hosted na VM desde a migracao pra fora do Supabase free
# (ver commit "Backend passa a usar o Postgres do catalogo separado do Supabase"). Diferente do
# Supabase, esse banco nao tem backup automatico nenhum por fora - esse script e a unica rede de
# seguranca dele.
#
# Uso: rodar via cron na propria VM (ver crontab -e do usuario ubuntu), nao dentro de nenhum
# container - usa "docker exec" pra rodar pg_dump de dentro do container catalogo-db.
#
# Mantem os ultimos 7 dumps (~1 semana) e apaga o resto, pra nao encher o disco da VM aos poucos.

set -euo pipefail

DIR_BACKUPS=/opt/backups/catalogo
DATA=$(date +%Y%m%d_%H%M%S)
ARQUIVO="$DIR_BACKUPS/catalogo_$DATA.dump"

mkdir -p "$DIR_BACKUPS"

docker exec oferta-games-catalogo-db pg_dump -U catalogo -d catalogo -F c --no-owner --no-privileges \
  > "$ARQUIVO"

echo "Backup salvo em $ARQUIVO ($(du -h "$ARQUIVO" | cut -f1))"

# Mantem so os 7 mais recentes.
ls -t "$DIR_BACKUPS"/catalogo_*.dump | tail -n +8 | xargs -r rm -v
