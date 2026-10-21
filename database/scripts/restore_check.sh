#!/usr/bin/env bash
# Verificação de restauração: descriptografa um backup (scripts/backup.sh), restaura em um PostgreSQL DESCARTÁVEL e compara
# contagens e catálogo com o que foi gravado no próprio backup. Dois modos:
#   full  restaura tudo o que está no dump (tabelas, dados, índices, constraints, políticas, funções) sobre o schema criado
#         pelo bootstrap. Prova que o dump é íntegro e completo. Atenção: --no-owner --no-acl faz o dump NÃO trazer GRANTs
#         nem o dono crm_definer das funções, então esse banco não serve para a aplicação.
#   data  é o procedimento de recuperação de desastre: bootstrap -> as migrations (schema, GRANTs e donos corretos) ->
#         `pg_restore --data-only`. Depois confere as contagens e executa a função de login como crm_app.
#
# Entrada (variáveis de ambiente):
#   BACKUP_FILE                 caminho do crm-core-*.dump.age
#   BACKUP_AGE_PRIVATE_KEY      chave privada age (AGE-SECRET-KEY-1...), nunca em arquivo versionado
#   RESTORE_PGHOST/PORT/USER/PASSWORD  servidor DESCARTÁVEL (superusuário). Nunca aponte para o Supabase.
#   RESTORE_MODES               "full data" (padrão)
set -euo pipefail
umask 077

fail() { echo "restore_check: $*" >&2; exit 1; }
DB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${BACKUP_FILE:?defina BACKUP_FILE}"
[ -f "$BACKUP_FILE" ] || fail "arquivo de backup não encontrado: $BACKUP_FILE"
[ -n "${BACKUP_AGE_PRIVATE_KEY:-}" ] || fail "BACKUP_AGE_PRIVATE_KEY ausente"
: "${RESTORE_PGHOST:?defina RESTORE_PGHOST (servidor descartável)}"
: "${RESTORE_PGPASSWORD:?defina RESTORE_PGPASSWORD}"
RESTORE_PGPORT="${RESTORE_PGPORT:-5432}"
RESTORE_PGUSER="${RESTORE_PGUSER:-postgres}"
MODES="${RESTORE_MODES:-full data}"
for tool in age tar pg_restore psql openssl; do command -v "$tool" >/dev/null 2>&1 || fail "ferramenta ausente: $tool"; done

case "$RESTORE_PGHOST" in
  *supabase*|*pooler*) fail "RESTORE_PGHOST parece o Supabase: a restauração de teste só roda em servidor descartável";;
esac

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
pg() { PGHOST="$RESTORE_PGHOST" PGPORT="$RESTORE_PGPORT" PGUSER="$RESTORE_PGUSER" PGPASSWORD="$RESTORE_PGPASSWORD" "$@"; }

# --- Descriptografa e abre o pacote -----------------------------------------------------------------------------
printf '%s\n' "$BACKUP_AGE_PRIVATE_KEY" > "$work/identity.txt"
age -d -i "$work/identity.txt" -o "$work/package.tar" "$BACKUP_FILE" 2>"$work/age.err" \
  || fail "não foi possível descriptografar o backup (chave errada ou arquivo corrompido)"
rm -f "$work/identity.txt"
tar -C "$work" -xf "$work/package.tar" core.dump counts.txt schema.txt 2>/dev/null \
  || fail "pacote sem core.dump, counts.txt ou schema.txt"
rm -f "$work/package.tar"
[ -s "$work/core.dump" ] && [ -s "$work/counts.txt" ] && [ -s "$work/schema.txt" ] || fail "pacote com arquivo vazio"
echo "backup aberto: $(wc -l < "$work/counts.txt") tabelas em counts.txt, $(wc -l < "$work/schema.txt") itens de catálogo"

scram() { SCRAM_PASSWORD="$1" bash "$DB_DIR/scripts/scram_verifier.sh"; }
rand() { head -c 24 /dev/urandom | base64 | tr -d '/+=\n' | head -c 32; }

