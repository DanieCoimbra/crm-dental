-- =====================================================================
-- tests/anonymize_appointments.sql | Fase 2 / F07-appointment-anonymization
-- Testes 77 e 78 da spec global da Fase 2: a anonimização de paciente (V013, ajustada na V021) recusa com CR004 quando há
-- agendamento futuro em aberto, zera appointments.notes e mantém horário, status e paciente; a retenção de 20 anos ignora
-- agendamentos; o crm_definer só tem SELECT e UPDATE (notes, version) em appointments, escopados ao tenant.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR004 agendamento futuro em aberto; CR002 retenção não vencida (tem precedência); os da Fase 1 seguem valendo.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase2_seed.sql

\set q_sched 'f2000000-0000-4000-8000-0000000006a1'
\set q_conf  'f2000000-0000-4000-8000-0000000006a2'
\set q_prog  'f2000000-0000-4000-8000-0000000006a3'
\set q_canc  'f2000000-0000-4000-8000-0000000006a4'
\set q_past  'f2000000-0000-4000-8000-0000000006a5'
\set q_none  'f2000000-0000-4000-8000-0000000006a6'
\set q_ret   'f2000000-0000-4000-8000-0000000006a7'
\set q_big   'f2000000-0000-4000-8000-0000000006a8'
\set q_b     'f2000000-0000-4000-8000-0000000006b1'

CREATE FUNCTION pg_temp.anon_sql(p_patient text) RETURNS text LANGUAGE sql AS $$
  SELECT 'SELECT core.anonymize_patient(' || quote_literal(p_patient) || ')' $$;

-- ---------------------------------------------------------------------
-- Fixtures como administrador (clínica A): pacientes criados ativos (o CR005 impede agendar para inativo) e desativados
-- depois de criados os agendamentos; vários tipos de agendamento
-- ---------------------------------------------------------------------
:ctx_a_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, active, deactivated_at, created_by) VALUES
  (:'q_sched', :'ca', 'Futuro Agendado',  '1980-01-01', '11999990011', true, NULL, :'ua_d'),
  (:'q_conf',  :'ca', 'Futuro Confirmado','1980-01-02', '11999990012', true, NULL, :'ua_d'),
  (:'q_prog',  :'ca', 'Em Atendimento',   '1980-01-03', '11999990013', true, NULL, :'ua_d'),
  (:'q_canc',  :'ca', 'Futuro Cancelado', '1980-01-04', '11999990014', true, NULL, :'ua_d'),
  (:'q_past',  :'ca', 'Somente Passado',  '1980-01-05', '11999990015', true, NULL, :'ua_d'),
  (:'q_none',  :'ca', 'Sem Prontuario',   '1980-01-06', '11999990016', true, NULL, :'ua_d'),
  (:'q_ret',   :'ca', 'Retencao Vigente', '1980-01-07', '11999990017', true, NULL, :'ua_d'),
  (:'q_big',   :'ca', 'Muitos Agendamentos', '1980-01-08', '11999990018', true, NULL, :'ua_d');
:ctx_b_d \gset
INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, active, deactivated_at, created_by)
  VALUES (:'q_b', :'cb', 'Paciente de B', '1980-02-01', '11999990019', true, NULL, :'ub_d');
:ctx_a_d \gset

