#!/bin/bash
# Backup diario dos DOIS bancos do projeto, rodado pelo cron do usuario ubuntu na VM:
#
#   0 4 * * * /opt/oferta-games/deploy/oracle/backup.sh >> /opt/backups/backup.log 2>&1
#
# - catalogo: Postgres local (container catalogo-db). Nao tem backup nenhum por fora.
# - supabase: schemas public + auth (contas, perfis, favoritos, colecoes, conexoes). O plano free
#   do Supabase NAO tem backup automatico, entao os dados de usuario so existem la e aqui.
#   Arquivos do Storage (avatares, banners) nao entram: moram no bucket, nao no banco.
#
# Por que o log vai pra /opt/backups e nao pra /var/log: /var/log e root:syslog 755, e o cron roda
# como ubuntu. O shell falhava NO REDIRECIONAMENTO, antes de executar qualquer linha deste script,
# entao nem set -e nem log registravam nada - o backup ficou parado de 10/09 a 13/09/2026 sem
# ninguem saber (issue #15). O heartbeat abaixo existe pra essa classe de falha silenciosa.
#
# Configuracao opcional, lida do .env do compose (mesmo arquivo do backend):
#   BACKUP_RCLONE_REMOTE   destino rclone pra copia FORA da VM (ex.: oci:oferta-games-backups).
#                          Sem ela o backup fica so no disco da VM e o log avisa em toda execucao.
#   BACKUP_HEARTBEAT_URL   URL de ping (healthchecks.io ou similar). Sucesso faz GET na URL; falha
#                          faz GET em URL/fail. O servico alerta quando o ping nao chega no prazo.
#
# Restaurar: ver deploy/oracle/README-catalogo-db.md.

set -Eeuo pipefail
# Dumps legiveis so pelo dono: o do Supabase tem e-mail e dado pessoal de todo usuario.
umask 077

RAIZ=/opt/oferta-games
ARQUIVO_ENV="$RAIZ/deploy/oracle/.env"
DIR_BACKUPS=/opt/backups
CONTAINER=oferta-games-catalogo-db
DATA=$(date +%Y%m%d_%H%M%S)
MANTER_DIARIOS=7
MANTER_SEMANAIS_DIAS=35

# Le UMA chave do .env sem dar source no arquivo inteiro: valores com &, $ ou espaco quebrariam o
# shell (a DATABASE_URL tem senha com caractere especial).
ler_env() {
  grep -E "^$1=" "$ARQUIVO_ENV" | tail -n 1 | cut -d= -f2- || true
}

HEARTBEAT=$(ler_env BACKUP_HEARTBEAT_URL)
REMOTO=$(ler_env BACKUP_RCLONE_REMOTE)

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

ping_heartbeat() {
  [ -n "$HEARTBEAT" ] || return 0
  curl -fsS -m 10 --retry 3 -o /dev/null "$HEARTBEAT$1" || log "AVISO: heartbeat $1 nao respondeu"
}