overall=0
for mode in $MODES; do
  db="restore_${mode}_$(rand | tr 'A-Z' 'a-z' | head -c 8)"
  echo "== modo $mode (banco $db)"
  pg psql -X -q -v ON_ERROR_STOP=1 -d postgres -c "CREATE DATABASE $db" >/dev/null

  # Bootstrap: papéis (no cluster) e schema core vazio, com senhas descartáveis.
  owner_pw="$(rand)$(rand)"; app_pw="$(rand)$(rand)"; backup_pw="$(rand)$(rand)"
  pg psql -X -q -v ON_ERROR_STOP=1 -d "$db" -f "$DB_DIR/tests/support/supabase_stubs.sql"
  pg psql -X -q -v ON_ERROR_STOP=1 -d "$db" \
    -v crm_owner_scram="$(scram "$owner_pw")" -v crm_app_scram="$(scram "$app_pw")" -v crm_backup_scram="$(scram "$backup_pw")" \
    -f "$DB_DIR/bootstrap/00_roles_and_schema.sql"
  # Fase 1: pg_trgm no schema extensions (os índices de busca por nome dependem dela, no dump e nas migrations)
  pg psql -X -q -v ON_ERROR_STOP=1 -d "$db" -f "$DB_DIR/bootstrap/20_extensions.sql"

  restore_ok=1
  if [ "$mode" = "full" ]; then
    # O bootstrap já criou o schema core: tira a entrada do schema do índice do dump
    pg_restore -l "$work/core.dump" | grep -v ' SCHEMA - core ' > "$work/toc.list"
    pg pg_restore --exit-on-error --no-owner --no-acl --role=crm_owner -L "$work/toc.list" -d "$db" "$work/core.dump" \
      || { echo "  FALHA: pg_restore (full) retornou erro"; restore_ok=0; }
  else
    PGHOST="$RESTORE_PGHOST" PGPORT="$RESTORE_PGPORT" PGDATABASE="$db" PGUSER=crm_owner PGPASSWORD="$owner_pw"       bash "$DB_DIR/scripts/apply_migrations.sh" >/dev/null 2>"$work/migrate.err"       || { echo "  FALHA: migrations no banco de restauração"; tail -3 "$work/migrate.err"; restore_ok=0; }
    if [ "$restore_ok" = 1 ]; then
      pg pg_restore --exit-on-error --data-only --disable-triggers --no-owner --no-acl -d "$db" "$work/core.dump" \
        || { echo "  FALHA: pg_restore --data-only retornou erro"; restore_ok=0; }
    fi
  fi

  mode_ok=$restore_ok
  if [ "$restore_ok" = 1 ]; then
    # 1. contagens dentro de [mín, máx] do backup
    while IFS='|' read -r table lo hi; do
      got=$(pg psql -X -At -d "$db" -c "SELECT count(*) FROM core.\"$table\"")
      if [ "$got" -ge "$lo" ] && [ "$got" -le "$hi" ]; then
        echo "  ok   - core.$table: $got linhas (esperado entre $lo e $hi)"
      else
        echo "  FALHA - core.$table: $got linhas, esperado entre $lo e $hi"; mode_ok=0
      fi
    done < "$work/counts.txt"
    # 2. catálogo restaurado == catálogo do backup (constraints, índices, políticas, RLS, gatilhos, funções)
    pg psql -X -q -At -d "$db" -v ON_ERROR_STOP=1 -f "$DB_DIR/scripts/catalog_snapshot.sql" | LC_ALL=C sort > "$work/schema_restored.txt"
    # No modo full o schema vem do próprio dump (texto SQL regerado pelo pg_dump e relido pelo pg_restore): o PostgreSQL pode
    # reescrever o MESMO CHECK numa forma equivalente (ex.: IN (...) vira ANY (ARRAY[...::text])). Por isso, nesse modo, as
    # constraints são comparadas por tabela + nome (todo o resto, estritamente). No modo data o schema vem das migrations: texto exato.
    if [ "$mode" = "full" ]; then
      norm() { awk -F'|' '$1 == "constraint" { print $1 "|" $2 "|" $3; next } { print }' "$1"; }
      norm "$work/schema.txt" > "$work/schema.cmp"; norm "$work/schema_restored.txt" > "$work/schema_restored.cmp"
    else
      cp "$work/schema.txt" "$work/schema.cmp"; cp "$work/schema_restored.txt" "$work/schema_restored.cmp"
    fi
    if diff -u "$work/schema.cmp" "$work/schema_restored.cmp" > "$work/schema.diff"; then
      echo "  ok   - catálogo idêntico ao do backup ($(wc -l < "$work/schema.txt") itens: constraints, índices, políticas, RLS, gatilhos, funções)"
    else
      echo "  FALHA - catálogo restaurado difere do backup:"; head -10 "$work/schema.diff"; mode_ok=0
    fi
    # 3. só no modo data: o banco recuperado FUNCIONA para a aplicação (donos e GRANTs corretos)
    if [ "$mode" = "data" ]; then
      email=$(pg psql -X -At -d "$db" -c "SELECT email FROM core.users ORDER BY created_at LIMIT 1")
      if [ -n "$email" ]; then
        # o e-mail vem do backup: vai como variável do psql (:'e'), nunca interpolado no SQL
        found=$(printf '%s\n' "SET SESSION AUTHORIZATION crm_app;" "SELECT count(*) FROM core.find_user_for_login(:'e');" \
          | pg psql -X -At -d "$db" -v ON_ERROR_STOP=1 -v e="$email" | tail -1)
        [ "$found" = "1" ] && echo "  ok   - como crm_app, a função de login encontra um usuário restaurado" \
                           || { echo "  FALHA - função de login não achou o usuário restaurado ($found)"; mode_ok=0; }
      else
        echo "  aviso - sem usuários no backup: teste funcional do login pulado"
      fi
    fi
  fi
  pg psql -X -q -d postgres -c "DROP DATABASE IF EXISTS $db WITH (FORCE)" >/dev/null 2>&1 || true
  [ "$mode_ok" = 1 ] && echo "  modo $mode: OK" || { echo "  modo $mode: FALHOU"; overall=1; }
done

[ "$overall" = 0 ] && echo "restore_check: OK" || { echo "restore_check: FALHOU"; exit 1; }