-- futuros em aberto (um por paciente), com notes de texto livre
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, notes, created_by) VALUES
  ('f2000000-0000-4000-8000-0000000007a1', :'ca', :'q_sched', date_trunc('minute', now()) + interval '10 days', date_trunc('minute', now()) + interval '10 days 30 minutes', 'SCHEDULED',   'queixa de dor', :'ua_d'),
  ('f2000000-0000-4000-8000-0000000007a2', :'ca', :'q_conf',  date_trunc('minute', now()) + interval '11 days', date_trunc('minute', now()) + interval '11 days 30 minutes', 'CONFIRMED',   'ligar antes', :'ua_d'),
  -- em atendimento agora: começou há 10 minutos e termina daqui a 20
  ('f2000000-0000-4000-8000-0000000007a3', :'ca', :'q_prog',  date_trunc('minute', now()) - interval '10 minutes', date_trunc('minute', now()) + interval '20 minutes', 'IN_PROGRESS', 'canal', :'ua_d'),
  -- cancelado e faltoso no futuro NÃO bloqueiam
  ('f2000000-0000-4000-8000-0000000007a4', :'ca', :'q_canc',  date_trunc('minute', now()) + interval '12 days', date_trunc('minute', now()) + interval '12 days 30 minutes', 'CANCELED',  'desistiu', :'ua_d'),
  ('f2000000-0000-4000-8000-0000000007a5', :'ca', :'q_canc',  date_trunc('minute', now()) + interval '13 days', date_trunc('minute', now()) + interval '13 days 30 minutes', 'NO_SHOW',   NULL, :'ua_d'),
  -- só passado: um ainda SCHEDULED (o backend deveria ter fechado a agenda; não bloqueia), um COMPLETED, um sem notes
  ('f2000000-0000-4000-8000-0000000007a6', :'ca', :'q_past',  date_trunc('minute', now()) - interval '3 days', date_trunc('minute', now()) - interval '3 days' + interval '30 minutes', 'SCHEDULED', 'Joao da Silva, pediu retorno', :'ua_d'),
  ('f2000000-0000-4000-8000-0000000007a7', :'ca', :'q_past',  date_trunc('minute', now()) - interval '2 days', date_trunc('minute', now()) - interval '2 days' + interval '30 minutes', 'COMPLETED', 'limpeza', :'ua_d'),
  ('f2000000-0000-4000-8000-0000000007a8', :'ca', :'q_past',  date_trunc('minute', now()) - interval '1 days', date_trunc('minute', now()) - interval '1 days' + interval '30 minutes', 'COMPLETED', NULL, :'ua_d'),
  -- sem registro clínico, só agendamentos passados RECENTES: a retenção de 20 anos ignora agendamentos
  ('f2000000-0000-4000-8000-0000000007a9', :'ca', :'q_none',  date_trunc('minute', now()) - interval '1 hour', date_trunc('minute', now()) - interval '30 minutes', 'COMPLETED', 'alergia a penicilina', :'ua_d'),
  -- retenção vigente (nota recente) E agendamento futuro em aberto: o CR002 vem antes do CR004
  ('f2000000-0000-4000-8000-0000000007b1', :'ca', :'q_ret',   date_trunc('minute', now()) + interval '14 days', date_trunc('minute', now()) + interval '14 days 30 minutes', 'SCHEDULED', NULL, :'ua_d');
INSERT INTO core.clinical_notes (clinic_id, patient_id, content, created_by) VALUES (:'ca', :'q_ret', repeat('QUJD', 12), :'ua_d');
-- um agendamento de outra clínica com notes (a anonimização de A nunca o alcança)
:ctx_b_d \gset
INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, notes, created_by)
  VALUES ('f2000000-0000-4000-8000-0000000007c1', :'cb', :'q_b', '2020-01-01 10:00+00', '2020-01-01 10:30+00', 'COMPLETED', 'nota de B', :'ub_d');
-- 500 agendamentos passados com notes para um só paciente
:ctx_a_d \gset
INSERT INTO core.appointments (clinic_id, patient_id, starts_at, ends_at, status, notes, created_by)
  SELECT :'ca', :'q_big', timestamptz '2020-01-01 00:00+00' + g * interval '1 hour', timestamptz '2020-01-01 00:30+00' + g * interval '1 hour', 'COMPLETED', 'obs ' || g, :'ua_d'
    FROM generate_series(1, 500) g;

UPDATE core.patients SET active = false, deactivated_at = now() WHERE clinic_id = :'ca' AND id IN (:'q_sched', :'q_conf', :'q_prog', :'q_canc', :'q_past', :'q_none', :'q_ret', :'q_big');
:ctx_b_d \gset
UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = :'q_b';
:ctx_a_d \gset

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- T77: CR004
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T77 futuro SCHEDULED', 'CR004', pg_temp.anon_sql(:'q_sched'));
SELECT pg_temp.expect_err('T77 futuro CONFIRMED', 'CR004', pg_temp.anon_sql(:'q_conf'));
SELECT pg_temp.expect_err('T77 IN_PROGRESS que ainda nao terminou', 'CR004', pg_temp.anon_sql(:'q_prog'));
-- CR002 (retenção) tem precedência sobre CR004 (agenda)
SELECT pg_temp.expect_err('T77 retencao vigente vem antes do CR004', 'CR002', pg_temp.anon_sql(:'q_ret'));
-- as regras da Fase 1 seguem: recepção não anonimiza; paciente de outra clínica não existe
:ctx_a_r \gset
SELECT pg_temp.expect_err('T77 recepcao nao anonimiza', '42501', pg_temp.anon_sql(:'q_past'));
:ctx_b_d \gset
SELECT pg_temp.expect_err('T77 dentista de B nao alcanca paciente de A', 'P0002', pg_temp.anon_sql(:'q_past'));
:ctx_a_d \gset

