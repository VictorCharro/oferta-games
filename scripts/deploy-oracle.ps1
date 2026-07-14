param(
    [Parameter(Mandatory = $true)]
    [string] $ChaveSsh,

    [string] $HostOracle = "163.176.220.243",
    [string] $UsuarioOracle = "ubuntu"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ChaveSsh)) {
    throw "Chave SSH nao encontrada: $ChaveSsh"
}

$comandoRemoto = @'
set -e
cd /opt/oferta-games
GIT_SSH_COMMAND='ssh -i ~/.ssh/github_actions_oracle -o IdentitiesOnly=yes' git pull --ff-only origin master
docker compose -f deploy/oracle/compose.yml up -d --build
docker compose -f deploy/oracle/compose.yml ps
curl -fsS http://127.0.0.1:8080/actuator/health
'@

ssh -i $ChaveSsh -o IdentitiesOnly=yes "$UsuarioOracle@$HostOracle" $comandoRemoto
