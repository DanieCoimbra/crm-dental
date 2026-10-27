-- =====================================================================
-- tests/audit_documents.sql | Fase 4 / F03-documents-audit-and-download (testes 132, 133 e 134)
--   132  toda emissão gera 1 linha INSERT e toda anulação 1 linha VOID em audit_log (só identificadores); sem app.user_id a gravação
--        inteira falha (28000); nenhuma linha UPDATE
--   133  core.record_document_download: grava DOWNLOAD (uma linha por chamada), recepção 42501, sem sessão 28000, documento de outra
--        clínica ou inexistente P0002; crm_app não grava audit_log diretamente
--   134  ck_audit_table/ck_audit_action aceitam os valores novos e recusam os de fora; o log continua só de inserção
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- Códigos  : 28000 sem app.user_id; 42501 papel / log só de inserção; P0002; 23514 constraint do log.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase4_seed.sql

\set d1 'f6000000-0000-4000-8000-000000000021'
\set d2 'f6000000-0000-4000-8000-000000000022'
\set dB 'f6000000-0000-4000-8000-000000000023'

SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('d1', pg_temp.cdoc_sql(:'d1', :'ca', :'pa', :'ua_d', 'CERTIFICATE'), 1);
SELECT pg_temp.expect_rows('d2', pg_temp.cdoc_sql(:'d2', :'ca', :'pa', :'ua_d', 'PRESCRIPTION'), 1);
:ctx_b_d \gset
SELECT pg_temp.expect_rows('dB (clinica B)', pg_temp.cdoc_sql(:'dB', :'cb', :'pb', :'ub_d', 'PRESCRIPTION'), 1);
:ctx_a_d \gset
SELECT pg_temp.expect_rows('anula d1', pg_temp.cdoc_void_sql(:'d1', :'ua_d'), 1);
RESET SESSION AUTHORIZATION;

SELECT pg_temp.expect_n('T132 INSERT de d1', pg_temp.dac(:'d1', 'INSERT'), 1);
SELECT pg_temp.expect_n('T132 INSERT de d2', pg_temp.dac(:'d2', 'INSERT'), 1);
SELECT pg_temp.expect_n('T132 VOID de d1', pg_temp.dac(:'d1', 'VOID'), 1);
SELECT pg_temp.expect_n('T132 d2 nao tem VOID', pg_temp.dac(:'d2', 'VOID'), 0);
SELECT pg_temp.expect_n('T132 nenhuma linha UPDATE para documentos',
  (SELECT count(*) FROM core.audit_log WHERE table_name = 'clinical_documents' AND action = 'UPDATE'), 0);
DO $$
BEGIN
  -- identificadores corretos: clínica, usuário que agiu, registro
  IF NOT EXISTS (SELECT 1 FROM core.audit_log WHERE table_name = 'clinical_documents' AND action = 'INSERT'
                   AND record_id = 'f6000000-0000-4000-8000-000000000021' AND clinic_id = 'f1000000-0000-4000-8000-00000000000a'
                   AND user_id = 'f1000000-0000-4000-8000-0000000000a1') THEN
    RAISE EXCEPTION 'T132: linha de auditoria sem clinica/usuario/registro corretos';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM core.audit_log WHERE table_name = 'clinical_documents' AND action = 'INSERT'
                   AND record_id = 'f6000000-0000-4000-8000-000000000023' AND clinic_id = 'f1000000-0000-4000-8000-00000000000b'
                   AND user_id = 'f1000000-0000-4000-8000-0000000000b1') THEN
    RAISE EXCEPTION 'T132: auditoria da clinica B incorreta';
  END IF;
END $$;

-- sem app.user_id (administrador sem a RLS): a emissão inteira falha e nada é gravado
:ctx_a_d_nouser \gset
SELECT pg_temp.expect_err('T132 emissao sem app.user_id', '28000', pg_temp.cdoc_sql(gen_random_uuid()::text, :'ca', :'pa', :'ua_d', 'PRESCRIPTION'));
:ctx_a_d \gset
SELECT pg_temp.expect_n('T132 a emissao sem usuario nao deixou documento', (SELECT count(*) FROM core.clinical_documents WHERE clinic_id = :'ca'), 2);