-- a tentativa recusada não alterou nada (notes e version intactos)
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF (SELECT notes FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a1') <> 'queixa de dor'
     OR (SELECT version FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a1') <> 0
     OR EXISTS (SELECT 1 FROM core.patients WHERE anonymized_at IS NOT NULL AND id IN
                ('f2000000-0000-4000-8000-0000000006a1', 'f2000000-0000-4000-8000-0000000006a2', 'f2000000-0000-4000-8000-0000000006a3')) THEN
    RAISE EXCEPTION 'T77: uma anonimizacao recusada alterou dados';
  END IF;
END $$;
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset

-- ---------------------------------------------------------------------
-- T77: passa com futuros só CANCELED/NO_SHOW e com agendamentos só passados
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_rows('T77 futuros so CANCELED/NO_SHOW anonimiza', pg_temp.anon_sql(:'q_canc'), 1);
SELECT pg_temp.expect_rows('T77 so passado anonimiza', pg_temp.anon_sql(:'q_past'), 1);
-- T78: sem registro clínico, inativo, só agendamentos passados recentes: anonimiza na hora
SELECT pg_temp.expect_rows('T78 sem prontuario anonimiza na hora (a retencao ignora agendamentos)', pg_temp.anon_sql(:'q_none'), 1);
RESET SESSION AUTHORIZATION;

DO $$
DECLARE
  r record;
BEGIN
  -- notes zeradas; horário, status e paciente permanecem; version +1 apenas onde havia notes
  FOR r IN SELECT * FROM core.appointments WHERE patient_id IN
             ('f2000000-0000-4000-8000-0000000006a4', 'f2000000-0000-4000-8000-0000000006a5', 'f2000000-0000-4000-8000-0000000006a6') LOOP
    IF r.notes IS NOT NULL THEN RAISE EXCEPTION 'T77: notes deveriam estar NULL (agendamento %)', r.id; END IF;
  END LOOP;
  IF (SELECT version FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a6') <> 1
     OR (SELECT version FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a8') <> 0 THEN
    RAISE EXCEPTION 'T77: version sobe 1 so nos agendamentos que tinham notes';
  END IF;
  IF (SELECT status FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a6') <> 'SCHEDULED'
     OR (SELECT status FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a7') <> 'COMPLETED'
     OR (SELECT status FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a4') <> 'CANCELED'
     OR (SELECT status FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a5') <> 'NO_SHOW' THEN
    RAISE EXCEPTION 'T77: o status dos agendamentos deve permanecer';
  END IF;
  IF (SELECT count(*) FROM core.appointments WHERE patient_id = 'f2000000-0000-4000-8000-0000000006a5') <> 3
     OR (SELECT count(*) FROM core.appointments WHERE patient_id = 'f2000000-0000-4000-8000-0000000006a4') <> 2
     OR EXISTS (SELECT 1 FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a6'
                  AND (starts_at <> date_trunc('minute', now()) - interval '3 days')) THEN
    RAISE EXCEPTION 'T77: os agendamentos (e seus horarios) devem ser mantidos';
  END IF;
  -- outra clínica e outros pacientes intactos
  IF (SELECT notes FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007c1') <> 'nota de B'
     OR (SELECT notes FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007a1') <> 'queixa de dor'
     OR (SELECT notes FROM core.appointments WHERE id = 'f2000000-0000-4000-8000-0000000007b1') IS NOT NULL THEN
    RAISE EXCEPTION 'T77: so os agendamentos do paciente anonimizado podem ser limpos';
  END IF;
  -- auditoria: um UPDATE por agendamento limpo (2 de q_past; 1 de q_none), mais o ANONYMIZE de cada paciente
  IF (SELECT count(*) FROM core.audit_log WHERE table_name = 'appointments' AND action = 'UPDATE'
        AND record_id IN ('f2000000-0000-4000-8000-0000000007a6', 'f2000000-0000-4000-8000-0000000007a7')) <> 2
     OR (SELECT count(*) FROM core.audit_log WHERE table_name = 'appointments' AND action = 'UPDATE'
           AND record_id = 'f2000000-0000-4000-8000-0000000007a8') <> 0
     OR (SELECT count(*) FROM core.audit_log WHERE table_name = 'appointments' AND action = 'UPDATE'
           AND record_id = 'f2000000-0000-4000-8000-0000000007a9') <> 1 THEN
    RAISE EXCEPTION 'T77: a limpeza de notes deve gerar uma linha de auditoria por agendamento limpo';
  END IF;
  IF (SELECT count(*) FROM core.audit_log WHERE action = 'ANONYMIZE' AND table_name = 'patients'
        AND record_id IN ('f2000000-0000-4000-8000-0000000006a4', 'f2000000-0000-4000-8000-0000000006a5', 'f2000000-0000-4000-8000-0000000006a6')) <> 3 THEN
    RAISE EXCEPTION 'T77: faltou a linha ANONYMIZE';
  END IF;
  -- T78: o paciente sem prontuário ficou anonimizado e o paciente com retenção vigente não
  IF (SELECT anonymized_at FROM core.patients WHERE id = 'f2000000-0000-4000-8000-0000000006a6') IS NULL
     OR (SELECT anonymized_at FROM core.patients WHERE id = 'f2000000-0000-4000-8000-0000000006a7') IS NOT NULL THEN
    RAISE EXCEPTION 'T78: estado de anonimizacao incorreto';
  END IF;
END $$;

-- depois de anonimizado: nenhum agendamento novo (CR003), nem reabrir um cancelado
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T77 agendamento novo para paciente anonimizado', 'CR003',
  pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'q_past', :'ua_d', '2031-09-01 08:00+00', '2031-09-01 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T77 reativar cancelado de paciente anonimizado', 'CR003', pg_temp.set_status_sql('f2000000-0000-4000-8000-0000000007a4', 'SCHEDULED'));

-- ---------------------------------------------------------------------
-- T55/T77: 500 agendamentos do mesmo paciente dentro do statement_timeout de 15 s
-- ---------------------------------------------------------------------
DO $$
DECLARE
  t0 timestamptz := clock_timestamp();
BEGIN
  PERFORM core.anonymize_patient('f2000000-0000-4000-8000-0000000006a8');
  IF clock_timestamp() - t0 > interval '15 seconds' THEN
    RAISE EXCEPTION 'T77: anonimizar 500 agendamentos levou % (limite 15 s)', clock_timestamp() - t0;
  END IF;
END $$;
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM core.appointments WHERE patient_id = 'f2000000-0000-4000-8000-0000000006a8' AND notes IS NOT NULL)
     OR (SELECT count(*) FROM core.appointments WHERE patient_id = 'f2000000-0000-4000-8000-0000000006a8') <> 500 THEN
    RAISE EXCEPTION 'T77: os 500 agendamentos devem ficar sem notes e continuar existindo';
  END IF;
  IF (SELECT count(*) FROM core.audit_log WHERE table_name = 'appointments' AND action = 'UPDATE'
        AND record_id IN (SELECT id FROM core.appointments WHERE patient_id = 'f2000000-0000-4000-8000-0000000006a8')) <> 500 THEN
    RAISE EXCEPTION 'T77: cada um dos 500 agendamentos limpos gera uma linha de auditoria';
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- T79 (parte): o crm_definer só tem SELECT e UPDATE (notes, version), escopados ao tenant
-- ---------------------------------------------------------------------
SET SESSION AUTHORIZATION crm_definer;
:ctx_a_d \gset
-- restam com notes só os 3 agendamentos futuros/em atendimento dos pacientes que a anonimização recusou
SELECT pg_temp.expect_rows('T79 definer le os agendamentos do tenant', 'SELECT 1 FROM core.appointments WHERE notes IS NOT NULL', 3);
SELECT pg_temp.expect_err('T79 definer nao altera status', '42501', format('UPDATE core.appointments SET status = %L WHERE id = %L', 'CANCELED', 'f2000000-0000-4000-8000-0000000007a1'));
SELECT pg_temp.expect_err('T79 definer nao altera horario', '42501', format('UPDATE core.appointments SET starts_at = starts_at WHERE id = %L', 'f2000000-0000-4000-8000-0000000007a1'));
SELECT pg_temp.expect_err('T79 definer nao insere', '42501', pg_temp.appt_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', '2031-09-02 08:00+00', '2031-09-02 09:00+00', 'SCHEDULED'));
SELECT pg_temp.expect_err('T79 definer nao apaga', '42501', 'DELETE FROM core.appointments');
:ctx_b_d \gset
SELECT pg_temp.expect_rows('T79 definer sob B nao alcanca as linhas de A', format('UPDATE core.appointments SET notes = NULL WHERE id = %L', 'f2000000-0000-4000-8000-0000000007a1'), 0);
SELECT pg_temp.expect_rows('T79 definer sob B ve so a clinica B', 'SELECT 1 FROM core.appointments', 1);
:ctx_none \gset
SELECT pg_temp.expect_rows('T79 definer sem tenant nao ve nada', 'SELECT 1 FROM core.appointments', 0);
RESET SESSION AUTHORIZATION;

ROLLBACK;
\o
SELECT 'anonymize_appointments: OK' AS resultado;
