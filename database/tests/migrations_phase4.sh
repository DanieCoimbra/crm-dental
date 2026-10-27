#!/usr/bin/env bash
# Fase 4 / F06 (teste 140): migrations V031-V035 do zero, reversões U035 -> U031 e a função de anonimização restaurada, num banco
# descartável PRÓPRIO (phase4_scratch), para não mexer no banco dos demais testes.
#   140a  V001-V035 aplicam do zero; toda V031..V035 tem seu U; os objetos da Fase 4 existem
#   140b  a U035 remove o índice do dashboard; a V035 reaplica
#   140c  a U034 restaura o comportamento da V029 (documentos NÃO são zerados na anonimização); a V034 reaplicada volta a zerar
#   140d  a U033 se RECUSA, de forma atômica, quando o audit_log já tem linhas de documentos (o log é só de inserção)
#   140e  num banco novo sem auditoria, a cadeia U035 -> U031 limpa a Fase 4, preserva as Fases 0 a 3 (ck_audit_table volta a 9 tabelas)
#         e V031-V035 reaplicam
# Requer: psql/openssl no PATH, PGHOST/PGPORT/PGUSER/PGPASSWORD (administrador de um servidor DESCARTÁVEL), DB_DIR e as senhas
#         CRM_OWNER_PW, CRM_APP_PW e CRM_BACKUP_PW do bootstrap do harness.
set -uo pipefail

DB_DIR="${DB_DIR:?DB_DIR nao definido}"
: "${CRM_OWNER_PW:?CRM_OWNER_PW nao definido}" "${CRM_APP_PW:?CRM_APP_PW nao definido}" "${CRM_BACKUP_PW:?CRM_BACKUP_PW nao definido}"
ADMIN_USER="$PGUSER"; ADMIN_PW="$PGPASSWORD"
SCRATCH=phase4_scratch