-- 133: download
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T133 download de d2', format('SELECT core.record_document_download(%L)', :'d2'), 1);
SELECT pg_temp.expect_rows('T133 segundo download de d2', format('SELECT core.record_document_download(%L)', :'d2'), 1);
SELECT pg_temp.expect_rows('T133 download do documento anulado (permitido)', format('SELECT core.record_document_download(%L)', :'d1'), 1);
SELECT pg_temp.expect_err('T133 documento inexistente', 'P0002', format('SELECT core.record_document_download(%L)', 'f6000000-0000-4000-8000-0000000000ff'));
SELECT pg_temp.expect_err('T133 documento de outra clinica', 'P0002', format('SELECT core.record_document_download(%L)', :'dB'));
:ctx_a_d_nouser \gset
SELECT pg_temp.expect_err('T133 sem usuario na sessao', '28000', format('SELECT core.record_document_download(%L)', :'d2'));
:ctx_none \gset
SELECT pg_temp.expect_err('T133 sem sessao (sem papel)', '42501', format('SELECT core.record_document_download(%L)', :'d2'));
:ctx_a_r \gset
SELECT pg_temp.expect_err('T133 recepcao', '42501', format('SELECT core.record_document_download(%L)', :'d2'));
:ctx_b_r \gset
SELECT pg_temp.expect_err('T133 recepcao de outra clinica', '42501', format('SELECT core.record_document_download(%L)', :'d2'));
:ctx_b_d \gset
SELECT pg_temp.expect_err('T133 dentista de B nao baixa o documento de A', 'P0002', format('SELECT core.record_document_download(%L)', :'d2'));
-- crm_app não grava o log diretamente
:ctx_a_d \gset
SELECT pg_temp.expect_err('T133 crm_app nao insere em audit_log', '42501',
  format('INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (%L, %L, ''clinical_documents'', %L, ''DOWNLOAD'')', :'ca', :'ua_d', :'d2'));
RESET SESSION AUTHORIZATION;

SELECT pg_temp.expect_n('T133 d2 tem 2 DOWNLOADs', pg_temp.dac(:'d2', 'DOWNLOAD'), 2);
SELECT pg_temp.expect_n('T133 d1 (anulado) tem 1 DOWNLOAD', pg_temp.dac(:'d1', 'DOWNLOAD'), 1);
SELECT pg_temp.expect_n('T133 dB nao tem DOWNLOAD', pg_temp.dac(:'dB', 'DOWNLOAD'), 0);
SELECT pg_temp.expect_n('T133 o total de DOWNLOADs e 3 (nada gravado pelas chamadas recusadas)',
  (SELECT count(*) FROM core.audit_log WHERE table_name = 'clinical_documents' AND action = 'DOWNLOAD'), 3);

-- 134: constraints do log e append-only
SELECT pg_temp.expect_err('T134 acao fora da lista', '23514',
  format('INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (%L, %L, ''clinical_documents'', %L, ''READ'')', :'ca', :'ua_d', :'d2'));
SELECT pg_temp.expect_err('T134 tabela fora da lista', '23514',
  format('INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (%L, %L, ''dashboards'', %L, ''INSERT'')', :'ca', :'ua_d', :'d2'));
SELECT pg_temp.expect_rows('T134 VOID e DOWNLOAD aceitos pelas constraints',
  format('INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (%L, %L, ''clinical_documents'', %L, ''VOID''), (%L, %L, ''clinical_documents'', %L, ''DOWNLOAD'')',
         :'ca', :'ua_d', :'d2', :'ca', :'ua_d', :'d2'), 2);
SELECT pg_temp.expect_err('T134 o log nao admite UPDATE', '42501', format('UPDATE core.audit_log SET action = ''INSERT'' WHERE record_id = %L', :'d2'));
SELECT pg_temp.expect_err('T134 o log nao admite DELETE', '42501', format('DELETE FROM core.audit_log WHERE record_id = %L', :'d2'));
SELECT pg_temp.expect_err('T134 o log nao admite TRUNCATE', '42501', 'TRUNCATE core.audit_log');

-- o dentista lê a trilha do documento (ix_audit_record); a recepção não
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T134 dentista le a trilha do documento',
  format('SELECT 1 FROM core.audit_log WHERE clinic_id = %L AND table_name = ''clinical_documents'' AND record_id = %L', :'ca', :'d2'), 5);
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T134 recepcao nao le a trilha', format('SELECT 1 FROM core.audit_log WHERE record_id = %L', :'d2'), 0);
RESET SESSION AUTHORIZATION;

ROLLBACK;
\o
SELECT 'audit_documents: OK' AS resultado;
