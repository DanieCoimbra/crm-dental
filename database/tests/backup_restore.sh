#!/usr/bin/env bash
# F06-backup-and-restore: testes 26 e 28 com o pg_dump, o age e o pg_restore REAIS, mais as falhas que o contrato exige.
#   26  backup como crm_backup (--enable-row-security) traz as linhas das clínicas A e B; tabela sem política de leitura reprova
#   28  o backup restaura em PostgreSQL limpo (após o bootstrap) com as mesmas contagens (modo full) e como recuperação de
#       desastre (modo data: bootstrap -> migrations -> pg_restore --data-only -> login funciona)
#   mais: arquivo ilegível sem a chave privada; falha sem chave, com chave inválida, sem TLS, com senha curta/embutida, backup
#   menor que o mínimo; restauração falha com chave errada, contagem divergente, dump truncado e catálogo alterado.
# Requer: psql/pg_dump/pg_restore/age no PATH, PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE (admin do banco DE ORIGEM),
#         CRM_BACKUP_PW, DB_DIR e um servidor DESCARTÁVEL e SEPARADO para a restauração: RESTORE_PGHOST/PORT/USER/PASSWORD.
set -uo pipefail

DB_DIR="${DB_DIR:?DB_DIR nao definido}"
: "${CRM_BACKUP_PW:?CRM_BACKUP_PW nao definido}"
: "${RESTORE_PGHOST:?RESTORE_PGHOST nao definido (servidor descartavel e separado)}"
SRC_URL="postgresql://crm_backup@${PGHOST}:${PGPORT:-5432}/${PGDATABASE:-postgres}"

fail=0
ok()  { echo "  ok   - $1"; }
bad() { echo "  FAIL - $1"; fail=1; }
admin() { psql -X -At -v ON_ERROR_STOP=1 "$@"; }
id1() { admin "$@" | head -1; }   # RETURNING id imprime também a etiqueta "INSERT 0 1"

work="$(mktemp -d)"
cleanup() {
  # Fase 1: audit_log é só de inserção (inclusive TRUNCATE, por trigger) e as FKs são RESTRICT; esta limpeza desliga o
  # trigger de TRUNCATE só aqui e esvazia as tabelas clínicas e da agenda (Fase 2), que só este teste popula com dados commitados
  admin -c "ALTER TABLE core.audit_log DISABLE TRIGGER trg_audit_log_no_truncate; ALTER TABLE core.payments DISABLE TRIGGER trg_pay_no_truncate; TRUNCATE core.audit_log, core.payments, core.installments, core.budget_documents, core.budget_items, core.budgets, core.appointments, core.procedure_types, core.business_hours, core.clinic_holidays, core.patient_files, core.clinical_notes, core.medical_histories, core.patients; ALTER TABLE core.audit_log ENABLE TRIGGER trg_audit_log_no_truncate; ALTER TABLE core.payments ENABLE TRIGGER trg_pay_no_truncate" >/dev/null 2>&1
  admin -c "DELETE FROM core.refresh_tokens WHERE clinic_id IN (SELECT id FROM core.clinics WHERE name LIKE 'bkp-test-%')" >/dev/null 2>&1
  admin -c "DELETE FROM core.users WHERE clinic_id IN (SELECT id FROM core.clinics WHERE name LIKE 'bkp-test-%')" >/dev/null 2>&1
  admin -c "DELETE FROM core.clinics WHERE name LIKE 'bkp-test-%'" >/dev/null 2>&1
  admin -c "DROP TABLE IF EXISTS core.t_sem_politica_f06" >/dev/null 2>&1
  rm -rf "$work"
}
trap cleanup EXIT

echo "backup_restore:"
for tool in age age-keygen pg_dump pg_restore; do
  command -v "$tool" >/dev/null 2>&1 || { echo "  FAIL - ferramenta ausente: $tool"; exit 1; }
done

# --- Chaves age de teste e dados nas duas clínicas ----------------------------------------------------------------------
age-keygen -o "$work/key.txt" >/dev/null 2>&1
PUB="$(age-keygen -y "$work/key.txt")"
PRIV="$(grep '^AGE-SECRET-KEY-' "$work/key.txt")"
age-keygen -o "$work/other.txt" >/dev/null 2>&1
OTHER_PRIV="$(grep '^AGE-SECRET-KEY-' "$work/other.txt")"

