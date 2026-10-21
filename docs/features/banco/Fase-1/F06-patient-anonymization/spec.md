# Spec — F06 Retenção e Anonimização de Paciente (Fase 1)

Complementa `docs/global/banco/Fase-1/spec.md` (seções 1.2, 2.6, 3 e 4.2).

## 1. Visão técnica
- **Feature:** `core.anonymize_patient`, guards e políticas do `crm_definer`.
- **Stack:** PL/pgSQL, `SECURITY DEFINER`, migration (`V013`).
- **Abordagem:** função de um papel sem login e sem `BYPASSRLS`, que opera pelas políticas escopadas ao tenant; estado terminal imposto por triggers.

## 2. Objetos (`V013__core_patient_anonymization.sql`, como `crm_owner`, transação única)

### 2.1 Grants e políticas do `crm_definer`
```sql
GRANT CREATE ON SCHEMA core TO crm_definer;   -- temporário, para ALTER FUNCTION ... OWNER TO

GRANT SELECT, UPDATE ON core.patients, core.patient_files TO crm_definer;
GRANT SELECT, DELETE ON core.medical_histories, core.clinical_notes TO crm_definer;

CREATE POLICY patients_definer_sel ON core.patients FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY patients_definer_upd ON core.patients FOR UPDATE TO crm_definer
  USING (clinic_id = core.current_clinic_id()) WITH CHECK (clinic_id = core.current_clinic_id());
-- idem patient_files (SELECT, UPDATE); medical_histories e clinical_notes (SELECT, DELETE)
```

### 2.2 Guards
```sql
CREATE FUNCTION core.guard_anonymized_patient() RETURNS trigger LANGUAGE plpgsql
  SET search_path = pg_catalog AS $$
BEGIN
  IF OLD.anonymized_at IS NOT NULL THEN
    RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
  END IF;
  IF NEW.anonymized_at IS NOT NULL
     AND COALESCE(current_setting('core.anonymizing', true), '') <> 'on' THEN
    RAISE EXCEPTION 'anonimização só pela função' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_patients_guard_anonymized BEFORE UPDATE ON core.patients
  FOR EACH ROW EXECUTE FUNCTION core.guard_anonymized_patient();
```
```sql
CREATE FUNCTION core.forbid_child_of_anonymized() RETURNS trigger LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp AS $$
DECLARE v_anonymized timestamptz;
BEGIN
  SELECT p.anonymized_at INTO v_anonymized FROM core.patients p
   WHERE p.id = NEW.patient_id AND p.clinic_id = NEW.clinic_id FOR KEY SHARE;   -- espera uma anonimizacao em curso
  IF v_anonymized IS NOT NULL THEN
    RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
  END IF;
  RETURN NEW;
END $$;
-- BEFORE INSERT em medical_histories, clinical_notes, patient_files
```
O `FOR KEY SHARE` fecha a corrida com a anonimização: sem ele, um `INSERT` concorrente passaria pelo trigger (que via o paciente ainda não anonimizado), esperaria o `FOR UPDATE` da função só na checagem da FK e comitaria um registro DEPOIS do prontuário apagado. Coberto por teste de catálogo (T50); a corrida real exige duas sessões com dados commitados.
A ordem dos triggers `BEFORE UPDATE` é alfabética pelo nome: `trg_patients_forbid_immutable` < `trg_patients_guard_anonymized` < `trg_patients_set_updated_at`. O guard roda, portanto, depois dos imutáveis e antes do `updated_at`; a ordem não altera o resultado, e o teste 50 a confere.

