-- =====================================================================
-- tests/audit_appointments.sql | Fase 2 / F06-appointments-audit
-- Teste 76 da spec global da Fase 2: toda gravação em appointments gera exatamente 1 linha em audit_log (sem valores);
-- sem app.user_id a gravação inteira falha (28000); o log continua só de inserção e recusa nome de tabela fora da lista;
-- business_hours, clinic_holidays e holidays não geram linhas.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 28000 sem app.user_id; 23503 usuário de outra clínica; 23514 table_name fora da lista; 42501 log só de inserção.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase2_seed.sql

\set au1 'f2000000-0000-4000-8000-000000000501'
\set au2 'f2000000-0000-4000-8000-000000000502'

-- Baseline: as linhas de auditoria dos pacientes do seed já existem; só interessam as de appointments
CREATE FUNCTION pg_temp.audit_count(p_record text, p_action text) RETURNS bigint LANGUAGE sql AS $$
  SELECT count(*) FROM core.audit_log WHERE table_name = 'appointments' AND record_id = p_record::uuid AND action = p_action $$;

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- INSERT: 1 linha
SELECT pg_temp.expect_rows('T76 INSERT de agendamento', pg_temp.appt_sql(:'au1', :'ca', :'pa', :'ua_d', '2031-07-01 08:00+00', '2031-07-01 09:00+00', 'SCHEDULED'), 1);
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF pg_temp.audit_count('f2000000-0000-4000-8000-000000000501', 'INSERT') <> 1 THEN
    RAISE EXCEPTION 'T76: INSERT deve gerar exatamente 1 linha (obtido %)', pg_temp.audit_count('f2000000-0000-4000-8000-000000000501', 'INSERT');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM core.audit_log WHERE table_name = 'appointments' AND record_id = 'f2000000-0000-4000-8000-000000000501'
                    AND clinic_id = 'f1000000-0000-4000-8000-00000000000a' AND user_id = 'f1000000-0000-4000-8000-0000000000a1') THEN
    RAISE EXCEPTION 'T76: a linha de auditoria deve trazer a clinica e o usuario da sessao';
  END IF;
END $$;

-- UPDATE (cada alteração, inclusive cancelar e reativar): 1 linha por UPDATE
SET SESSION AUTHORIZATION crm_app;
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T76 recepcao confirma', pg_temp.set_status_sql(:'au1', 'CONFIRMED'), 1);
SELECT pg_temp.expect_rows('T76 recepcao cancela',  pg_temp.set_status_sql(:'au1', 'CANCELED'), 1);
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T76 dentista reativa',  pg_temp.set_status_sql(:'au1', 'SCHEDULED'), 1);
-- UPDATE sem mudança de valores também gera uma linha (aceito, como na Fase 1)
SELECT pg_temp.expect_rows('T76 UPDATE sem mudanca', format('UPDATE core.appointments SET notes = notes WHERE id = %L', :'au1'), 1);
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF pg_temp.audit_count('f2000000-0000-4000-8000-000000000501', 'UPDATE') <> 4 THEN
    RAISE EXCEPTION 'T76: 4 UPDATEs devem gerar 4 linhas (obtido %)', pg_temp.audit_count('f2000000-0000-4000-8000-000000000501', 'UPDATE');
  END IF;
  -- a recepção foi a autora de 2 deles
  IF (SELECT count(*) FROM core.audit_log WHERE table_name = 'appointments' AND record_id = 'f2000000-0000-4000-8000-000000000501'
         AND user_id = 'f1000000-0000-4000-8000-0000000000a2') <> 2 THEN
    RAISE EXCEPTION 'T76: a autoria da recepcao deve ser registrada';
  END IF;
END $$;

