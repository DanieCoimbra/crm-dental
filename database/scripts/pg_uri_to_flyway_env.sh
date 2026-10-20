#!/usr/bin/env bash
# Converte a URI de conexão do Postgres usada pelos secrets (SEM senha) nas variáveis do Flyway.
#   entrada : postgresql://usuario@host:porta/banco?sslmode=require
#   saída   : linhas "export FLYWAY_URL='jdbc:postgresql://host:porta/banco?sslmode=require'" e "export FLYWAY_USER='usuario'"
# A senha vem à parte (secret *_PASSWORD). Uma URI com senha é recusada: ela acabaria em logs e em variáveis.
# TLS é obrigatório (sslmode=require, verify-ca ou verify-full). A saída é feita para `eval`, então a URI não pode
# conter aspas, cifrão, crase nem espaços.
#
# Segundo argumento opcional: papel exigido. O usuário precisa ser exatamente esse papel ou "<papel>.<ref>" (Supavisor); assim um
# secret cadastrado com outro usuário (por exemplo postgres) é recusado em vez de rodar com privilégio maior que o previsto.
#
# Uso : eval "$(bash database/scripts/pg_uri_to_flyway_env.sh "$SUPABASE_OWNER_URL" crm_owner)"
set -euo pipefail

uri="${1:?uso: pg_uri_to_flyway_env.sh <postgresql://usuario@host:porta/banco?sslmode=require> [papel]}"
required_role="${2:-}"

if [[ "$uri" == *"'"* || "$uri" == *'$'* || "$uri" == *'`'* || "$uri" == *' '* || "$uri" == *'"'* || "$uri" == *';'* ]]; then
  echo "URI contém caracteres não permitidos" >&2
  exit 2
fi
if [[ ! "$uri" =~ ^postgres(ql)?://([^@:/]+)@([^:/?]+)(:([0-9]+))?/([^?]+)(\?(.*))?$ ]]; then
  echo "URI inválida: use postgresql://usuario@host:porta/banco?sslmode=require (sem senha na URI)" >&2
  exit 2
fi
user="${BASH_REMATCH[2]}"
host="${BASH_REMATCH[3]}"
port="${BASH_REMATCH[5]:-5432}"
database="${BASH_REMATCH[6]}"
params="${BASH_REMATCH[8]:-}"

case "&$params&" in
  *"&sslmode=require&"*|*"&sslmode=verify-ca&"*|*"&sslmode=verify-full&"*) ;;
  *) echo "a URI precisa de sslmode=require (ou verify-ca/verify-full)" >&2; exit 3;;
esac

if [[ -n "$required_role" && "$user" != "$required_role" && "$user" != "$required_role".* ]]; then
  echo "o usuário da URI deve ser o papel $required_role (ou $required_role.<ref> no Supavisor)" >&2
  exit 4
fi

printf "export FLYWAY_URL='jdbc:postgresql://%s:%s/%s?%s'\n" "$host" "$port" "$database" "$params"
printf "export FLYWAY_USER='%s'\n" "$user"