A=$(id1 -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'bkp-test-A') RETURNING id")
B=$(id1 -c "INSERT INTO core.clinics (id, name) VALUES (gen_random_uuid(), 'bkp-test-B') RETURNING id")
UA=$(id1 -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$A', 'Dentista A', 'bkp-test-a-dentista@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id")
UB=$(id1 -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role) VALUES (gen_random_uuid(), '$B', 'Dentista B', 'bkp-test-b-dentista@teste.local', 'x', 'DENTIST_ADMIN') RETURNING id")
admin -c "INSERT INTO core.users (id, clinic_id, name, email, password_hash, role, created_by) VALUES (gen_random_uuid(), '$B', 'Recepcao B', 'bkp-test-b-recepcao@teste.local', 'x', 'RECEPTIONIST', '$UB')" >/dev/null
admin -c "INSERT INTO core.refresh_tokens (id, clinic_id, user_id, family_id, token_hash, expires_at) VALUES (gen_random_uuid(), '$A', '$UA', gen_random_uuid(), repeat('a', 64), now() + interval '7 days')" >/dev/null
admin -c "INSERT INTO core.refresh_tokens (id, clinic_id, user_id, family_id, token_hash, expires_at) VALUES (gen_random_uuid(), '$B', '$UB', gen_random_uuid(), repeat('b', 64), now() + interval '7 days')" >/dev/null

# Fase 1 e 2: dados clínicos e de agenda nas DUAS clínicas, como o backend os gravaria (a auditoria exige app.user_id)
PA=$(id1 -c "SELECT gen_random_uuid()"); PB=$(id1 -c "SELECT gen_random_uuid()")
seed_clinical() { # $1 clínica  $2 usuário  $3 paciente  $4 nome  $5 CPF  $6 sufixo do exame
  admin -c "SELECT set_config('app.clinic_id', '$1', false), set_config('app.role', 'DENTIST_ADMIN', false), set_config('app.user_id', '$2', false);
            INSERT INTO core.patients (id, clinic_id, name, cpf, birth_date, phone, created_by) VALUES ('$3', '$1', '$4', '$5', '1990-01-01', '11999990000', '$2');
            INSERT INTO core.medical_histories (clinic_id, patient_id, content, created_by) VALUES ('$1', '$3', repeat('QUJD', 12), '$2');
            INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by) VALUES ('$1', '$3', repeat('QUJE', 12), '$2');
            INSERT INTO core.patient_files (clinic_id, patient_id, kind, storage_key, original_filename, content_type, size_bytes, created_by)
              VALUES ('$1', '$3', 'RADIOGRAPH', '$1/$3/' || gen_random_uuid(), 'bkp-exame-$6.png', 'image/png', 1000, '$2');
            INSERT INTO core.appointments (clinic_id, patient_id, starts_at, ends_at, status, notes, created_by)
              VALUES ('$1', '$3', timestamptz '2031-05-05 10:00+00', timestamptz '2031-05-05 10:30+00', 'SCHEDULED', 'bkp-consulta-$6', '$2');
            INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, created_by)
              VALUES ('$1', 1, true, '08:00', '18:00', '$2');
            INSERT INTO core.clinic_holidays (clinic_id, holiday_date, name, created_by)
              VALUES ('$1', DATE '2031-05-06', 'bkp-feriado-$6', '$2');
            -- Fase 3: procedimento, orçamento com item, PDF, parcela e pagamento (o orçamento passa por SENT e APPROVED)
            DO \$\$
            DECLARE v_pt uuid := gen_random_uuid(); v_b uuid := gen_random_uuid(); v_i uuid := gen_random_uuid();
            BEGIN
              INSERT INTO core.procedure_types (id, clinic_id, name, base_price, default_duration_minutes, color, created_by)
                VALUES (v_pt, '$1', 'bkp-proc-$6', 150.00, 30, '#3366FF', '$2');
              INSERT INTO core.budgets (id, clinic_id, patient_id, notes, created_by)
                VALUES (v_b, '$1', '$3', 'bkp-orcamento-$6', '$2');
              INSERT INTO core.budget_items (clinic_id, budget_id, procedure_type_id, quantity, position)
                VALUES ('$1', v_b, v_pt, 2, 1);
              UPDATE core.budgets SET status = 'SENT' WHERE id = v_b;
              INSERT INTO core.budget_documents (clinic_id, budget_id, patient_id, storage_key, content_type, size_bytes, sha256, created_by)
                VALUES ('$1', v_b, '$3', '$1/$3/budgets/' || gen_random_uuid(), 'application/pdf', 2000, repeat('a', 64), '$2');
              UPDATE core.budgets SET status = 'APPROVED' WHERE id = v_b;
              INSERT INTO core.installments (id, clinic_id, budget_id, patient_id, number, amount, due_date, created_by)
                VALUES (v_i, '$1', v_b, '$3', 1, 300.00, DATE '2031-06-10', '$2');
              INSERT INTO core.payments (clinic_id, installment_id, kind, amount, method, paid_on, created_by)
                VALUES ('$1', v_i, 'PAYMENT', 100.00, 'PIX', DATE '2031-06-11', '$2');
            END \$\$" >/dev/null
}
seed_clinical "$A" "$UA" "$PA" "bkp-paciente-a" 10000000001 A
seed_clinical "$B" "$UB" "$PB" "bkp-paciente-b" 20000000002 B