-- Sem app.user_id: a gravação inteira falha e NADA persiste
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d_nouser \gset
-- (com created_by = NULL a política recusa antes; por isso a prova de 28000 usa o administrador, que não passa pela RLS)
RESET SESSION AUTHORIZATION;
SELECT pg_temp.expect_err('T76 INSERT sem app.user_id', '28000',
  pg_temp.appt_sql(:'au2', :'ca', :'pa', :'ua_d', '2031-07-02 08:00+00', '2031-07-02 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T76 UPDATE sem app.user_id', '28000', pg_temp.set_status_sql(:'au1', 'CONFIRMED'));
:ctx_a_d \gset
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-000000000502') THEN
    RAISE EXCEPTION 'T76: o agendamento sem auditoria nao pode persistir';
  END IF;
  IF (SELECT status FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-000000000501') <> 'SCHEDULED' THEN
    RAISE EXCEPTION 'T76: o UPDATE sem auditoria nao pode persistir';
  END IF;
END $$;
-- usuário de outra clínica na sessão: a FK composta de audit_log recusa
:ctx_a_d_user_b \gset
SELECT pg_temp.expect_err('T76 app.user_id de outra clinica', '23503', pg_temp.set_status_sql(:'au1', 'CONFIRMED'));
:ctx_a_d \gset

-- O log: só identificadores, só de inserção, lista de tabelas ampliada e fechada
DO $$
BEGIN
  IF (SELECT array_agg(column_name::text ORDER BY column_name) FROM information_schema.columns
       WHERE table_schema = 'core' AND table_name = 'audit_log')
     IS DISTINCT FROM ARRAY['action', 'clinic_id', 'id', 'occurred_at', 'record_id', 'table_name', 'user_id'] THEN
    RAISE EXCEPTION 'T76: audit_log tem colunas fora do contrato (sem valores)';
  END IF;
END $$;
SELECT pg_temp.expect_err('T76 nome de tabela fora da lista', '23514', format(
  'INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (%L, %L, %L, gen_random_uuid(), %L)', :'ca', :'ua_d', 'business_hours', 'INSERT'));
SAVEPOINT s_audit;
SELECT pg_temp.expect_err('T76 audit_log continua so de insercao (UPDATE)', '42501', 'UPDATE core.audit_log SET action = ''INSERT''');
SELECT pg_temp.expect_err('T76 audit_log continua so de insercao (DELETE)', '42501', 'DELETE FROM core.audit_log');
ROLLBACK TO SAVEPOINT s_audit;

-- Configuração não gera auditoria: business_hours, clinic_holidays, holidays
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
INSERT INTO core.business_hours (clinic_id, weekday, is_open, opens_at, closes_at, created_by) VALUES (:'ca', 1, true, '08:00', '18:00', :'ua_d');
UPDATE core.business_hours SET closes_at = '17:00' WHERE clinic_id = :'ca' AND weekday = 1;
INSERT INTO core.clinic_holidays (clinic_id, holiday_date, name, created_by) VALUES (:'ca', '2031-06-24', 'Aniversario da cidade', :'ua_d');
UPDATE core.clinic_holidays SET name = 'Aniversario de Piracicaba' WHERE clinic_id = :'ca';
DELETE FROM core.clinic_holidays WHERE clinic_id = :'ca';
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.audit_log WHERE table_name IN ('business_hours', 'clinic_holidays', 'holidays')) THEN
    RAISE EXCEPTION 'T76: configuracao nao entra na auditoria';
  END IF;
  -- e nenhum trigger de auditoria nessas tabelas
  IF EXISTS (SELECT 1 FROM pg_trigger t WHERE NOT t.tgisinternal AND t.tgname LIKE '%audit%'
                AND t.tgrelid IN ('core.business_hours'::regclass, 'core.clinic_holidays'::regclass, 'core.holidays'::regclass)) THEN
    RAISE EXCEPTION 'T76: business_hours, clinic_holidays e holidays nao devem ter trigger de auditoria';
  END IF;
END $$;

-- Quem lê o log: só o dentista; a recepção vê 0 linhas
SET SESSION AUTHORIZATION crm_app;
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T76 recepcao nao le a auditoria', 'SELECT 1 FROM core.audit_log', 0);
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T76 dentista le o historico de um agendamento (indice ix_audit_record)', format(
  'SELECT 1 FROM core.audit_log WHERE clinic_id = %L AND table_name = %L AND record_id = %L', :'ca', 'appointments', :'au1'), 5);
RESET SESSION AUTHORIZATION;

ROLLBACK;
\o
SELECT 'audit_appointments: OK' AS resultado;