### 2.3 Função
```sql
CREATE FUNCTION core.anonymize_patient(p_patient_id uuid) RETURNS timestamptz
  LANGUAGE plpgsql VOLATILE SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_patient core.patients%ROWTYPE;
  v_last    timestamptz;
  v_now     timestamptz := pg_catalog.now();
BEGIN
  IF core.current_app_role() IS DISTINCT FROM 'DENTIST_ADMIN' THEN
    RAISE EXCEPTION 'apenas o dentista anonimiza' USING ERRCODE = '42501';
  END IF;
  IF core.current_clinic_id() IS NULL OR core.current_user_id() IS NULL THEN
    RAISE EXCEPTION 'sessão sem clínica/usuário' USING ERRCODE = '28000';
  END IF;

  SELECT * INTO v_patient FROM core.patients WHERE id = p_patient_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'paciente inexistente' USING ERRCODE = 'P0002'; END IF;
  IF v_patient.anonymized_at IS NOT NULL THEN RAISE EXCEPTION 'já anonimizado' USING ERRCODE = 'CR003'; END IF;
  IF v_patient.active THEN RAISE EXCEPTION 'inative o paciente antes' USING ERRCODE = 'CR001'; END IF;

  SELECT GREATEST(
           (SELECT max(updated_at) FROM core.medical_histories WHERE patient_id = p_patient_id),
           (SELECT max(GREATEST(updated_at, noted_at)) FROM core.clinical_notes WHERE patient_id = p_patient_id),
           (SELECT max(updated_at) FROM core.patient_files WHERE patient_id = p_patient_id))
    INTO v_last;
  IF v_last IS NOT NULL AND v_now < v_last + core.clinical_retention_period() THEN
    RAISE EXCEPTION 'retenção não vencida' USING ERRCODE = 'CR002',
      DETAIL = 'liberado em ' || (v_last + core.clinical_retention_period())::date;
  END IF;

  PERFORM set_config('core.anonymizing', 'on', true);
  DELETE FROM core.medical_histories WHERE patient_id = p_patient_id;
  DELETE FROM core.clinical_notes    WHERE patient_id = p_patient_id;
  UPDATE core.patient_files
     SET deleted_at = COALESCE(deleted_at, v_now), original_filename = 'anonimizado'
   WHERE patient_id = p_patient_id;
  UPDATE core.patients
     SET name = 'Paciente anonimizado', cpf = NULL,
         birth_date = date_trunc('year', birth_date)::date, sex = 'NOT_INFORMED',
         profession = NULL, phone = '0000000000', email = NULL, address = NULL,
         insurance_name = NULL, insurance_number = NULL,
         acquaintance_name = NULL, acquaintance_phone = NULL,
         anonymized_at = v_now, version = version + 1
   WHERE id = p_patient_id;
  INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action)
  VALUES (v_patient.clinic_id, core.current_user_id(), 'patients', p_patient_id, 'ANONYMIZE');
  PERFORM set_config('core.anonymizing', 'off', true);
  RETURN v_now;
END $$;

REVOKE EXECUTE ON FUNCTION core.anonymize_patient(uuid) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION core.anonymize_patient(uuid) TO crm_app;
ALTER FUNCTION core.anonymize_patient(uuid) OWNER TO crm_definer;
REVOKE CREATE ON SCHEMA core FROM crm_definer;
```
`set_config('core.anonymizing', ...)` com `is_local = true` expira no fim da transação. A flag é um segundo cinto: quem a definir por fora precisaria de `UPDATE` em `anonymized_at`, que o `crm_app` não tem.

## 3. Componentes
`V013`, `tests/anonymization.sql` (F07), chamada `SELECT core.anonymize_patient(?)` e limpeza de Storage no backend.

## 4. Lógica
1. Dentista inativa o paciente (F02).
2. Backend lista as `storage_key` do paciente, chama a função, e **depois do sucesso** apaga os objetos.
3. A função valida, calcula a retenção e executa os passos, tudo na mesma transação; `DELETE`/`UPDATE` internos disparam `write_audit()` (F05).
4. Resultado: paciente `active = false`, `anonymized_at` preenchido, sem prontuário, exames marcados como removidos.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Recepção chama | `42501` |
| Sem tenant/usuário | `28000` |
| Paciente de outra clínica ou inexistente | `P0002` (a política do definer não o enxerga) |
| Paciente ativo | `CR001` |
| Retenção não vencida | `CR002` com data em `DETAIL` |
| Já anonimizado / chamadas paralelas | `CR003` (`FOR UPDATE` serializa) |
| Paciente sem registro clínico e inativo | Anonimiza na hora (S4) |
| Edição, reativação ou novo registro depois | `CR003` |
| Gravar `anonymized_at` fora da função | `42501` |
| Falha em qualquer passo | Transação inteira desfeita |
| `UPDATE` em registro clínico antigo | Reinicia a retenção (o cálculo usa `updated_at`); documentado no contrato |
| Muitas notas (500) | Dentro do `statement_timeout` de 15 s |

## 6. Segurança e desempenho
- `crm_definer` sem login, sem `BYPASSRLS`, sem `CREATE` ao fim da migration; políticas escopadas ao tenant.
- Sem SQL dinâmico; `search_path` fixo; dados originais não aparecem em mensagens de erro.
- Irreversível: backups de até 30 dias ainda guardam o original (documentado).
