param(
    [Parameter(Mandatory = $true)]
    [string] $ChaveSsh,

    [string] $HostOracle = "163.176.220.243",
    [string] $UsuarioOracle = "ubuntu",
    [string] $UrlSaude = "https://api.163.176.220.243.sslip.io/actuator/health"
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
sleep 10
curl -fsS --retry 6 --retry-delay 2 "__URL_SAUDE__"
'@

$comandoRemoto = $comandoRemoto.Replace('__URL_SAUDE__', $UrlSaude)
ssh -i $ChaveSsh -o IdentitiesOnly=yes "$UsuarioOracle@$HostOracle" $comandoRemoto
