#!/usr/bin/env bash
# Backup lógico do schema core: pg_dump como crm_backup (somente leitura, sem BYPASSRLS), empacotado com as contagens e
# o catálogo e criptografado com age (chave PÚBLICA). O dump nunca fica em claro fora de um diretório temporário 0700.
#
# Entrada (variáveis de ambiente; nada de segredo em argumento):
#   BACKUP_URL             URI SEM senha: postgresql://crm_backup.<ref>@host:5432/postgres?sslmode=require
#   PGPASSWORD             senha do crm_backup (>= 24 caracteres)
#   BACKUP_AGE_PUBLIC_KEY  chave pública age (age1...)
#   OUT_DIR                pasta de saída (padrão .)
#   BACKUP_MIN_BYTES       tamanho mínimo do arquivo final (padrão 10240): evita "backup vazio" silencioso
#   BACKUP_REQUIRE_TLS     1 (padrão) exige sslmode=require|verify-ca|verify-full; 0 SÓ para o banco local dos testes
# Saída: OUT_DIR/crm-core-<UTC yyyymmddThhmmss>.dump.age (conteúdo: tar com core.dump, counts.txt e schema.txt)
#        e, se GITHUB_OUTPUT existir, backup_file= e backup_name=.
set -euo pipefail
umask 077

fail() { echo "backup: $*" >&2; exit 1; }

# --- Validações ANTES de gravar qualquer arquivo ------------------------------------------------------------
: "${BACKUP_URL:?defina BACKUP_URL (URI sem senha)}"
: "${PGPASSWORD:?defina PGPASSWORD (senha do crm_backup)}"
[ -n "${BACKUP_AGE_PUBLIC_KEY:-}" ] || fail "BACKUP_AGE_PUBLIC_KEY ausente (nenhum arquivo foi gravado)"
OUT_DIR="${OUT_DIR:-.}"
MIN_BYTES="${BACKUP_MIN_BYTES:-10240}"
REQUIRE_TLS="${BACKUP_REQUIRE_TLS:-1}"

[[ "$BACKUP_AGE_PUBLIC_KEY" =~ ^age1[0-9a-z]{58}$ ]] || fail "BACKUP_AGE_PUBLIC_KEY não parece uma chave pública age (age1...)"
[ "${#PGPASSWORD}" -ge 24 ] || fail "a senha do crm_backup deve ter pelo menos 24 caracteres"
if [[ "$BACKUP_URL" =~ ^postgres(ql)?://[^@/]*:[^@/]*@ ]]; then fail "a URI não pode conter senha (use PGPASSWORD)"; fi
if [ "$REQUIRE_TLS" = "1" ]; then
  case "$BACKUP_URL" in
    *sslmode=require*|*sslmode=verify-ca*|*sslmode=verify-full*) ;;
    *) fail "BACKUP_URL precisa de sslmode=require (ou verify-ca/verify-full)";;
  esac
fi
for tool in pg_dump psql age tar; do command -v "$tool" >/dev/null 2>&1 || fail "ferramenta ausente: $tool"; done
[ -d "$OUT_DIR" ] || fail "OUT_DIR não existe: $OUT_DIR"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
q() { psql "$BACKUP_URL" -X -At -v ON_ERROR_STOP=1 -c "$1"; }

# --- O cliente não pode ser mais antigo que o servidor --------------------------------------------------------
server_major=$(( $(q "SHOW server_version_num") / 10000 ))
client_major=$(pg_dump --version | sed -E 's/^[^0-9]*([0-9]+).*/\1/')
[ "$client_major" -ge "$server_major" ] || fail "pg_dump $client_major é mais antigo que o servidor $server_major: ajuste PG_MAJOR dos workflows"

# --- Toda tabela com RLS precisa de política de leitura do crm_backup (senão o dump sairia incompleto, sem erro) ----
uncovered=$(q "
  SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'core' AND c.relkind IN ('r', 'p') AND c.relrowsecurity
     AND NOT EXISTS (SELECT 1 FROM pg_policy p
                      WHERE p.polrelid = c.oid AND p.polcmd IN ('r', '*')
                        AND (SELECT oid FROM pg_roles WHERE rolname = 'crm_backup') = ANY (p.polroles)
                        AND pg_get_expr(p.polqual, p.polrelid) = 'true')")
[ -z "$uncovered" ] || fail "tabela(s) sem política de leitura do crm_backup, o dump sairia incompleto: $uncovered"

# --- Contagens (antes e depois do dump) e catálogo --------------------------------------------------------------
SQL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
psql "$BACKUP_URL" -X -At -v ON_ERROR_STOP=1 -f "$SQL_DIR/table_counts.sql" > "$work/counts_before.txt"
[ -s "$work/counts_before.txt" ] || fail "nenhuma tabela encontrada em core (schema vazio?)"

# O catálogo gravado aqui é o que a verificação de restauração compara depois (a mesma consulta roda nos dois lados).
psql "$BACKUP_URL" -X -q -At -v ON_ERROR_STOP=1 -f "$SQL_DIR/catalog_snapshot.sql" | LC_ALL=C sort > "$work/schema.txt"

# --- Dump ---------------------------------------------------------
pg_dump "$BACKUP_URL" -Fc --schema=core \
  --enable-row-security --no-owner --no-acl -f "$work/core.dump"
[ -s "$work/core.dump" ] || fail "o pg_dump não produziu arquivo"

psql "$BACKUP_URL" -X -At -v ON_ERROR_STOP=1 -f "$SQL_DIR/table_counts.sql" > "$work/counts_after.txt"
# counts.txt: tabela|mínimo|máximo (escritas durante o dump fazem a contagem variar entre antes e depois).
# Limitação conhecida: o dump lê um snapshot único; inserções seguidas de remoções durante o dump podem levar a contagem do
# snapshot para fora de [mín, máx] e reprovar a verificação mensal sem que o backup esteja ruim (reexecute o backup).
join -t '|' "$work/counts_before.txt" "$work/counts_after.txt" \
  | awk -F'|' '{ lo = ($2 < $3 ? $2 : $3) + 0; hi = ($2 > $3 ? $2 : $3) + 0; print $1 "|" lo "|" hi }' > "$work/counts.txt"
[ "$(wc -l < "$work/counts.txt")" = "$(wc -l < "$work/counts_before.txt")" ] || fail "tabelas mudaram durante o backup; tente de novo"

# --- Empacota e criptografa (só a chave pública está aqui; sem a privada ninguém lê) ------------------------------------
tar -C "$work" -cf "$work/package.tar" core.dump counts.txt schema.txt
timestamp="$(date -u +%Y%m%dT%H%M%S)"
name="crm-core-${timestamp}.dump.age"
age -r "$BACKUP_AGE_PUBLIC_KEY" -o "$work/$name" "$work/package.tar"

size=$(wc -c < "$work/$name")
[ "$size" -ge "$MIN_BYTES" ] || fail "backup menor que o mínimo ($size < $MIN_BYTES bytes): descartado"
mv "$work/$name" "$OUT_DIR/$name"

echo "backup gerado: $name ($size bytes)"
echo "contagens (tabela|mín|máx):"
sed 's/^/  /' "$work/counts.txt"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  { echo "backup_file=$OUT_DIR/$name"; echo "backup_name=$name"; } >> "$GITHUB_OUTPUT"
fi
