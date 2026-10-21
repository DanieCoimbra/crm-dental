# Spec — F04 Metadados de Exames e Storage (Fase 1)

Complementa `docs/global/banco/Fase-1/spec.md` (seções 2.3, 3.2, 5.4 e 6.3).

## 1. Visão técnica
- **Feature:** `core.patient_files` e configuração do bucket.
- **Stack:** SQL, migration (`V011`), Supabase Storage (painel).
- **Abordagem:** tabela de metadados com RLS assimétrica (inserção para os dois papéis; leitura e alteração só para o dentista).

## 2. Objetos

### 2.1 `V011__core_patient_files.sql`
```sql
CREATE TABLE core.patient_files (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id uuid NOT NULL REFERENCES core.clinics(id) ON DELETE RESTRICT,
  patient_id uuid NOT NULL,
  kind varchar(20) NOT NULL,
  storage_key varchar(120) NOT NULL,
  original_filename varchar(255) NOT NULL,
  content_type varchar(50) NOT NULL,
  size_bytes bigint NOT NULL,
  deleted_at timestamptz,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_files_kind CHECK (kind IN ('RADIOGRAPH','INTRAORAL_PHOTO','OTHER')),
  CONSTRAINT ck_files_type CHECK (content_type IN ('image/jpeg','image/png','image/webp','application/pdf')),
  CONSTRAINT ck_files_size CHECK (size_bytes BETWEEN 1 AND 10485760),
  CONSTRAINT ck_files_key CHECK (storage_key ~ ('^' || clinic_id::text || '/' || patient_id::text || '/[0-9a-f-]{36}$')),
  CONSTRAINT fk_files_patient FOREIGN KEY (patient_id, clinic_id)
    REFERENCES core.patients (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_files_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_files_storage_key UNIQUE (storage_key)
);
CREATE INDEX ix_files_patient ON core.patient_files (clinic_id, patient_id, created_at DESC, id DESC)
  WHERE deleted_at IS NULL;
```

### 2.2 Triggers, RLS e grants
```sql
CREATE TRIGGER trg_files_set_updated_at BEFORE UPDATE ON core.patient_files
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
CREATE TRIGGER trg_files_forbid_immutable BEFORE UPDATE ON core.patient_files
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns
    ('id','clinic_id','patient_id','storage_key','content_type','size_bytes','created_by','created_at');

ALTER TABLE core.patient_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.patient_files FORCE  ROW LEVEL SECURITY;
CREATE POLICY files_sel ON core.patient_files FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND core.current_app_role() = 'DENTIST_ADMIN');
CREATE POLICY files_ins ON core.patient_files FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND core.current_app_role() IN ('DENTIST_ADMIN','RECEPTIONIST')
              AND created_by = core.current_user_id());
CREATE POLICY files_upd ON core.patient_files FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND core.current_app_role() = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND core.current_app_role() = 'DENTIST_ADMIN');
CREATE POLICY files_backup_sel ON core.patient_files FOR SELECT TO crm_backup USING (true);

GRANT SELECT, INSERT, UPDATE ON core.patient_files TO crm_app;
GRANT SELECT ON core.patient_files TO crm_backup;
```
`original_filename` não é imutável: a anonimização o substitui (F06).

### 2.3 Storage (manual, painel; documentado em `database/README.md`)
Bucket privado; `file_size_limit` 10 MB; `allowed_mime_types` = lista da `CHECK`; sem política em `storage.objects` para `anon`/`authenticated`; chave `<clinic_id>/<patient_id>/<uuid>`.

## 3. Componentes
`V011`, `tests/clinical_roles.sql` (parte de exames), `database/README.md`, entidade `PatientFile` e `FileStorage` do backend.

## 4. Lógica
1. Backend valida tipo/tamanho, gera `id` e `storage_key`, envia o objeto ao Storage.
2. Abre a transação com as três variáveis e faz `INSERT` simples (sem `RETURNING`).
3. Se o `INSERT` falhar, o backend apaga o objeto enviado (ou deixa para conciliação).
4. Dentista lista (`ix_files_patient`), baixa por URL assinada (backend) e remove por `UPDATE deleted_at`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Tipo, tamanho ou `kind` inválidos | `23514` |
| `storage_key` de outra clínica/paciente/fora do formato | `23514` |
| `storage_key` repetida | `23505` |
| Recepção lê/lista | 0 linhas |
| Recepção altera | 0 linhas afetadas |
| Recepção `INSERT ... RETURNING` | Falha pela política de `SELECT` |
| Paciente de outra clínica | `23503` |
| Alterar `storage_key`, `size_bytes` etc. | `23514` |
| `DELETE` | `42501` |
| Objeto some do Storage | Metadado fica órfão; conciliação no backend |

## 6. Segurança e desempenho
- Binário nunca no banco; bucket privado e chave de serviço só no backend.
- Download por URL assinada depois de checar clínica e papel.
- Lista de exames por índice parcial; sem *sort*.