backup() { # variáveis extras vêm do ambiente da chamada
  BACKUP_URL="${BACKUP_URL:-$SRC_URL}" PGPASSWORD="${BKP_PW:-$CRM_BACKUP_PW}" BACKUP_AGE_PUBLIC_KEY="${KEY-$PUB}" \
    BACKUP_REQUIRE_TLS="${TLS:-0}" OUT_DIR="${OUT:-$work/out}" BACKUP_MIN_BYTES="${MIN:-10240}" bash "$DB_DIR/scripts/backup.sh"
}
restore() { # $1 = arquivo; chave privada em PRIVATE
  BACKUP_FILE="$1" BACKUP_AGE_PRIVATE_KEY="${PRIVATE:-$PRIV}" RESTORE_PGPASSWORD="${RESTORE_PGPASSWORD:-$PGPASSWORD}" \
    RESTORE_PGUSER="${RESTORE_PGUSER:-postgres}" bash "$DB_DIR/scripts/restore_check.sh"
}
empty_dir() { rm -rf "$work/out"; mkdir -p "$work/out"; }

# --- Teste 26: backup real ---------------------------------------------------------------------------------------------------
empty_dir
out=$(backup 2>&1); rc=$?
file=$(ls "$work/out"/crm-core-*.dump.age 2>/dev/null | head -1)
[ $rc -eq 0 ] && [ -n "$file" ] && ok "26: backup como crm_backup gerou o arquivo" || { bad "26: backup falhou (rc=$rc): $(echo "$out" | tail -3)"; exit 1; }
echo "$(basename "$file")" | grep -Eq '^crm-core-[0-9]{8}T[0-9]{6}\.dump\.age$' && ok "26: nome crm-core-<UTC yyyymmddThhmmss>.dump.age" || bad "26: nome fora do padrao: $(basename "$file")"
[ "$(ls "$work/out" | wc -l)" = "1" ] && ok "26: so o arquivo criptografado foi gravado (nenhum dump em claro)" || bad "26: sobrou arquivo em claro: $(ls "$work/out")"

cp "$file" "$work/good.dump.age"; GOOD="$work/good.dump.age"

# o conteúdo: linhas das DUAS clínicas dentro do dump (RLS por política, sem BYPASSRLS)
age -d -i "$work/key.txt" -o "$work/package.tar" "$file" 2>/dev/null && mkdir -p "$work/pkg" && tar -C "$work/pkg" -xf "$work/package.tar"
data=$(pg_restore --data-only -f - "$work/pkg/core.dump" 2>/dev/null)
echo "$data" | grep -q 'bkp-test-a-dentista@teste.local' && echo "$data" | grep -q 'bkp-test-b-dentista@teste.local' \
  && echo "$data" | grep -q 'bkp-test-b-recepcao@teste.local' \
  && ok "26: o dump traz as linhas das clinicas A e B (pg_dump --enable-row-security)" || bad "26: faltam linhas de alguma clinica no dump"
