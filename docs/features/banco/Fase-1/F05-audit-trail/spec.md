# Spec — F05 Trilha de Auditoria (Fase 1)

Complementa `docs/global/banco/Fase-1/spec.md` (seções 2.3, 2.6, 3 e 4.1).

## 1. Visão técnica
- **Feature:** `core.audit_log`, `core.write_audit()` e triggers.
- **Stack:** PL/pgSQL, `SECURITY DEFINER`, migration (`V012`).
- **Abordagem:** trigger por linha nas quatro tabelas clínicas escreve só identificadores; tabela append-only; leitura restrita ao dentista.

## 2. Objetos (`V012__core_audit_log.sql`, como `crm_owner`, transação única)

### 2.1 Tabela, índices, RLS e grants
```sql
CREATE TABLE core.audit_log (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  clinic_id uuid NOT NULL REFERENCES core.clinics(id) ON DELETE RESTRICT,
  user_id uuid NOT NULL,
  table_name varchar(30) NOT NULL,
  record_id uuid NOT NULL,
  action varchar(10) NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_audit_table CHECK (table_name IN ('patients','medical_histories','clinical_notes','patient_files')),
  CONSTRAINT ck_audit_action CHECK (action IN ('INSERT','UPDATE','DELETE','ANONYMIZE')),
  CONSTRAINT fk_audit_user FOREIGN KEY (user_id, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT
);
CREATE INDEX ix_audit_clinic_time ON core.audit_log (clinic_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_record ON core.audit_log (clinic_id, table_name, record_id, occurred_at DESC);

ALTER TABLE core.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.audit_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY audit_sel ON core.audit_log FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND core.current_app_role() = 'DENTIST_ADMIN');
CREATE POLICY audit_definer_ins ON core.audit_log FOR INSERT TO crm_definer
  WITH CHECK (clinic_id = core.current_clinic_id());
CREATE POLICY audit_backup_sel ON core.audit_log FOR SELECT TO crm_backup USING (true);

GRANT SELECT ON core.audit_log TO crm_app, crm_backup;
GRANT INSERT ON core.audit_log TO crm_definer;
```
Colunas de identidade não exigem privilégio na sequência para `INSERT`. Sem política para `crm_app` em `INSERT/UPDATE/DELETE`.

```sql
-- o pg_dump do crm_backup lê last_value/is_called da sequência; sem isto o backup inteiro falha (descoberto pelo teste 26)
GRANT SELECT ON SEQUENCE core.audit_log_id_seq TO crm_backup;
```

### 2.2 Função de auditoria
```sql
CREATE FUNCTION core.write_audit() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_user uuid := NULLIF(pg_catalog.current_setting('app.user_id', true), '')::uuid;
  v_row  record;
BEGIN
  IF v_user IS NULL THEN
    RAISE EXCEPTION 'auditoria exige app.user_id' USING ERRCODE = '28000';
  END IF;
  v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action)
  VALUES (v_row.clinic_id, v_user, TG_TABLE_NAME, v_row.id, TG_OP);
  RETURN NULL;
END $$;
```
Dono `crm_definer` (mesmo procedimento da V006: `GRANT CREATE` temporário, `ALTER FUNCTION ... OWNER TO`, `REVOKE CREATE`); `REVOKE EXECUTE ... FROM PUBLIC`, sem `GRANT` (trigger não precisa de `EXECUTE` em tempo de disparo).

### 2.3 Triggers
```sql
CREATE TRIGGER trg_patients_audit AFTER INSERT OR UPDATE OR DELETE ON core.patients
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
-- idem: medical_histories, clinical_notes, patient_files

CREATE FUNCTION core.audit_log_append_only() RETURNS trigger LANGUAGE plpgsql
  SET search_path = pg_catalog AS $$
BEGIN RAISE EXCEPTION 'audit_log é somente de inserção' USING ERRCODE = '42501'; END $$;
CREATE TRIGGER trg_audit_log_append_only BEFORE UPDATE OR DELETE ON core.audit_log
  FOR EACH ROW EXECUTE FUNCTION core.audit_log_append_only();
```
`TRUNCATE` é bloqueado por um segundo trigger, de comando (`trg_audit_log_no_truncate`, `BEFORE TRUNCATE ... FOR EACH STATEMENT`), com a mesma função. Quem pode desligar triggers (dono/superusuário, por DDL) continua capaz de esvaziar o log; a mitigação é operacional (migrations revisadas, conexão do dono restrita).

## 3. Componentes
`V012`, `tests/audit_log.sql` (F07), entidade `AuditEntry` (`@Immutable`) do backend.

## 4. Lógica
1. Qualquer `INSERT/UPDATE/DELETE` clínico dispara `write_audit()` após a linha.
2. A função lê `app.user_id`; se nulo, aborta toda a transação.
3. `INSERT` em `audit_log` como `crm_definer`; a política confere `clinic_id = tenant`; a FK composta confere que o usuário é da clínica.
4. A F06 insere a linha `ANONYMIZE` diretamente (mesmo papel e política).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Sem `app.user_id` | `28000`; operação clínica revertida |
| `user_id` de outra clínica | `23503` |
| `clinic_id` da linha ≠ tenant da sessão | `WITH CHECK` falha (`42501`) |
| `UPDATE` sem mudança de valores | Gera 1 linha (aceito) |
| `crm_app` tenta `INSERT/UPDATE/DELETE/TRUNCATE` | `42501` |
| Dono tenta `UPDATE/DELETE` | `42501` (trigger) |
| `DELETE` em cascata | Não existe (`RESTRICT`) |
| Recepção consulta | 0 linhas |
| Registro afetado já removido | `record_id` permanece (sem FK) |

## 6. Segurança e desempenho
- Log sem valores, sem nome, sem CPF e sem conteúdo clínico.
- Função `SECURITY DEFINER` com `search_path` fixo, sem SQL dinâmico, dono sem login e sem `BYPASSRLS`.
- Consultas por índice, sem *sort*; custo de 1 `INSERT` por linha alterada.
