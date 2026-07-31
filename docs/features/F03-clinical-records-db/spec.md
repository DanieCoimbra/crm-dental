# Especificação Técnica de Banco de Dados: F03-clinical-records-db

**Feature:** F03-clinical-records-db — Domínio de Prontuários & Anexos  
**Documento de Origem:** `docs/global/spec-fase1-bd.md`  
**Projeto:** SaaS Clínica Dental Solo  

---

## 1. Visão Geral do Domínio

O domínio **F03-clinical-records-db** provê o armazenamento seguro das anotações de evolução médica dos atendimentos (`clinical_notes`) e metadados de exames/radiografias (`patient_files`). Todo o acesso é restrito ao Dentista Solo (`admin`), bloqueando completamente usuários do papel `receptionist`.

---

## 2. Tipos Customizados & DDL

```sql
CREATE TYPE patient_file_type_enum AS ENUM (
    'RADIOGRAFIA',
    'FOTO_INTRAORAL',
    'EXAME_LABORATORIAL',
    'OUTRO'
);

-- Tabela de Evolução Clínica
CREATE TABLE clinical_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    dentist_id UUID NOT NULL REFERENCES users(id),
    attendance_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    chief_complaint_encrypted TEXT,
    diagnosis_encrypted TEXT,
    procedure_summary TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_clinical_notes_patient ON clinical_notes(clinic_id, patient_id, attendance_date DESC);

-- Tabela de Anexos e Radiografias
CREATE TABLE patient_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_type patient_file_type_enum NOT NULL,
    file_url TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    uploaded_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_patient_files_patient ON patient_files(clinic_id, patient_id);
```

---

## 3. Especificação das Políticas RLS & RBAC Estrito

### 3.1 Habilitação de RLS
```sql
ALTER TABLE clinical_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient_files ENABLE ROW LEVEL SECURITY;
```

### 3.2 Políticas da Tabela `clinical_notes` (Acesso Exclusivo de Admin)
```sql
CREATE POLICY clinical_notes_select_policy ON clinical_notes
    FOR SELECT USING (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY clinical_notes_insert_policy ON clinical_notes
    FOR INSERT WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY clinical_notes_update_policy ON clinical_notes
    FOR UPDATE USING (clinic_id = current_clinic_id() AND is_admin())
    WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY clinical_notes_delete_policy ON clinical_notes
    FOR DELETE USING (clinic_id = current_clinic_id() AND is_admin());
```

### 3.3 Políticas da Tabela `patient_files` (Acesso Exclusivo de Admin)
```sql
CREATE POLICY patient_files_select_policy ON patient_files
    FOR SELECT USING (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY patient_files_insert_policy ON patient_files
    FOR INSERT WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY patient_files_delete_policy ON patient_files
    FOR DELETE USING (clinic_id = current_clinic_id() AND is_admin());
```