grep -q '^users|' "$work/pkg/counts.txt" && grep -q '^clinics|' "$work/pkg/counts.txt" \
  && ok "26: counts.txt dentro do pacote" || bad "26: counts.txt invalido"
# Fase 1: as 5 tabelas novas, com linhas das DUAS clínicas, entram no dump (inclusive a sequência da identidade do audit_log)
echo "$data" | grep -q 'bkp-paciente-a' && echo "$data" | grep -q 'bkp-paciente-b' \
  && echo "$data" | grep -q 'bkp-exame-A.png' && echo "$data" | grep -q 'bkp-exame-B.png' \
  && echo "$data" | grep -q 'QUJDQUJDQUJD' && echo "$data" | grep -q 'QUJEQUJEQUJE' \
  && ok "26: o dump traz pacientes, prontuario cifrado e exames das clinicas A e B" || bad "26: faltam linhas clinicas no dump"
n_clin=$(grep -Ec '^(patients|medical_histories|clinical_notes|patient_files)[|]2[|]2$' "$work/pkg/counts.txt")
n_aud=$(grep -Ec '^audit_log[|]22[|]22$' "$work/pkg/counts.txt")   # 8 da Fase 1 + 2 agendamentos (V020) + 12 da Fase 3 (V028; a V030 tirou os 2 UPDATEs de recalculo de total por item)
[ "$n_clin" = "4" ] && [ "$n_aud" = "1" ] && ok "26: counts.txt traz as 5 tabelas da Fase 1 (2 linhas por tabela clinica; 22 de auditoria)" \
  || bad "26: contagens da Fase 1 divergem em counts.txt: $(grep -E '^(patients|medical_histories|clinical_notes|patient_files|audit_log)[|]' "$work/pkg/counts.txt" | tr '\n' ' ')"
echo "$data" | grep -q 'audit_log_id_seq' && ok "26: a sequencia da identidade do audit_log entra no dump" || bad "26: sequencia do audit_log ausente do dump"
# Fase 2: as 4 tabelas novas (e as duas colunas novas) entram no dump com as linhas das DUAS clínicas
echo "$data" | grep -q 'bkp-consulta-A' && echo "$data" | grep -q 'bkp-consulta-B'   && echo "$data" | grep -q 'bkp-feriado-A' && echo "$data" | grep -q 'bkp-feriado-B'   && ok "26: o dump traz agendamentos e feriados das clinicas A e B" || bad "26: faltam agendamentos ou feriados no dump"
n_ag=$(grep -Ec '^(appointments|business_hours|clinic_holidays)[|]2[|]2$' "$work/pkg/counts.txt")
n_hol=$(grep -Ec '^holidays[|]50[|]50$' "$work/pkg/counts.txt")
[ "$n_ag" = "3" ] && [ "$n_hol" = "1" ] && ok "26: counts.txt traz as 4 tabelas da Fase 2 (2 linhas por tabela de clinica; 50 feriados nacionais)"   || bad "26: contagens da Fase 2 divergem em counts.txt: $(grep -E '^(appointments|business_hours|clinic_holidays|holidays)[|]' "$work/pkg/counts.txt" | tr '
' ' ')"
echo "$data" | grep -Eq 'COPY core\.clinics .*timezone' && echo "$data" | grep -Eq 'COPY core\.clinical_notes .*appointment_id'   && ok "26: clinics.timezone e clinical_notes.appointment_id entram no dump" || bad "26: colunas novas ausentes do dump"
# Fase 3: as 6 tabelas novas entram no dump com as linhas das DUAS clínicas (teste 119)
echo "$data" | grep -q 'bkp-proc-A' && echo "$data" | grep -q 'bkp-proc-B' && echo "$data" | grep -q 'bkp-orcamento-A' && echo "$data" | grep -q 'bkp-orcamento-B' \
  && ok "26: o dump traz procedimentos e orcamentos das clinicas A e B (Fase 3)" || bad "26: faltam linhas da Fase 3 no dump"
