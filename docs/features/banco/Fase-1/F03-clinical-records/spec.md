# Spec — F03 Prontuário Clínico Cifrado (Fase 1)

Complementa `docs/global/banco/Fase-1/spec.md` (seções 2.3, 2.4, 2.6 e 3.2).

## 1. Visão técnica
- **Feature:** `core.medical_histories` e `core.clinical_notes`.
- **Stack:** SQL, migration (`V009`, `V010`).
- **Abordagem:** tabelas de negócio com RLS por clínica e por papel `DENTIST_ADMIN`; conteúdo cifrado pela aplicação e tratado como `varchar` opaco.

## 2. Objetos

### 2.1 `V009__core_medical_histories.sql`
```sql
CREATE TABLE core.medical_histories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id uuid NOT NULL REFERENCES core.clinics(id) ON DELETE RESTRICT,
  patient_id uuid NOT NULL,
  content varchar(54000) NOT NULL,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_histories_content CHECK (char_length(content) >= 40 AND content ~ '^[A-Za-z0-9+/]+={0,2}$'),
  CONSTRAINT fk_histories_patient FOREIGN KEY (patient_id, clinic_id)
    REFERENCES core.patients (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_histories_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_histories_patient UNIQUE (patient_id)
);
CREATE INDEX ix_histories_clinic ON core.medical_histories (clinic_id);
```

### 2.2 `V010__core_clinical_notes.sql`
Mesmas colunas, mais `noted_at timestamptz NOT NULL DEFAULT now()`, `content varchar(27000)` (mesmo `CHECK`) e **sem** `UNIQUE (patient_id)`.
```sql
CREATE INDEX ix_notes_timeline ON core.clinical_notes (clinic_id, patient_id, noted_at DESC, id DESC);
```

### 2.3 Triggers, RLS e grants (as duas tabelas, `t` = tabela)
```sql
CREATE TRIGGER trg_t_set_updated_at BEFORE UPDATE ON core.t
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
CREATE TRIGGER trg_t_forbid_immutable BEFORE UPDATE ON core.t
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id','clinic_id','patient_id','created_by','created_at');

ALTER TABLE core.t ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.t FORCE  ROW LEVEL SECURITY;
CREATE POLICY t_sel ON core.t FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id() AND core.current_app_role() = 'DENTIST_ADMIN');
CREATE POLICY t_ins ON core.t FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND core.current_app_role() = 'DENTIST_ADMIN'
              AND created_by = core.current_user_id());
CREATE POLICY t_upd ON core.t FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND core.current_app_role() = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND core.current_app_role() = 'DENTIST_ADMIN');
CREATE POLICY t_backup_sel ON core.t FOR SELECT TO crm_backup USING (true);

GRANT SELECT, INSERT, UPDATE ON core.t TO crm_app;
GRANT SELECT ON core.t TO crm_backup;
```

## 3. Componentes
`V009`, `V010`, `tests/clinical_roles.sql`, `tests/clinical_constraints.sql` (F07), entidades `MedicalHistory` e `ClinicalNote` do backend.

## 4. Lógica
- **Histórico:** o backend cifra e faz `INSERT`; para editar, `UPDATE` (versão otimista). Um paciente tem no máximo uma linha.
- **Nota:** `INSERT` com `noted_at` informado (ou o padrão), `UPDATE` do conteúdo; sem `DELETE`.
- **Linha do tempo:** `WHERE patient_id = :p AND (noted_at, id) < (:t, :i) ORDER BY noted_at DESC, id DESC LIMIT :n`.
- **Papel:** `RECEPTIONIST` enxerga 0 linhas; o backend responde 403 antes, e o banco é a segunda barreira.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Texto em claro em `content` | `23514` (`ck_*_content`) |
| Conteúdo > tamanho da coluna | `22001` |
| Segundo histórico do paciente | `23505` (`ux_histories_patient`) |
| Paciente de outra clínica | `23503` (FK composta) |
| `RECEPTIONIST` lê | 0 linhas |
| `RECEPTIONIST` grava | `42501` (viola `WITH CHECK`) |
| `app.role` ausente | Negado |
| `created_by` falso | `42501` |
| Alterar `patient_id`, `clinic_id` | `23514` (imutável) |
| `DELETE` por `crm_app` | `42501` (sem grant) |

## 6. Segurança e desempenho
- Nenhum `pgcrypto`, nenhuma chave; o conteúdo não aparece em log.
- Linha do tempo e histórico por índice; sem *sort* extra.
- Volume: dezenas de notas por paciente; sem particionamento.
