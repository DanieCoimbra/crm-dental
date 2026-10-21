-- =====================================================================
-- tests/patients_constraints.sql | Fase 1 / F02-patients
-- Testes 29, 30, 31 (patients), 34, 35 (patients), 36 (patients), 37, 38, 39, 42 (patients) e 52 (patients)
-- da spec global da Fase 1: isolamento por clínica, papel, autoria, CPF, formatos, coerências,
-- imutáveis, updated_at, inativação e ausência de DELETE.
-- Executar : como administrador, depois das migrations. Tudo dentro de uma transação revertida.
-- =====================================================================
\set ON_ERROR_STOP on
\o /dev/null
BEGIN;
\ir support/phase1_seed.sql

\set pa1 'f1000000-0000-4000-8000-0000000000c1'
\set pa2 'f1000000-0000-4000-8000-0000000000c2'
\set pb1 'f1000000-0000-4000-8000-0000000000d1'

-- INSERT válido de paciente da clínica A, com colunas e valores extras (para variar um campo por teste)
CREATE FUNCTION pg_temp.patient_sql(p_cols text, p_vals text) RETURNS text LANGUAGE sql AS $$
  SELECT 'INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by'
         || CASE WHEN p_cols <> '' THEN ', ' || p_cols ELSE '' END
         || ') VALUES (gen_random_uuid(), ''f1000000-0000-4000-8000-00000000000a'', ''Fulano Silva'', ''1990-01-01'', '
         || '''11999990003'', ''f1000000-0000-4000-8000-0000000000a1''' || CASE WHEN p_vals <> '' THEN ', ' || p_vals ELSE '' END || ')'
$$;

-- Fixtures (administrador; a auditoria exige o contexto)
:ctx_a_d \gset
INSERT INTO core.patients (id, clinic_id, name, cpf, birth_date, phone, created_by) VALUES
  (:'pa1', :'ca', 'Ana Souza',  '11111111111', '1990-05-10', '11999990001', :'ua_d'),
  (:'pa2', :'ca', 'Bruno Lima', NULL,          '1985-01-01', '11999990002', :'ua_d');
:ctx_b_d \gset
-- mesmo CPF em outra clínica: permitido (teste 37)
INSERT INTO core.patients (id, clinic_id, name, cpf, birth_date, phone, created_by) VALUES
  (:'pb1', :'cb', 'Carla Dias', '11111111111', '1992-02-02', '11999990004', :'ub_d');

SET SESSION AUTHORIZATION crm_app;

-- ---------------------------------------------------------------------
-- T29: isolamento por clínica (dentista de A)
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_rows('T29 A le so a clinica A', 'SELECT 1 FROM core.patients', 2);
SELECT pg_temp.expect_rows('T29 A nao le B', format('SELECT 1 FROM core.patients WHERE id = %L', :'pb1'), 0);
SELECT pg_temp.expect_rows('T29 UPDATE em B afeta 0', format('UPDATE core.patients SET name = ''Invasor Teste'' WHERE id = %L', :'pb1'), 0);
SELECT pg_temp.expect_err('T29 DELETE negado', '42501', format('DELETE FROM core.patients WHERE id = %L', :'pb1'));
SELECT pg_temp.expect_err('T29 INSERT com clinic_id de B falha por WITH CHECK', '42501',
  format('INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (gen_random_uuid(), %L, ''Fulano Silva'', ''1990-01-01'', ''11999990005'', %L)', :'cb', :'ua_d'));

-- T30: sem nenhuma variável de sessão
:ctx_none \gset
SELECT pg_temp.expect_rows('T30 sem tenant: 0 linhas', 'SELECT 1 FROM core.patients', 0);
SELECT pg_temp.expect_err('T30 sem tenant: INSERT falha', '42501', pg_temp.patient_sql('', ''));

-- T31: FK composta impede created_by de outra clínica (a política passa, pois created_by = app.user_id)
:ctx_a_d_user_b \gset
SELECT pg_temp.expect_err('T31 created_by de outra clinica', '23503', format(
  'INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (gen_random_uuid(), %L, ''Fulano Silva'', ''1990-01-01'', ''11999990008'', %L)', :'ca', :'ub_d'));

-- ---------------------------------------------------------------------
-- T35: papel ausente ou inválido nega tudo, inclusive patients
-- ---------------------------------------------------------------------
:ctx_a_norole \gset
SELECT pg_temp.expect_rows('T35 sem app.role: 0 linhas', 'SELECT 1 FROM core.patients', 0);
SELECT pg_temp.expect_err('T35 sem app.role: INSERT falha', '42501', pg_temp.patient_sql('', ''));
:ctx_a_d \gset
SELECT set_config('app.role', 'ADMIN', true) AS _ \gset
SELECT pg_temp.expect_rows('T35 app.role=ADMIN: 0 linhas', 'SELECT 1 FROM core.patients', 0);
SELECT pg_temp.expect_rows('T35 app.role=ADMIN: UPDATE afeta 0', 'UPDATE core.patients SET profession = ''x''', 0);