n_f3=$(grep -Ec '^(procedure_types|budgets|budget_items|budget_documents|installments|payments)[|]2[|]2$' "$work/pkg/counts.txt")
[ "$n_f3" = "6" ] && ok "26: counts.txt traz as 6 tabelas da Fase 3 (2 linhas por tabela)" \
  || bad "26: contagens da Fase 3 divergem em counts.txt: $(grep -E '^(procedure_types|budgets|budget_items|budget_documents|installments|payments)[|]' "$work/pkg/counts.txt" | tr '\n' ' ')"
pg_restore -l "$work/pkg/core.dump" | grep -q 'POLICY' && ok "26: o dump traz as politicas RLS" || bad "26: dump sem politicas RLS"
! pg_restore -l "$work/pkg/core.dump" | grep -Eq 'ACL|OWNER TO' && ok "26: o dump nao traz GRANTs nem donos (--no-acl --no-owner)" || bad "26: dump contem ACL/OWNER"

# --- Criptografia -------------------------------------------------------------------------------------------------------------
head -c 22 "$file" | grep -q 'age-encryption.org/v1' && ok "criptografia: formato age" || bad "criptografia: cabecalho age ausente"
! grep -aq 'PGDMP' "$file" && ! grep -aq 'bkp-test-' "$file" && ! grep -aq 'teste.local' "$file" \
  && ok "criptografia: nenhum dado em texto claro no artefato" || bad "criptografia: ha texto claro no artefato"
age -d -i "$work/other.txt" -o "$work/x.tar" "$file" >/dev/null 2>&1 \
  && bad "criptografia: outra chave conseguiu abrir o artefato" || ok "criptografia: ilegivel sem a chave privada correta"

# --- Falhas do backup (contrato: backup vazio ou sem chave faz o workflow falhar) ---------------------------------------------
export SRC_URL PUB CRM_BACKUP_PW DB_DIR work   # o subshell de `bash -c` precisa enxergá-los; sem isso a falha seria por outro motivo
check_fails() { # descrição; padrão esperado na mensagem de erro; resto = comando
  local desc="$1" pattern="$2"; shift 2
  empty_dir
  if out=$("$@" 2>&1); then bad "$desc: deveria falhar"; else
    if ! echo "$out" | grep -Eqi "$pattern"; then bad "$desc: falhou por outro motivo: $(echo "$out" | tail -2)"
    elif [ "$(ls "$work/out" | wc -l)" = "0" ]; then ok "$desc (falha pelo motivo certo e nenhum arquivo gravado)"
    else bad "$desc: falhou mas deixou arquivo"; fi
  fi
}
check_fails "sem chave age" "BACKUP_AGE_PUBLIC_KEY" env KEY="" bash -c "$(declare -f backup); backup"
check_fails "chave age invalida" "BACKUP_AGE_PUBLIC_KEY" env KEY="age1curta" bash -c "$(declare -f backup); backup"
check_fails "sem TLS (padrao exige sslmode)" "ssl|tls" env TLS=1 bash -c "$(declare -f backup); backup"
check_fails "senha curta" "PGPASSWORD|senha" env BKP_PW="curta" bash -c "$(declare -f backup); backup"
check_fails "URI com senha embutida" "senha|password" env BACKUP_URL="postgresql://crm_backup:segredo@${PGHOST}:${PGPORT:-5432}/${PGDATABASE:-postgres}" bash -c "$(declare -f backup); backup"
check_fails "backup menor que o minimo" "menor que o m" env MIN=999999999 bash -c "$(declare -f backup); backup"
check_fails "senha errada do crm_backup" "authentication|autentica|password" env BKP_PW="senhaErradaComMaisDeVinteEQuatroCaracteres" bash -c "$(declare -f backup); backup"

