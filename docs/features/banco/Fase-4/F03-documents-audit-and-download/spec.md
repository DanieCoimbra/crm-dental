# Spec — F03 Auditoria e download de documentos (Fase 4)

## 1. Visão técnica
- **Feature:** auditoria de `clinical_documents` e registro de `DOWNLOAD`.
- **Stack:** PostgreSQL, `plpgsql`, `SECURITY DEFINER`, constraints.
- **Abordagem:** `V033__core_clinical_documents_audit.sql`, como `crm_owner`, uma transação, com as concessões temporárias no padrão da V020/V028/V029.

## 2. Objetos

**Constraints** (lista vigente lida do catálogo + `clinical_documents`):
```sql
ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (table_name IN (
  'patients','medical_histories','clinical_notes','patient_files','appointments',
  'budgets','installments','payments','budget_documents','clinical_documents'));
ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_action;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_action CHECK (action IN (
  'INSERT','UPDATE','DELETE','ANONYMIZE','VOID','DOWNLOAD'));
```
`DROP/ADD CONSTRAINT` valida as linhas existentes sem disparar os triggers de linha do `audit_log`.

**Funções** (dono `crm_definer`, `SET search_path = pg_catalog, pg_temp`, sem SQL dinâmico):
- `core.write_document_audit() RETURNS trigger` `SECURITY DEFINER`.
- `core.record_document_download(p_document_id uuid) RETURNS void` `SECURITY DEFINER`.

**Trigger:** `trg_cdoc_audit AFTER INSERT OR UPDATE ON core.clinical_documents FOR EACH ROW EXECUTE FUNCTION core.write_document_audit()`.

**Privilégios:** `REVOKE EXECUTE ... FROM PUBLIC` em ambas; `GRANT EXECUTE ON FUNCTION core.record_document_download(uuid) TO crm_app`. O `crm_definer` precisa de `SELECT (id, clinic_id, anonymized_at)` em `clinical_documents` e da política `cdoc_definer_sel`; como a F04 concede o `SELECT` completo e as políticas do definer, **a V033 cria só o mínimo necessário para a função de download** (`GRANT SELECT (id, clinic_id, anonymized_at)` e `cdoc_definer_sel`) e a V034 amplia.

## 3. Componentes
| Objeto | Tipo |
|---|---|
| `ck_audit_table`, `ck_audit_action` | constraints recriadas |
| `write_document_audit()` / `trg_cdoc_audit` | função / trigger |
| `record_document_download(uuid)` | função |

## 4. Lógica

**`write_document_audit`**
1. `app.user_id` nulo → `28000`.
2. `INSERT` → ação `INSERT`.
3. `UPDATE`: `OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL` → `VOID`; senão `OLD.anonymized_at IS NULL AND NEW.anonymized_at IS NOT NULL` → `ANONYMIZE`; senão `RAISE ... '23514'`.
4. `INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action)` com `NEW.clinic_id`, o usuário da sessão, `'clinical_documents'`, `NEW.id`.

**`record_document_download`**
1. `core.current_app_role()` ≠ `'DENTIST_ADMIN'` → `42501`.
2. `core.current_clinic_id()` ou `core.current_user_id()` nulos → `28000`.
3. `SELECT anonymized_at ... WHERE id = p_document_id` (a política do definer limita à clínica da sessão): sem linha → `P0002`; `anonymized_at` não nulo → `CR003`.
4. `INSERT` em `audit_log` com ação `DOWNLOAD`.

## 5. Erros e bordas
| Cenário | Resultado |
|---|---|
| Emitir sem `app.user_id` | `28000`, reverte a operação |
| Recepção chama o download | `42501`, nada gravado |
| Documento de outra clínica / inexistente | `P0002` |
| Documento anonimizado | `CR003` |
| `UPDATE` fora das transições (impossível pela F02) | `23514` |
| Vários downloads | uma linha cada |
| Apagar/alterar `audit_log` | `42501` (trigger append-only existente) |

## 6. Segurança e desempenho
- `crm_definer` sem login e sem `BYPASSRLS`; políticas dele sempre com `clinic_id = current_clinic_id()`.
- `crm_app` não consegue gravar `audit_log` diretamente (sem política de `INSERT`).
- Meta: `record_document_download` p95 ≤ 10 ms com 100 mil documentos.
- Rollback `U033`: falha se `audit_log` tiver `clinical_documents`, `VOID` ou `DOWNLOAD`; senão remove trigger, funções e restaura as constraints.
