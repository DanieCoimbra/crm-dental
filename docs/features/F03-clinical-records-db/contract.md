# Contrato de Banco de Dados & RLS: F03-clinical-records-db

**Feature:** F03-clinical-records-db  
**Versão:** 1.0.0  
**Consumidores Primários:** Backend Go (`backend-go`), Visualizador de Prontuário Flutter  

---

## 1. Contrato de Esquema SQL (DDL)

```sql
-- ENUM para Tipos de Anexos
DO $$ BEGIN
    CREATE TYPE patient_file_type_enum AS ENUM ('RADIOGRAFIA', 'FOTO_INTRAORAL', 'EXAME_LABORATORIAL', 'OUTRO');
EXCEPTION WHEN duplicate_object THEN null; END $$;

-- Tabela clinical_notes
CREATE TABLE IF NOT EXISTS clinical_notes (
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

CREATE INDEX IF NOT EXISTS idx_clinical_notes_patient ON clinical_notes(clinic_id, patient_id, attendance_date DESC);

-- Tabela patient_files
CREATE TABLE IF NOT EXISTS patient_files (
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

CREATE INDEX IF NOT EXISTS idx_patient_files_patient ON patient_files(clinic_id, patient_id);
```

---

## 2. Contrato de Segurança & Políticas RLS

```sql
-- Habilitar RLS
ALTER TABLE clinical_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient_files ENABLE ROW LEVEL SECURITY;

-- Policies para clinical_notes (Exclusivo Admin)
CREATE POLICY clinical_notes_select_policy ON clinical_notes
    FOR SELECT USING (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY clinical_notes_insert_policy ON clinical_notes
    FOR INSERT WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY clinical_notes_update_policy ON clinical_notes
    FOR UPDATE USING (clinic_id = current_clinic_id() AND is_admin())
    WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY clinical_notes_delete_policy ON clinical_notes
    FOR DELETE USING (clinic_id = current_clinic_id() AND is_admin());

-- Policies para patient_files (Exclusivo Admin)
CREATE POLICY patient_files_select_policy ON patient_files
    FOR SELECT USING (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY patient_files_insert_policy ON patient_files
    FOR INSERT WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY patient_files_delete_policy ON patient_files
    FOR DELETE USING (clinic_id = current_clinic_id() AND is_admin());
```

---

## 3. Garantias & Limites do Contrato

1. **Sigilo Médico Absoluto**: Usuários com o papel `receptionist` não possuem permissão de leitura nem escrita sobre `clinical_notes` ou `patient_files`.
2. **Criptografia de Anamnese**: Queixas principais (`chief_complaint_encrypted`) e diagnósticos (`diagnosis_encrypted`) devem ser gravados utilizando criptografia forte.
3. **Armazenamento de Exames**: A coluna `file_url` armazena apenas caminhos/keys relativas do Storage com presigned URLs geradas no backend.
