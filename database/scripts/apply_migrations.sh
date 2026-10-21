#!/usr/bin/env bash
# Aplica as migrations migrations/V*.sql em ordem, como crm_owner, cada uma em UMA transação (--single-transaction).
# SEM controle de versão: nada registra o que já foi aplicado, então a escolha do ponto de partida é de quem executa.
#   uso      : bash database/scripts/apply_migrations.sh [versao_inicial]      (padrão: 1; ex.: 14 aplica só V014 em diante)
#   conexão  : variáveis do libpq (PGHOST, PGPORT, PGUSER=crm_owner, PGPASSWORD, PGDATABASE, PGSSLMODE) ou OWNER_URL
#              (postgresql://crm_owner.<ref>@host:5432/postgres?sslmode=require, SEM senha; a senha vai em PGPASSWORD).
# Com OWNER_URL, TLS é obrigatório e o usuário precisa ser crm_owner (ou crm_owner.<ref> no Supavisor).
# Falha na primeira migration que errar: ela é desfeita inteira e as seguintes não rodam.
set -euo pipefail

DB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
from="${1:-1}"
[[ "$from" =~ ^[0-9]+$ ]] || { echo "versao_inicial deve ser um numero inteiro" >&2; exit 2; }

target=()
if [ -n "${OWNER_URL:-}" ]; then
  if [[ ! "$OWNER_URL" =~ ^postgres(ql)?://([^@:/]+)@[^/]+/[^?]+\?(.*)$ ]]; then
    echo "OWNER_URL invalida: use postgresql://usuario@host:porta/banco?sslmode=require (sem senha)" >&2; exit 2
  fi
  user="${BASH_REMATCH[2]}"; params="${BASH_REMATCH[3]}"
  case "&$params&" in
    *"&sslmode=require&"*|*"&sslmode=verify-ca&"*|*"&sslmode=verify-full&"*) ;;
    *) echo "a URI precisa de sslmode=require (ou verify-ca/verify-full)" >&2; exit 3;;
  esac
  if [ "$user" != crm_owner ] && [[ "$user" != crm_owner.* ]]; then
    echo "o usuario da URI deve ser crm_owner (ou crm_owner.<ref>), nao $user" >&2; exit 4
  fi
  target=("$OWNER_URL")
fi

applied=0
for f in $(ls "$DB_DIR"/migrations/V*.sql | sort); do
  name="$(basename "$f")"
  version=$((10#$(echo "$name" | sed -E 's/^V0*([0-9]+)__.*/\1/')))
  [ "$version" -ge "$from" ] || continue
  echo "   $name"
  psql -X -q -v ON_ERROR_STOP=1 --single-transaction "${target[@]}" -f "$f"
  applied=$((applied + 1))
done
echo "== $applied migration(s) aplicada(s)"
