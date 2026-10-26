# Spec — F03 PDF do orçamento no Storage (Fase 3)

Complementa `docs/global/banco/Fase-3/spec.md` (seções 2.3, 2.4, 3, 4.6 e 7) e a spec da F04 da Fase 1 (`patient_files`).

## 1. Visão técnica
- **Feature:** metadados do PDF do orçamento guardado no Supabase Storage.
- **Stack:** PostgreSQL 16, PL/pgSQL, migration `V025`. Sem extensão nova.
- **Abordagem:** tabela quase append-only (só `deleted_at` muda), revisão atribuída pelo banco sob a trava do orçamento.

## 2. Objetos (`V025__core_budget_documents.sql`, como `crm_owner`)

```sql
CREATE TABLE core.budget_documents (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id uuid NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  budget_id uuid NOT NULL,
  patient_id uuid NOT NULL,
  revision smallint NOT NULL CONSTRAINT ck_bdoc_revision CHECK (revision >= 1),   -- trigger atribui
  storage_key varchar(120) NOT NULL,
  content_type varchar(20) NOT NULL CONSTRAINT ck_bdoc_type CHECK (content_type = 'application/pdf'),
  size_bytes bigint NOT NULL CONSTRAINT ck_bdoc_size CHECK (size_bytes BETWEEN 1 AND 5242880),
  sha256 varchar(64) NOT NULL CONSTRAINT ck_bdoc_sha CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  generated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_bdoc_key CHECK (storage_key ~ ('^' || clinic_id::text || '/' || patient_id::text || '/budgets/[0-9a-f-]{36}$')),
  CONSTRAINT fk_bdoc_budget FOREIGN KEY (budget_id, patient_id, clinic_id)
    REFERENCES core.budgets (id, patient_id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_bdoc_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_bdoc_storage_key UNIQUE (storage_key),
  CONSTRAINT ux_bdoc_budget_revision UNIQUE (budget_id, revision)
);
CREATE INDEX ix_bdoc_budget_active ON core.budget_documents (budget_id, revision DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_bdoc_patient ON core.budget_documents (clinic_id, patient_id);
```
Sem `version` e sem `updated_at` (decisão S12 da spec global). `created_by` sem índice (`users` não é apagado).

### 2.1 Triggers
| Trigger | Evento | Efeito |
|---|---|---|
| `trg_bdoc_before_insert` → `core.budget_documents_before_insert()` | `BEFORE INSERT` | Trava o orçamento (`SELECT status FROM core.budgets WHERE id = NEW.budget_id AND clinic_id = NEW.clinic_id FOR NO KEY UPDATE`); sem linha: `RETURN NEW`; `status = 'DRAFT'` → `CR008`; `NEW.revision := COALESCE(max(revision), 0) + 1` do orçamento (inclui removidas) |
| `trg_bdoc_forbid_immutable` | `BEFORE UPDATE` | `core.forbid_immutable_columns('id','clinic_id','budget_id','patient_id','revision','storage_key','content_type','size_bytes','sha256','generated_at','created_by','created_at')` |
| `trg_bdoc_guard_update` → `core.guard_budget_document_update()` | `BEFORE UPDATE` | `OLD.deleted_at IS NOT NULL AND NEW.deleted_at IS DISTINCT FROM OLD.deleted_at` → `23514` (não volta nem muda) |

### 2.2 RLS e grants
`ENABLE` + `FORCE`. `SELECT` `T AND A`; `INSERT` `WITH CHECK (T AND D AND U)`; `UPDATE` `USING/WITH CHECK (T AND D)`; `SELECT TO crm_backup USING (true)`; sem política de `DELETE`. `GRANT SELECT, INSERT ON ... TO crm_app; GRANT UPDATE (deleted_at) ON ... TO crm_app; GRANT SELECT ... TO crm_backup`. As políticas do `crm_definer` entram na F07.

## 3. Componentes
`V025`, `U025`, `tests/budget_documents.sql`, `tests/budget_documents_race.sh`. Entidade JPA `BudgetDocument` (`@Immutable` salvo `deleted_at`, sem `@Version`).

## 4. Lógica
1. **Registrar (backend, após `SENT`):** gerar o PDF, calcular o `sha256`, gravar o objeto no Storage, `INSERT` da linha (sem `revision`); o banco trava o orçamento, confere o status e atribui a revisão.
2. **Reenviar:** novo objeto e nova linha; o orçamento não muda.
3. **Remover:** `UPDATE ... SET deleted_at = now()` (dentista); a revisão deixa de ser a ativa; o backend apaga o objeto.
4. **Revisão ativa:** `ORDER BY revision DESC LIMIT 1` com `deleted_at IS NULL` (índice parcial).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Orçamento `DRAFT` | `CR008` |
| `storage_key` de outra clínica, de outro paciente ou sem `budgets/` | `23514` |
| `sha256` com maiúscula, 63 caracteres ou não hexadecimal | `23514` |
| `content_type` ≠ PDF; 0 byte; 6 MB | `23514` |
| `storage_key` repetida | `23505` |
| Duas revisões simultâneas | Revisões 1 e 2 (a segunda espera a trava) |
| Alterar `sha256`, `storage_key` ou qualquer coluna além de `deleted_at` | `42501` para `crm_app` (grant por coluna); `23514` para o dono ou superusuário (trigger de imutáveis) |
| `deleted_at` voltando a `NULL` ou mudando | `23514` |
| Orçamento ou paciente de outra clínica | `23503` |
| Recepção grava | `INSERT` → `42501`; `UPDATE` afeta 0 linhas |
| `DELETE` | Negado (`42501`) |
| Sem `app.user_id` | `28000` (auditoria, F06) |

## 6. Segurança e desempenho
- Objeto no Storage sem RLS: bucket privado, acesso só pelo backend, chave opaca; o banco guarda `sha256` para detectar troca.
- Sem texto livre na tabela. `UPDATE` só de `deleted_at` por grant de coluna.
- PDF ativo do orçamento por `ix_bdoc_budget_active`, sem `Seq Scan`.
