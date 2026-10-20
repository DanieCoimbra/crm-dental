#!/usr/bin/env bash
# Executa o Flyway Community com a configuração do repositório (database/flyway.conf).
#   - Usa o `flyway` local se existir (imagem de testes do banco); senão o contêiner pinado flyway/flyway.
#   - A conexão vem SÓ de variáveis de ambiente: FLYWAY_URL, FLYWAY_USER, FLYWAY_PASSWORD (a senha nunca vai em argumento).
#
# Uso : FLYWAY_URL=jdbc:postgresql://host:5432/postgres FLYWAY_USER=crm_owner FLYWAY_PASSWORD=... \
#       bash database/scripts/flyway.sh info | validate | migrate
# Env : FLYWAY_IMAGE (padrão flyway/flyway:11.20.3), FLYWAY_DOCKER_NETWORK (padrão host, só no modo contêiner)
set -euo pipefail

: "${FLYWAY_URL:?defina FLYWAY_URL (jdbc:postgresql://...)}"
: "${FLYWAY_USER:?defina FLYWAY_USER}"
: "${FLYWAY_PASSWORD:?defina FLYWAY_PASSWORD}"

DB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(dirname "$DB_DIR")"
IMAGE="${FLYWAY_IMAGE:-flyway/flyway:11.20.3}"

# Local das migrations ABSOLUTO (a menos que o chamador passe o seu -locations=...): o caminho relativo do flyway.conf só vale
# quando o diretório de trabalho é a raiz do repositório, o que não ocorre na imagem de testes (repositório montado em /database).
default_locations() {
  local base="$1"
  for arg in "${@:2}"; do
    case "$arg" in -locations=*) return 0;; esac
  done
  printf '%s' "-locations=filesystem:${base%/}/database/migrations"
}

if command -v flyway >/dev/null 2>&1; then
  loc="$(default_locations "$ROOT" "$@")"
  # sem -workingDirectory: com "/" como diretório de trabalho o Flyway varre o sistema de arquivos inteiro
  cd "$DB_DIR"
  exec flyway -configFiles="$DB_DIR/flyway.conf" ${loc:+"$loc"} "$@"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "flyway.sh: nem 'flyway' nem 'docker' disponíveis" >&2
  exit 127
fi

loc="$(default_locations /repo "$@")"
# MSYS_NO_PATHCONV evita que o Git Bash (Windows) reescreva os caminhos do contêiner.
MSYS_NO_PATHCONV=1 exec docker run --rm --network "${FLYWAY_DOCKER_NETWORK:-host}" \
  -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD \
  -v "$ROOT:/repo:ro" "$IMAGE" \
  -configFiles=/repo/database/flyway.conf ${loc:+"$loc"} "$@"