-- ---------------------------------------------------------------------
-- T34: a recepção lê, insere e altera pacientes
-- ---------------------------------------------------------------------
:ctx_a_r \gset
SELECT pg_temp.expect_rows('T34 recepcao le', 'SELECT 1 FROM core.patients', 2);
SELECT pg_temp.expect_rows('T34 recepcao insere', format(
  'INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (gen_random_uuid(), %L, ''Daniel Rocha'', ''2000-03-03'', ''11999990006'', %L)', :'ca', :'ua_r'), 1);
SELECT pg_temp.expect_rows('T34 recepcao altera', format('UPDATE core.patients SET profession = ''Professora'' WHERE id = %L', :'pa1'), 1);

-- ---------------------------------------------------------------------
-- T36: autoria falsa (created_by diferente de app.user_id)
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_err('T36 created_by falso', '42501', format(
  'INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (gen_random_uuid(), %L, ''Fulano Silva'', ''1990-01-01'', ''11999990007'', %L)', :'ca', :'ua_r'));

-- ---------------------------------------------------------------------
-- T37: CPF único por clínica; NULL repetido permitido
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T37 CPF duplicado na clinica', '23505', pg_temp.patient_sql('cpf', '''11111111111'''));
SELECT pg_temp.expect_rows('T37 CPF NULL repetido', pg_temp.patient_sql('', ''), 1);
SELECT pg_temp.expect_rows('T37 CPF NULL repetido (2)', pg_temp.patient_sql('', ''), 1);
SELECT pg_temp.expect_rows('T37 CPF novo', pg_temp.patient_sql('cpf', '''22222222222'''), 1);

-- ---------------------------------------------------------------------
-- T38: formatos e domínios
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T38 cpf letras', '23514', pg_temp.patient_sql('cpf', '''abc12345678'''));
SELECT pg_temp.expect_err('T38 cpf 10 digitos', '23514', pg_temp.patient_sql('cpf', '''1234567890'''));
SELECT pg_temp.expect_err('T38 cpf com pontuacao (11 caracteres)', '23514', pg_temp.patient_sql('cpf', '''111.111-111'''));
SELECT pg_temp.expect_err('T38 telefone com mascara', '23514', format(
  'INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (gen_random_uuid(), %L, ''Fulano Silva'', ''1990-01-01'', ''(11) 99999-0003'', %L)', :'ca', :'ua_d'));
SELECT pg_temp.expect_err('T38 e-mail em maiusculas', '23514', pg_temp.patient_sql('email', '''Fulano@Exemplo.COM'''));
SELECT pg_temp.expect_rows('T38 e-mail em minusculas', pg_temp.patient_sql('email', '''fulano@exemplo.com'''), 1);
SELECT pg_temp.expect_err('T38 sexo invalido', '23514', pg_temp.patient_sql('sex', '''X'''));
SELECT pg_temp.expect_err('T38 nascimento antes de 1900', '23514', format(
  'INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (gen_random_uuid(), %L, ''Fulano Silva'', ''1899-12-31'', ''11999990003'', %L)', :'ca', :'ua_d'));
SELECT pg_temp.expect_err('T38 nome so com espacos', '23514', format(
  'INSERT INTO core.patients (id, clinic_id, name, birth_date, phone, created_by) VALUES (gen_random_uuid(), %L, ''   a  '', ''1990-01-01'', ''11999990003'', %L)', :'ca', :'ua_d'));
SELECT pg_temp.expect_err('T38 sem telefone', '23502', format(
  'INSERT INTO core.patients (id, clinic_id, name, birth_date, created_by) VALUES (gen_random_uuid(), %L, ''Fulano Silva'', ''1990-01-01'', %L)', :'ca', :'ua_d'));
SELECT pg_temp.expect_err('T38 sem nascimento', '23502', format(
  'INSERT INTO core.patients (id, clinic_id, name, phone, created_by) VALUES (gen_random_uuid(), %L, ''Fulano Silva'', ''11999990003'', %L)', :'ca', :'ua_d'));

-- ---------------------------------------------------------------------
-- T39: coerências (convênio, conhecido, inativação)
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T39 numero do convenio sem nome', '23514', pg_temp.patient_sql('insurance_number', '''123456'''));
SELECT pg_temp.expect_rows('T39 convenio completo', pg_temp.patient_sql('insurance_name, insurance_number', '''Odonto X'', ''123456'''), 1);
SELECT pg_temp.expect_err('T39 conhecido so com nome', '23514', pg_temp.patient_sql('acquaintance_name', '''Maria'''));
SELECT pg_temp.expect_err('T39 conhecido so com telefone', '23514', pg_temp.patient_sql('acquaintance_phone', '''11988887777'''));
SELECT pg_temp.expect_err('T39 telefone do conhecido invalido', '23514', pg_temp.patient_sql('acquaintance_name, acquaintance_phone', '''Maria'', ''123'''));
SELECT pg_temp.expect_rows('T39 conhecido completo', pg_temp.patient_sql('acquaintance_name, acquaintance_phone', '''Maria'', ''11988887777'''), 1);
SELECT pg_temp.expect_err('T39 inativo sem deactivated_at', '23514', pg_temp.patient_sql('active', 'false'));
SELECT pg_temp.expect_err('T39 ativo com deactivated_at', '23514', pg_temp.patient_sql('deactivated_at', 'now()'));
SELECT pg_temp.expect_rows('T39 inativo coerente', pg_temp.patient_sql('active, deactivated_at', 'false, now()'), 1);

-- inativação lógica pelo crm_app e reativação
SELECT pg_temp.expect_rows('T39 inativar', format('UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = %L', :'pa2'), 1);
SELECT pg_temp.expect_err('T39 inativar sem deactivated_at', '23514', format('UPDATE core.patients SET active = false WHERE id = %L', :'pa1'));
SELECT pg_temp.expect_rows('T39 reativar', format('UPDATE core.patients SET active = true, deactivated_at = NULL WHERE id = %L', :'pa2'), 1);

-- ---------------------------------------------------------------------
-- T52 (patients): sem DELETE/TRUNCATE; grants por coluna protegem anonymized_at e as imutáveis
-- ---------------------------------------------------------------------
SELECT pg_temp.expect_err('T52 DELETE', '42501', 'DELETE FROM core.patients');
SELECT pg_temp.expect_err('T52 TRUNCATE', '42501', 'TRUNCATE core.patients');
SELECT pg_temp.expect_err('T52 anonymized_at no UPDATE', '42501', format('UPDATE core.patients SET anonymized_at = now() WHERE id = %L', :'pa1'));
SELECT pg_temp.expect_err('T52 anonymized_at no INSERT', '42501', pg_temp.patient_sql('anonymized_at', 'now()'));
SELECT pg_temp.expect_err('T52 created_by no UPDATE', '42501', format('UPDATE core.patients SET created_by = %L WHERE id = %L', :'ua_r', :'pa1'));
SELECT pg_temp.expect_err('T52 clinic_id no UPDATE', '42501', format('UPDATE core.patients SET clinic_id = %L WHERE id = %L', :'cb', :'pa1'));

RESET SESSION AUTHORIZATION;

-- ---------------------------------------------------------------------
-- T42 (patients): imutáveis (trigger) e updated_at; administrador isola o trigger do grant por coluna
-- ---------------------------------------------------------------------
:ctx_a_d \gset
SELECT pg_temp.expect_err('T42 id imutavel', '23514', format('UPDATE core.patients SET id = gen_random_uuid() WHERE id = %L', :'pa1'));
SELECT pg_temp.expect_err('T42 clinic_id imutavel', '23514', format('UPDATE core.patients SET clinic_id = %L WHERE id = %L', :'cb', :'pa1'));
SELECT pg_temp.expect_err('T42 created_by imutavel', '23514', format('UPDATE core.patients SET created_by = %L WHERE id = %L', :'ua_r', :'pa1'));
SELECT pg_temp.expect_err('T42 created_at imutavel', '23514', format('UPDATE core.patients SET created_at = now() - interval ''1 day'' WHERE id = %L', :'pa1'));

-- A coluna anonymized_at só muda com a flag da função (guard da F06) e respeita o CHECK
SELECT pg_temp.expect_err('T42 anonymized_at sem a flag da funcao', '42501', format('UPDATE core.patients SET anonymized_at = now() WHERE id = %L', :'pa1'));
SELECT set_config('core.anonymizing', 'on', true) AS _ \gset
SELECT pg_temp.expect_err('T39 anonymized_at com paciente ativo viola o CHECK', '23514', format('UPDATE core.patients SET anonymized_at = now() WHERE id = %L', :'pa1'));
SELECT set_config('core.anonymizing', 'off', true) AS _ \gset

-- updated_at: recua o valor (trigger desligado só aqui) e confere que um UPDATE o refaz
ALTER TABLE core.patients DISABLE TRIGGER trg_patients_set_updated_at;
UPDATE core.patients SET updated_at = '2000-01-01T00:00:00Z' WHERE id = :'pa1';
ALTER TABLE core.patients ENABLE TRIGGER trg_patients_set_updated_at;
SET SESSION AUTHORIZATION crm_app;
:ctx_a_d \gset
UPDATE core.patients SET name = 'Ana Souza Lima' WHERE id = :'pa1';
RESET SESSION AUTHORIZATION;
DO $$
BEGIN
  IF (SELECT updated_at FROM core.patients WHERE id = 'f1000000-0000-4000-8000-0000000000c1') <= '2000-01-01T00:00:00Z' THEN
    RAISE EXCEPTION 'T42: updated_at nao foi atualizado pelo trigger';
  END IF;
END $$;

-- ON DELETE RESTRICT: paciente referenciado não pode ser apagado nem pelo administrador
SELECT pg_temp.expect_err('T42 clinica com paciente nao pode ser apagada', '23503', format('DELETE FROM core.clinics WHERE id = %L', :'cb'));

ROLLBACK;
\o
SELECT 'patients_constraints: OK' AS resultado;