# tabela de negócio SEM política de leitura do crm_backup: o dump sairia incompleto, sem erro -> o backup recusa
admin -c "CREATE TABLE core.t_sem_politica_f06 (id uuid PRIMARY KEY, clinic_id uuid NOT NULL)" >/dev/null
admin -c "ALTER TABLE core.t_sem_politica_f06 ENABLE ROW LEVEL SECURITY" -c "ALTER TABLE core.t_sem_politica_f06 FORCE ROW LEVEL SECURITY" >/dev/null
admin -c "GRANT SELECT ON core.t_sem_politica_f06 TO crm_backup" >/dev/null
admin -c "INSERT INTO core.t_sem_politica_f06 VALUES (gen_random_uuid(), '$A')" >/dev/null
empty_dir
out=$(backup 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -q 't_sem_politica_f06'; then ok "26: tabela nova sem politica do crm_backup faz o backup falhar, nomeando a tabela"
else bad "26: backup aceitou tabela sem politica de leitura (rc=$rc)"; fi
admin -c "DROP TABLE core.t_sem_politica_f06" >/dev/null

# --- Teste 28: restauração -------------------------------------------------------------------------------------------------------
out=$(restore "$GOOD" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "28: restauracao verificada nos dois modos (full e data)" || { bad "28: restore_check falhou (rc=$rc)"; echo "$out" | head -40 | sed "s/^/      /"; }
echo "$out" | grep -q 'modo full: OK' && echo "$out" | grep -q 'modo data: OK' && ok "28: modos full e data aprovados" || bad "28: algum modo nao foi aprovado"
echo "$out" | grep -Eq 'ok   - core.users: [0-9]+ linhas' && ok "28: contagens por tabela conferidas" || bad "28: contagens nao aparecem na saida"
echo "$out" | grep -q 'catálogo idêntico' && ok "28: catalogo (constraints, indices, politicas, RLS, gatilhos, funcoes) identico ao do backup" || bad "28: comparacao de catalogo ausente"
echo "$out" | grep -q 'função de login encontra um usuário restaurado' && ok "28: no modo data o banco recuperado funciona para a aplicacao (crm_app + SECURITY DEFINER)" || bad "28: teste funcional do login ausente"
! echo "$out" | grep -q 'bkp-test-' && ok "28: a saida da restauracao nao imprime dados" || bad "28: a saida imprimiu dados do backup"

# --- Falhas da restauração -------------------------------------------------------------------------------------------------------
PRIVATE="$OTHER_PRIV" restore "$GOOD" >/dev/null 2>&1 && bad "28: restaurou com a chave errada" || ok "28: chave privada errada reprova a restauracao"

repack() { # $1 = nome do novo arquivo; função de edição em $2 aplicada a $work/edit
  rm -rf "$work/edit"; mkdir "$work/edit"; tar -C "$work/edit" -xf "$work/package.tar"
  "$2"; tar -C "$work/edit" -cf "$work/edit.tar" core.dump counts.txt schema.txt
  age -r "$PUB" -o "$work/$1" "$work/edit.tar"
}
edit_counts() { sed -i 's/^users|.*/users|999|999/' "$work/edit/counts.txt"; }
repack counts.age edit_counts
out=$(restore "$work/counts.age" 2>&1) && bad "28: contagem divergente nao reprovou" \
  || { echo "$out" | grep -q 'esperado entre 999 e 999' && ok "28: contagem divergente reprova a verificacao mensal" || bad "28: reprovou mas sem apontar a contagem"; }

edit_dump() { head -c $(( $(wc -c < "$work/edit/core.dump") / 2 )) "$work/edit/core.dump" > "$work/edit/half" && mv "$work/edit/half" "$work/edit/core.dump"; }
repack truncated.age edit_dump
restore "$work/truncated.age" >/dev/null 2>&1 && bad "28: dump truncado foi aceito" || ok "28: dump truncado reprova a restauracao"

edit_schema() { grep -v '^policy|users|users_sel|' "$work/edit/schema.txt" > "$work/edit/s" && mv "$work/edit/s" "$work/edit/schema.txt"; }
repack schema.age edit_schema
out=$(restore "$work/schema.age" 2>&1) && bad "28: catalogo divergente nao reprovou" \
  || { echo "$out" | grep -q 'catálogo restaurado difere' && ok "28: politica/constraint ausente ou a mais reprova a restauracao" || bad "28: reprovou mas sem apontar o catalogo"; }

printf 'isto nao e um backup' > "$work/lixo.age"
restore "$work/lixo.age" >/dev/null 2>&1 && bad "28: arquivo qualquer foi aceito" || ok "28: arquivo que nao e backup age reprova"

[ $fail -eq 0 ] && echo "backup_restore: OK" || { echo "backup_restore: FALHOU"; exit 1; }