fail=0
ok()  { echo "  ok   - $1"; }
bad() { echo "  FAIL - $1"; fail=1; }
admin() { PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PW" PGDATABASE=postgres "$@"; }
admin_s() { PGUSER="$ADMIN_USER" PGPASSWORD="$ADMIN_PW" PGDATABASE=$SCRATCH "$@"; }
owner_s() { PGUSER=crm_owner PGPASSWORD="$CRM_OWNER_PW" PGDATABASE=$SCRATCH "$@"; }
app_s() { PGUSER=crm_app PGPASSWORD="$CRM_APP_PW" PGDATABASE=$SCRATCH "$@"; }

work_err="$(mktemp)"
cleanup() { rm -f "$work_err"; admin psql -X -q -c "DROP DATABASE IF EXISTS $SCRATCH WITH (FORCE)" >/dev/null 2>&1; }
trap cleanup EXIT

echo "migrations_phase4:"

mapfile -t vfiles < <(ls "$DB_DIR"/migrations/V*.sql | sort)
vnum() { echo $((10#$(basename "$1" | sed -E 's/^V0*([0-9]+)__.*/\1/'))); }
apply_range() { # $1 = primeira versão  $2 = última versão; cada migration em UMA transação, como crm_owner
  local f v
  for f in "${vfiles[@]}"; do
    v=$(vnum "$f"); [ "$v" -ge "$1" ] && [ "$v" -le "$2" ] || continue
    owner_s psql -X -q -v ON_ERROR_STOP=1 --single-transaction -f "$f" >/dev/null 2>"$work_err" || { echo "      falhou em $(basename "$f"): $(head -2 "$work_err")"; return 1; }
  done
}
revert_range() { # $1 = maior U, $2 = menor U (ordem inversa)
  local hi="$1" lo="$2" v f
  for v in $(seq "$hi" -1 "$lo"); do
    f=$(ls "$DB_DIR"/rollback/U$(printf '%03d' "$v")__*.sql 2>/dev/null | head -1)
    [ -n "$f" ] || { echo "      sem U$(printf '%03d' "$v")"; return 1; }
    owner_s psql -X -q -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>"$work_err" || { echo "      falhou em $(basename "$f"): $(head -2 "$work_err")"; return 1; }
  done
}
q() { admin_s psql -X -At -c "$1"; }

# --- 140a: numeração e reversões ------------------------------------------------------------------------------------------------
last=$(vnum "${vfiles[${#vfiles[@]}-1]}")
[ "$last" = "35" ] && [ "${#vfiles[@]}" = "35" ] && ok "140a: 35 migrations (V001..V035), sem lacunas, a ultima e a V035" || bad "140a: esperadas 35 migrations, ha ${#vfiles[@]} (ultima V$last)"
missing=""
for v in $(seq 31 35); do ls "$DB_DIR"/rollback/U$(printf '%03d' "$v")__*.sql >/dev/null 2>&1 || missing="$missing U$(printf '%03d' "$v")"; done
[ -z "$missing" ] && ok "140a: toda migration da Fase 4 (V031..V035) tem seu U de reversao" || bad "140a: faltam reversoes:$missing"

scram() { SCRAM_PASSWORD="$1" bash "$DB_DIR/scripts/scram_verifier.sh"; }
fresh_db() { # recria o banco descartavel e roda o bootstrap dos papeis e das extensoes
  admin psql -X -q -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $SCRATCH WITH (FORCE)" -c "CREATE DATABASE $SCRATCH" >/dev/null 2>&1 || return 1
  admin_s psql -X -q -v ON_ERROR_STOP=1 -v crm_owner_scram="$(scram "$CRM_OWNER_PW")" -v crm_app_scram="$(scram "$CRM_APP_PW")" \
    -v crm_backup_scram="$(scram "$CRM_BACKUP_PW")" -f "$DB_DIR/bootstrap/00_roles_and_schema.sql" >/dev/null 2>&1 || return 1
  admin_s psql -X -q -v ON_ERROR_STOP=1 -f "$DB_DIR/bootstrap/20_extensions.sql" >/dev/null 2>&1
}
fresh_db && ok "banco descartavel $SCRATCH criado e bootstrap dos papeis e das extensoes feito" \
  || { bad "nao foi possivel criar $SCRATCH ou o bootstrap falhou"; echo "migrations_phase4: FALHOU"; exit 1; }

apply_range 1 35 && ok "140a: V001-V035 aplicam do zero" || { bad "140a: V001-V035 falharam"; echo "migrations_phase4: FALHOU"; exit 1; }
[ "$(q "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'core' AND c.relname = 'clinical_documents' AND c.relrowsecurity AND c.relforcerowsecurity")" = "1" ] \
  && ok "140a: clinical_documents existe com RLS ENABLE + FORCE" || bad "140a: clinical_documents ausente ou sem RLS FORCE"
[ "$(q "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'core' AND table_name = 'users' AND column_name IN ('cro_number', 'cro_state')")" = "2" ] \
  && ok "140a: users.cro_number e users.cro_state existem" || bad "140a: colunas do CRO ausentes"
[ "$(q "SELECT count(*) FROM pg_indexes WHERE schemaname = 'core' AND indexname = 'ix_appt_completed_patient'")" = "1" ] \
  && ok "140a: ix_appt_completed_patient existe" || bad "140a: indice do dashboard ausente"

# --- 140b: U035 / V035 -----------------------------------------------------------------------------------------------------------
revert_range 35 35 && ok "140b: U035 reverte" || bad "140b: U035 falhou"
[ "$(q "SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_appt_completed_patient'")" = "0" ] && ok "140b: o indice sai com a U035" || bad "140b: indice ainda existe"
apply_range 35 35 && ok "140b: a V035 reaplica depois da U035" || bad "140b: a V035 nao reaplicou"

# --- 140c: a U034 restaura o comportamento da V029 -------------------------------------------------------------------------------
seed_patient() { # $1 sufixo  -> cria clínica/dentista com CRO/paciente inativo/documento; imprime "clinica usuario paciente documento"
  local c u p d
  c=$(admin_s psql -X -At -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'p4-$1') RETURNING id" | head -1)
  u=$(admin_s psql -X -At -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$c', 'Dentista', 'p4-$1@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id" | head -1)
  p=$(admin_s psql -X -At -c "SELECT gen_random_uuid()"); d=$(admin_s psql -X -At -c "SELECT gen_random_uuid()")
  admin_s psql -X -q -v ON_ERROR_STOP=1 -c "SELECT set_config('app.clinic_id', '$c', false), set_config('app.role', 'DENTIST_ADMIN', false), set_config('app.user_id', '$u', false);
    UPDATE core.users SET cro_number = '12345', cro_state = 'SP' WHERE id = '$u';
    INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES ('$p', '$c', 'Paciente $1', '1990-01-01', '11999990000', '$u');
    INSERT INTO core.clinical_documents (id, clinic_id, patient_id, document_type, issued_on, rest_days, content, sha256, created_by)
      VALUES ('$d', '$c', '$p', 'CERTIFICATE', '2031-07-01', 3, repeat('QUJD', 20), repeat('a', 64), '$u');
    UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = '$p'" >/dev/null 2>&1 || return 1
  echo "$c $u $p $d"
}
anonymize() { # $1 clínica  $2 usuário  $3 paciente; imprime o SQLSTATE do erro ou OK
  local out
  out=$(app_s psql -X -At -v VERBOSITY=sqlstate -c "BEGIN; SELECT set_config('app.clinic_id', '$1', true), set_config('app.role', 'DENTIST_ADMIN', true), set_config('app.user_id', '$2', true); SELECT core.anonymize_patient('$3'); COMMIT;" 2>&1)
  if echo "$out" | grep -q 'ERROR'; then echo "$out" | grep -o 'ERROR:  [A-Z0-9]*' | head -1 | sed 's/ERROR:  //'; else echo OK; fi
}

revert_range 34 34 && ok "140c: U034 reverte" || bad "140c: U034 falhou"
read -r C1 U1 P1 D1 <<< "$(seed_patient restaurada)"
if [ -n "${P1:-}" ]; then
  r=$(anonymize "$C1" "$U1" "$P1")
  [ "$r" = "OK" ] && ok "140c: apos a U034 a anonimizacao passa (comportamento da V029)" || bad "140c: apos a U034 a anonimizacao deveria passar (obtido: $r)"
  [ "$(q "SELECT count(*) FROM core.clinical_documents WHERE id = '$D1' AND content IS NOT NULL AND anonymized_at IS NULL")" = "1" ] \
    && ok "140c: apos a U034 o documento NAO e zerado" || bad "140c: o documento deveria permanecer intacto"
else
  bad "140c: nao foi possivel criar o paciente de teste"
fi
n_pol=$(q "SELECT count(*) FROM pg_policy p WHERE (SELECT oid FROM pg_roles WHERE rolname = 'crm_definer') = ANY (p.polroles) AND p.polrelid = 'core.clinical_documents'::regclass AND p.polcmd = 'w'")
[ "$n_pol" = "0" ] && ok "140c: a politica de UPDATE do crm_definer sai com a U034" || bad "140c: restou a politica de UPDATE do crm_definer"
[ "$(q "SELECT has_column_privilege('crm_definer', 'core.clinical_documents', 'content', 'UPDATE')")" = "f" ] \
  && ok "140c: o UPDATE de colunas do crm_definer sai com a U034" || bad "140c: crm_definer ainda atualiza content"
[ "$(q "SELECT has_column_privilege('crm_definer', 'core.clinical_documents', 'anonymized_at', 'SELECT')")" = "t" ] \
  && ok "140c: o SELECT de colunas da V033 (download) permanece" || bad "140c: o SELECT de colunas da V033 foi perdido"

apply_range 34 34 && ok "140c: a V034 reaplica depois da U034" || bad "140c: a V034 nao reaplicou"
read -r C2 U2 P2 D2 <<< "$(seed_patient reaplicada)"
if [ -n "${P2:-}" ]; then
  r=$(anonymize "$C2" "$U2" "$P2")
  [ "$r" = "OK" ] && ok "140c: com a V034 reaplicada a anonimizacao passa" || bad "140c: a anonimizacao deveria passar (obtido: $r)"
  [ "$(q "SELECT count(*) FROM core.clinical_documents WHERE id = '$D2' AND content IS NULL AND anonymized_at IS NOT NULL")" = "1" ] \
    && ok "140c: com a V034 reaplicada o documento e zerado" || bad "140c: o documento deveria ser zerado"
fi

# --- 140d: com linhas de auditoria de documentos a U033 se RECUSA e nao altera nada ----------------------------------------------
revert_range 33 33 >/dev/null 2>&1 && bad "140d: a U033 deveria falhar com auditoria de documentos no log" || ok "140d: a U033 recusa reverter com linhas de auditoria de documentos (restaurar do pg_dump)"
[ "$(q "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_cdoc_audit' AND NOT tgisinternal")" = "1" ] \
  && [ "$(q "SELECT count(*) FROM pg_proc WHERE proname IN ('write_document_audit', 'record_document_download')")" = "2" ] \
  && ok "140d: a U033 recusada e atomica: o trigger e as funcoes continuam" || bad "140d: a U033 recusada deixou a reversao pela metade"

# --- 140e: a cadeia limpa a Fase 4 e preserva as demais; V031-V035 reaplicam (banco novo, sem auditoria) ----------------------------
fresh_db && apply_range 1 35 && ok "140e: banco novo com V001-V035 para a cadeia de reversao" || { bad "140e: nao foi possivel recriar o banco"; echo "migrations_phase4: FALHOU"; exit 1; }
revert_range 35 31 && ok "140e: a cadeia U035 -> U031 reverte" || bad "140e: a cadeia U035 -> U031 falhou"
left=$(q "SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'core' AND c.relname = 'clinical_documents')
              + (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'core' AND table_name = 'users' AND column_name IN ('cro_number', 'cro_state'))
              + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'core'
                  AND p.proname IN ('guard_user_cro', 'clinical_documents_before_insert', 'guard_clinical_document_update', 'clinical_documents_block',
                                    'write_document_audit', 'record_document_download'))
              + (SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_appt_completed_patient')")
[ "$left" = "0" ] && ok "140e: nenhum objeto da Fase 4 sobra apos a cadeia" || bad "140e: sobraram $left objetos da Fase 4"
def_t=$(q "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_audit_table'")
def_a=$(q "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_audit_action'")
echo "$def_t" | grep -q "budget_documents" && ! echo "$def_t" | grep -q "clinical_documents" && ! echo "$def_a" | grep -qE "VOID|DOWNLOAD" \
  && ok "140e: ck_audit_table volta a 9 tabelas e ck_audit_action a 4 acoes (Fases 0 a 3 intactas)" || bad "140e: constraints do audit_log nao restauradas"
[ "$(q "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'core' AND c.relname IN ('budgets', 'payments', 'budget_documents', 'appointments')")" = "4" ] \
  && ok "140e: as tabelas das Fases 2 e 3 permanecem" || bad "140e: tabelas das Fases 2 e 3 foram afetadas"
apply_range 31 35 && ok "140e: V031-V035 reaplicam depois da cadeia" || bad "140e: V031-V035 nao reaplicaram"
[ "$(q "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'core' AND c.relname = 'clinical_documents'")" = "1" ] \
  && ok "140e: clinical_documents volta apos a reaplicacao" || bad "140e: clinical_documents ausente apos reaplicar"

[ $fail -eq 0 ] && echo "migrations_phase4: OK" || { echo "migrations_phase4: FALHOU"; exit 1; }