ARQUIVO_SENHA=""
limpar() { [ -n "$ARQUIVO_SENHA" ] && rm -f "$ARQUIVO_SENHA"; return 0; }
falhou() {
  log "ERRO: backup falhou na linha $1"
  rm -f "$DIR_BACKUPS"/catalogo/*.parcial "$DIR_BACKUPS"/supabase/*.parcial
  ping_heartbeat /fail
  limpar
  exit 1
}
trap 'falhou $LINENO' ERR
trap limpar EXIT

# Dump em formato custom (-Fc): comprimido e restauravel por tabela. Grava num .parcial e so
# renomeia depois de validar, pra um dump cortado no meio nunca ser tratado como bom nem apagar
# um backup valido na rotacao.
validar_e_publicar() {
  local parcial=$1 final=$2 minimo=$3
  local tamanho
  tamanho=$(stat -c %s "$parcial")
  if [ "$tamanho" -lt "$minimo" ]; then
    log "ERRO: $(basename "$final") com $tamanho bytes (minimo esperado $minimo) - dump vazio ou truncado"
    rm -f "$parcial"
    return 1
  fi
  # pg_restore --list confere cabecalho e indice (formato certo, arquivo legivel) sem restaurar
  # nada. Dump cortado no meio ja foi pego antes: pg_dump sai com erro e o pipefail dispara o trap.
  docker exec -i "$CONTAINER" pg_restore --list > /dev/null < "$parcial"
  mv "$parcial" "$final"
  log "OK $(basename "$final") ($(du -h "$final" | cut -f1))"
}

# Mantem os N mais recentes + os de domingo ate MANTER_SEMANAIS_DIAS. Nome do arquivo carrega a
# data (prefixo_AAAAMMDD_HHMMSS.dump), entao nao depende de mtime (que muda num cp/restore).
rotacionar() {
  local dir=$1 prefixo=$2 limite arquivos
  limite=$(date -d "-$MANTER_SEMANAIS_DIAS days" +%Y%m%d)
  # Glob com nullglob em vez de ls: com set -e + pipefail, um "ls" sem arquivo retorna erro e
  # dispararia o trap de falha.
  shopt -s nullglob
  arquivos=("$dir"/"$prefixo"_*.dump)
  shopt -u nullglob
  printf '%s\n' "${arquivos[@]}" | sort -r | tail -n +$((MANTER_DIARIOS + 1)) | while read -r arquivo; do
    [ -n "$arquivo" ] || continue
    local dia
    dia=$(basename "$arquivo" | sed -E "s/^${prefixo}_([0-9]{8})_.*/\1/")
    if [ "$(date -d "$dia" +%u)" = "7" ] && [ "$dia" -ge "$limite" ]; then
      continue
    fi
    rm -f "$arquivo"
    log "Rotacao: removido $(basename "$arquivo")"
  done
}

log "Inicio do backup"
ping_heartbeat /start
mkdir -p "$DIR_BACKUPS/catalogo" "$DIR_BACKUPS/supabase"

# --- catalogo ---
CATALOGO="$DIR_BACKUPS/catalogo/catalogo_$DATA.dump"
docker exec "$CONTAINER" pg_dump -U catalogo -d catalogo -Fc --no-owner --no-privileges > "$CATALOGO.parcial"
# ~100 MB hoje; 10 MB pega um banco que subiu vazio (ver README-catalogo-db.md).
validar_e_publicar "$CATALOGO.parcial" "$CATALOGO" 10000000

# --- supabase (dados de usuario) ---
# A URL vai por --env-file (arquivo 600, apagado no fim) e nao por argumento: argumento de
# "docker exec" aparece no ps de qualquer usuario da VM, e a URL carrega a senha do banco.
ARQUIVO_SENHA=$(mktemp)
grep -E '^DATABASE_URL=' "$ARQUIVO_ENV" | tail -n 1 > "$ARQUIVO_SENHA"
SUPABASE="$DIR_BACKUPS/supabase/supabase_$DATA.dump"
docker exec --env-file "$ARQUIVO_SENHA" "$CONTAINER" \
  sh -c 'pg_dump "$DATABASE_URL" -n public -n auth -Fc --no-owner --no-privileges' > "$SUPABASE.parcial"
limpar
ARQUIVO_SENHA=""
# ~330 KB hoje (poucos usuarios); 20 KB pega schema sem dado nenhum.
validar_e_publicar "$SUPABASE.parcial" "$SUPABASE" 20000

rotacionar "$DIR_BACKUPS/catalogo" catalogo
rotacionar "$DIR_BACKUPS/supabase" supabase

# --- copia fora da VM ---
if [ -n "$REMOTO" ]; then
  command -v rclone > /dev/null || { log "ERRO: BACKUP_RCLONE_REMOTE definido mas rclone nao instalado"; false; }
  rclone copyto "$CATALOGO" "$REMOTO/catalogo/$(basename "$CATALOGO")"
  rclone copyto "$SUPABASE" "$REMOTO/supabase/$(basename "$SUPABASE")"
  # Rotacao remota por idade: mais simples que replicar a regra diario+semanal, e o bucket tem
  # espaco de sobra pra 5 semanas de dumps (~3,5 GB).
  rclone delete --min-age "${MANTER_SEMANAIS_DIAS}d" "$REMOTO"
  log "Copia externa enviada para $REMOTO"
else
  log "AVISO: BACKUP_RCLONE_REMOTE nao configurado - backups existem SO no disco desta VM"
fi

ping_heartbeat ""
log "Backup concluido"
