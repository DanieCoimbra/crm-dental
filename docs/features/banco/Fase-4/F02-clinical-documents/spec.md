# Spec — F02 Documentos clínicos (Fase 4)

## 1. Visão técnica
- **Feature:** `core.clinical_documents` e seus triggers, RLS e grants.
- **Stack:** PostgreSQL, `plpgsql`, RLS, advisory lock. Sem extensão.
- **Abordagem:** `V032__core_clinical_documents.sql`, como `crm_owner`, uma transação. Depende de V004, V008, V015 e V031. A auditoria (trigger `trg_cdoc_audit`) entra na F03/V033, não aqui.

## 2. Objetos

Dicionário de colunas, constraints e índices: **spec global, seções 2.3 e 2.4** (fonte única; não duplicar). Resumo:

- Colunas: `id`, `clinic_id`, `patient_id`, `appointment_id`, `document_type`, `number`, `issued_on`, `rest_days`, `target_specialty`, `content varchar(22000)`, `sha256`, `voided_at`, `voided_by`, `void_reason`, `anonymized_at`, `created_by`, `created_at`, `updated_at`, `version`.
- FKs compostas `ON DELETE RESTRICT`: paciente `(patient_id, clinic_id)`, consulta `(appointment_id, patient_id, clinic_id)`, `created_by` e `voided_by` com `clinic_id`.
- `CHECK`s: `ck_cdoc_type`, `_number`, `_sha256`, `_rest`, `_special`, `_required`, `_content`, `_void`; `ux_cdoc_number (clinic_id, document_type, number)`.
- Índices: `ix_cdoc_patient`, `ix_cdoc_appointment` (parcial). `created_by`/`voided_by` sem índice (decisão registrada).
- RLS: `cdoc_sel`, `cdoc_ins`, `cdoc_upd`, `cdoc_backup_sel`; sem política de `DELETE`.
- `GRANT SELECT, INSERT, UPDATE (voided_at, voided_by, void_reason, version)` ao `crm_app`; `GRANT SELECT` ao `crm_backup`. As políticas e grants do `crm_definer` ficam na F04.

O `ck_cdoc_content` já prevê o estado anonimizado (conteúdo e estruturados nulos com `anonymized_at`), que só a F04 produz.

## 3. Componentes
| Função / trigger | Momento | Papel |
|---|---|---|
| `clinical_documents_before_insert()` / `trg_cdoc_before_insert` | `BEFORE INSERT` | papel, guardas de paciente, CRO, número, limpeza dos campos de sistema |
| `guard_clinical_document_update()` / `trg_cdoc_guard_update` | `BEFORE UPDATE` | só anular ou anonimizar |
| `forbid_immutable_columns(...)` / `trg_cdoc_forbid_immutable` | `BEFORE UPDATE` | `id, clinic_id, document_type, number, patient_id, appointment_id, issued_on, sha256, created_by, created_at` |
| `set_updated_at()` / `trg_cdoc_set_updated_at` | `BEFORE UPDATE` | carimbo |
| `clinical_documents_block()` / `trg_cdoc_no_delete`, `trg_cdoc_no_truncate` | `BEFORE DELETE` (linha), `BEFORE TRUNCATE` (comando) | `42501` para qualquer papel |

Todas as funções: `SET search_path = pg_catalog, pg_temp`; `REVOKE EXECUTE ... FROM PUBLIC`.

## 4. Lógica

**Emissão (`BEFORE INSERT`)**
1. Papel ≠ `DENTIST_ADMIN` → `42501` (antes de qualquer outra checagem).
2. `SELECT active, anonymized_at FROM core.patients WHERE id = NEW.patient_id AND clinic_id = NEW.clinic_id FOR KEY SHARE`: anonimizado → `CR003`; inativo → `CR005`; sem linha → segue (FK responde).
3. `SELECT cro_number FROM core.users WHERE id = NEW.created_by AND clinic_id = NEW.clinic_id`: linha com `cro_number IS NULL` → `CR012`.
4. `pg_advisory_xact_lock(hashtextextended('cdoc:' || NEW.clinic_id::text || ':' || NEW.document_type, 0))`; `NEW.number := COALESCE(max(number) ..., 0) + 1`.
5. `voided_at`, `voided_by`, `void_reason`, `anonymized_at := NULL`.

**Atualização (`BEFORE UPDATE`)** — transições aceitas:
- **Anular:** papel `DENTIST_ADMIN`; `OLD.voided_at IS NULL`, `NEW.voided_at IS NOT NULL`, `NEW.voided_by = core.current_user_id()`; `content`, `rest_days`, `target_specialty`, `anonymized_at` inalterados.
- **Anonimizar:** `current_user = 'crm_definer'` e `current_setting('core.anonymizing', true) = 'on'`; `OLD.anonymized_at IS NULL`, `NEW.anonymized_at IS NOT NULL`; `content`, `rest_days`, `target_specialty`, `void_reason` → `NULL`; `voided_*` inalterados.
- Todo o resto → `23514`.

## 5. Erros e bordas
| Cenário | Resultado |
|---|---|
| Recepção insere | `42501` |
| Recepção lê ou atualiza | 0 linhas |
| Emissor sem CRO / paciente anonimizado / inativo | `CR012` / `CR003` / `CR005` |
| `CERTIFICATE` sem `rest_days`; campo estruturado de outro tipo | `23514` |
| `content` fora de Base64 ou grande demais | `23514` |
| `appointment_id` de outro paciente | `23503` |
| Anular duas vezes; `UPDATE` de outra coluna | `23514` |
| `UPDATE` de coluna sem `GRANT` | `42501` |
| `DELETE`, `TRUNCATE` | `42501` |
| Duas emissões simultâneas do mesmo tipo | números consecutivos distintos |
| Emissão concorrente com anonimização do paciente | espera e responde `CR003` |
| `ROLLBACK` após atribuir número | buraco na numeração (aceito) |

## 6. Segurança e desempenho
- `ENABLE` + `FORCE` RLS; funções de sessão como `(SELECT ...)`; `crm_app` não é dono e não tem `BYPASSRLS`.
- Risco aceito: `crm_app` pode tomar o lock `cdoc:` de outra clínica (negação de serviço).
- Metas: `INSERT` p95 ≤ 20 ms e lista do paciente p95 ≤ 10 ms com 100 mil documentos (máx. via `ux_cdoc_number` com varredura reversa).
- Rollback `U032`: remove triggers, funções e tabela e **apaga os documentos**. Destrutiva por convenção do projeto (como a `U025`): o dono não enxerga linhas sob RLS `FORCE`, então não consegue conferir se há documentos; com dados, restaurar do `pg_dump`.
