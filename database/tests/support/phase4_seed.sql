-- =====================================================================
-- tests/support/phase4_seed.sql | Fase 4 (F01-F06)
-- Apoio dos testes da Fase 4. Incluir com `\ir support/phase4_seed.sql` DENTRO de uma transação (BEGIN) aberta pelo
-- teste, como administrador do banco de teste. Tudo é revertido no ROLLBACK.
--   * traz o seed da Fase 3 (clínicas A e B, dentista e recepção de cada uma, pacientes pa/pa2/pa_off/pa_anon/pb, macros ctx_*,
--     expect_err, expect_rows, procedimentos);
--   * CRO preenchido nos dois dentistas (ua_d: 12345/SP; ub_d: 67890/RJ), cada um pela própria sessão (a guarda da V031);
--   * pg_temp.cdoc_sql / cdoc_ins / cdoc_void_sql: construtores de SQL para passar a expect_err / expect_rows;
--   * pg_temp.expect_n: confere um número (linhas de auditoria, por exemplo).
-- =====================================================================
\ir phase3_seed.sql

:ctx_a_d \gset
UPDATE core.users SET cro_number = '12345', cro_state = 'SP' WHERE id = :'ua_d';
:ctx_b_d \gset
UPDATE core.users SET cro_number = '67890', cro_state = 'RJ' WHERE id = :'ub_d';
:ctx_a_d \gset

-- Base64 válido (>= 40 caracteres) e sha256 de 64 hex minúsculos
CREATE FUNCTION pg_temp.b64() RETURNS text LANGUAGE sql AS $$ SELECT repeat('QUJD', 20) $$;

-- INSERT de um documento: (id, clínica, paciente, autor, tipo). CERTIFICATE leva rest_days = 5; REFERRAL leva a especialidade.
CREATE FUNCTION pg_temp.cdoc_sql(p_id text, p_clinic text, p_patient text, p_user text, p_type text) RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.clinical_documents (id, clinic_id, patient_id, document_type, issued_on, rest_days, target_specialty, content, sha256, created_by) '
                'VALUES (%L, %L, %L, %L, DATE ''2031-07-01'', %s, %s, %L, repeat(''a'', 64), %L)',
                p_id, p_clinic, p_patient, p_type,
                CASE WHEN p_type = 'CERTIFICATE' THEN '5' ELSE 'NULL' END,
                CASE WHEN p_type = 'REFERRAL' THEN quote_literal('Endodontia') ELSE 'NULL' END,
                pg_temp.b64(), p_user) $$;

-- INSERT livre: colunas e valores em texto (para os casos inválidos). Sempre com sha256 e created_by válidos.
CREATE FUNCTION pg_temp.cdoc_raw_sql(p_clinic text, p_patient text, p_user text, p_type text, p_rest text, p_spec text, p_content text,
                                     p_sha text DEFAULT NULL) RETURNS text LANGUAGE sql AS $$
  SELECT format('INSERT INTO core.clinical_documents (clinic_id, patient_id, document_type, issued_on, rest_days, target_specialty, content, sha256, created_by) '
                'VALUES (%L, %L, %L, DATE ''2031-07-01'', %s, %s, %s, %L, %L)',
                p_clinic, p_patient, p_type, p_rest, p_spec, p_content, COALESCE(p_sha, repeat('a', 64)), p_user) $$;

-- Anulação: voided_by informado; version +1 (o JPA faz o mesmo)
CREATE FUNCTION pg_temp.cdoc_void_sql(p_id text, p_by text) RETURNS text LANGUAGE sql AS $$
  SELECT format('UPDATE core.clinical_documents SET voided_at = now(), voided_by = %L, void_reason = ''emitido por engano'', version = version + 1 WHERE id = %L',
                p_by, p_id) $$;

CREATE FUNCTION pg_temp.expect_n(p_label text, p_actual bigint, p_expected bigint) RETURNS void LANGUAGE plpgsql AS $$
BEGIN IF p_actual <> p_expected THEN RAISE EXCEPTION '%: esperado %, obtido %', p_label, p_expected, p_actual; END IF; END $$;

-- linhas de auditoria de um documento (lidas como administrador)
CREATE FUNCTION pg_temp.dac(p_record text, p_action text) RETURNS bigint LANGUAGE sql AS $$
  SELECT count(*) FROM core.audit_log WHERE table_name = 'clinical_documents' AND record_id = p_record::uuid AND action = p_action $$;
