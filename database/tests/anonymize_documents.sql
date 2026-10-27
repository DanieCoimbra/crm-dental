-- =====================================================================
-- tests/anonymize_documents.sql | Fase 4 / F04-documents-anonymization (testes 135 e 136)
--   135  anonymize_patient zera content, rest_days, target_specialty e void_reason dos documentos do paciente e marca anonymized_at,
--        mantendo número, tipo, datas, emissor, sha256 e a anulação; version+1; uma linha ANONYMIZE por documento (além da de
--        patients); documentos de outro paciente e de outra clínica intactos; documento NÃO bloqueia a anonimização; depois, download
--        e nova emissão são recusados (CR003)
--   136  a exceção de imutabilidade não se forja (GUC sozinho); o crm_definer só alcança documentos da clínica da sessão
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : CR003; 42501; 23514.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase4_seed.sql

\set d1 'f6000000-0000-4000-8000-000000000031'
\set d2 'f6000000-0000-4000-8000-000000000032'
\set d3 'f6000000-0000-4000-8000-000000000033'
\set d4 'f6000000-0000-4000-8000-000000000034'
\set dB 'f6000000-0000-4000-8000-000000000035'

-- documentos: 3 do paciente pa (um anulado com motivo), 1 do paciente pa2 e 1 da clínica B
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('d1', pg_temp.cdoc_sql(:'d1', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
SELECT pg_temp.expect_rows('d2', pg_temp.cdoc_sql(:'d2', :'ca', :'pa', :'ua_d', 'PRESCRIPTION'), 1);
SELECT pg_temp.expect_rows('d3', pg_temp.cdoc_sql(:'d3', :'ca', :'pa', :'ua_d', 'REFERRAL'), 1);
SELECT pg_temp.expect_rows('d4 (outro paciente)', pg_temp.cdoc_sql(:'d4', :'ca', :'pa2', :'ua_d', 'CERTIFICATE'), 1);
SELECT pg_temp.expect_rows('anula d2 com motivo', pg_temp.cdoc_void_sql(:'d2', :'ua_d'), 1);
:ctx_b_d \gset
SELECT pg_temp.expect_rows('dB (clinica B)', pg_temp.cdoc_sql(:'dB', :'cb', :'pb', :'ub_d', 'CERTIFICATE'), 1);
RESET SESSION AUTHORIZATION;

CREATE TEMP TABLE before_docs AS
  SELECT id, number, document_type, issued_on, sha256, created_by, created_at, voided_at, voided_by, version FROM core.clinical_documents;
GRANT SELECT ON before_docs TO PUBLIC;

-- o paciente precisa estar inativo; sem prontuário não há retenção
:ctx_a_d \gset
UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = :'pa';

-- 135: a anonimização passa mesmo com documentos (anulado ou não)
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T135 anonimiza o paciente com documentos', format('SELECT core.anonymize_patient(%L)', :'pa'), 1);
RESET SESSION AUTHORIZATION;

DO $$
DECLARE r record;
BEGIN
  -- os 3 documentos do paciente: payload zerado, marca e linha preservada
  FOR r IN SELECT d.*, b.number AS b_number, b.document_type AS b_type, b.issued_on AS b_issued, b.sha256 AS b_sha, b.created_by AS b_by,
                  b.created_at AS b_created, b.voided_at AS b_voided, b.voided_by AS b_voided_by, b.version AS b_version
             FROM core.clinical_documents d JOIN before_docs b ON b.id = d.id
            WHERE d.id IN ('f6000000-0000-4000-8000-000000000031', 'f6000000-0000-4000-8000-000000000032', 'f6000000-0000-4000-8000-000000000033') LOOP
    IF r.content IS NOT NULL OR r.rest_days IS NOT NULL OR r.target_specialty IS NOT NULL OR r.void_reason IS NOT NULL THEN
      RAISE EXCEPTION 'T135: documento % ainda tem dado pessoal ou clinico', r.id;
    END IF;
    IF r.anonymized_at IS NULL THEN RAISE EXCEPTION 'T135: documento % sem anonymized_at', r.id; END IF;
    IF r.number <> r.b_number OR r.document_type <> r.b_type OR r.issued_on <> r.b_issued OR r.sha256 <> r.b_sha
       OR r.created_by <> r.b_by OR r.created_at <> r.b_created
       OR r.voided_at IS DISTINCT FROM r.b_voided OR r.voided_by IS DISTINCT FROM r.b_voided_by THEN
      RAISE EXCEPTION 'T135: documento % perdeu numero, tipo, datas, emissor, hash ou anulacao', r.id;
    END IF;
    IF r.version <> r.b_version + 1 THEN RAISE EXCEPTION 'T135: version de % deveria subir 1', r.id; END IF;
    IF r.patient_id <> 'f2000000-0000-4000-8000-0000000000a1' THEN RAISE EXCEPTION 'T135: paciente de % mudou', r.id; END IF;
  END LOOP;
  IF (SELECT count(*) FROM core.clinical_documents WHERE anonymized_at IS NOT NULL) <> 3 THEN
    RAISE EXCEPTION 'T135: exatamente 3 documentos deveriam estar anonimizados';
  END IF;
  -- o anulado continua anulado
  IF (SELECT voided_at FROM core.clinical_documents WHERE id = 'f6000000-0000-4000-8000-000000000032') IS NULL THEN
    RAISE EXCEPTION 'T135: a anulacao de d2 sumiu';
  END IF;
  -- outro paciente e outra clínica: intactos
  IF EXISTS (SELECT 1 FROM core.clinical_documents WHERE id IN ('f6000000-0000-4000-8000-000000000034', 'f6000000-0000-4000-8000-000000000035')
                AND (content IS NULL OR anonymized_at IS NOT NULL OR rest_days IS NULL)) THEN
    RAISE EXCEPTION 'T135: documento de outro paciente ou de outra clinica foi alterado';
  END IF;
END $$;

SELECT pg_temp.expect_n('T135 ANONYMIZE de d1', pg_temp.dac(:'d1', 'ANONYMIZE'), 1);
SELECT pg_temp.expect_n('T135 ANONYMIZE de d2 (anulado)', pg_temp.dac(:'d2', 'ANONYMIZE'), 1);
SELECT pg_temp.expect_n('T135 ANONYMIZE de d3', pg_temp.dac(:'d3', 'ANONYMIZE'), 1);
SELECT pg_temp.expect_n('T135 d4 e dB sem ANONYMIZE', pg_temp.dac(:'d4', 'ANONYMIZE') + pg_temp.dac(:'dB', 'ANONYMIZE'), 0);
SELECT pg_temp.expect_n('T135 mais a linha ANONYMIZE do paciente (patients)',
  (SELECT count(*) FROM core.audit_log WHERE table_name = 'patients' AND record_id = :'pa'::uuid AND action = 'ANONYMIZE'), 1);

-- depois da anonimização
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_err('T135 download de documento anonimizado', 'CR003', format('SELECT core.record_document_download(%L)', :'d1'));
SELECT pg_temp.expect_err('T135 nova emissao para o paciente anonimizado', 'CR003', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', 'PRESCRIPTION'));
SELECT pg_temp.expect_err('T135 anular documento anonimizado', '23514', pg_temp.cdoc_void_sql(:'d1', :'ua_d'));
SELECT pg_temp.expect_err('T135 anonimizar de novo', 'CR003', format('SELECT core.anonymize_patient(%L)', :'pa'));
SELECT pg_temp.expect_rows('T135 o dentista continua listando os documentos anonimizados (numero e tipo)',
  format('SELECT number, document_type FROM core.clinical_documents WHERE patient_id = %L', :'pa'), 3);
-- o documento do outro paciente continua baixável
SELECT pg_temp.expect_rows('T135 download do documento de outro paciente', format('SELECT core.record_document_download(%L)', :'d4'), 1);
RESET SESSION AUTHORIZATION;

-- 136: a exceção não se forja
SELECT set_config('core.anonymizing', 'on', true) \gset
SELECT pg_temp.expect_err('T136 GUC ligado por superusuario nao anonimiza d4', '23514',
  format('UPDATE core.clinical_documents SET content = NULL, rest_days = NULL, target_specialty = NULL, void_reason = NULL, anonymized_at = now() WHERE id = %L', :'d4'));
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT set_config('core.anonymizing', 'on', true) \gset
SELECT pg_temp.expect_err('T136 GUC ligado por crm_app nao anonimiza d4', '42501',
  format('UPDATE core.clinical_documents SET content = NULL WHERE id = %L', :'d4'));
RESET SESSION AUTHORIZATION;

-- o crm_definer só alcança a clínica da sessão (a flag liga a guarda, mas a RLS do definer filtra por tenant)
:ctx_a_d \gset
SELECT set_config('core.anonymizing', 'on', true) \gset
SET LOCAL ROLE crm_definer;
SELECT pg_temp.expect_rows('T136 definer nao enxerga documento de outra clinica', format('SELECT 1 FROM core.clinical_documents WHERE id = %L', :'dB'), 0);
SELECT pg_temp.expect_rows('T136 definer nao atualiza documento de outra clinica',
  format('UPDATE core.clinical_documents SET content = NULL, rest_days = NULL, target_specialty = NULL, void_reason = NULL, anonymized_at = now() WHERE id = %L', :'dB'), 0);
-- o definer só pode zerar o payload junto com anonymized_at (transição completa): meia transição é recusada
SELECT pg_temp.expect_err('T136 definer so zera o conteudo sem anonymized_at', '23514',
  format('UPDATE core.clinical_documents SET content = NULL WHERE id = %L', :'d4'));
SELECT pg_temp.expect_err('T136 definer nao altera o sha256 (sem grant)', '42501',
  format('UPDATE core.clinical_documents SET sha256 = repeat(''b'', 64) WHERE id = %L', :'d4'));
RESET ROLE;
SELECT set_config('core.anonymizing', 'off', true) \gset

-- sem a flag o definer também não consegue
:ctx_a_d \gset
SET LOCAL ROLE crm_definer;
SELECT pg_temp.expect_err('T136 definer sem a flag nao anonimiza d4', '23514',
  format('UPDATE core.clinical_documents SET content = NULL, rest_days = NULL, target_specialty = NULL, void_reason = NULL, anonymized_at = now() WHERE id = %L', :'d4'));
RESET ROLE;

ROLLBACK;
\o
SELECT 'anonymize_documents: OK' AS resultado;
