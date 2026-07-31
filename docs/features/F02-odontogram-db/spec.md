# Especificação Técnica de Banco de Dados: F02-odontogram-db

**Feature:** F02-odontogram-db — Domínio de Dados do Odontograma & Imutabilidade  
**Documento de Origem:** `docs/global/spec-fase1-bd.md`  
**Projeto:** SaaS Clínica Dental Solo  

---

## 1. Visão Geral do Domínio

O domínio **F02-odontogram-db** responde pela representação estruturada e anatômica da arcada dentária do paciente (`teeth_status`) e pelo registro auditável imutável de todas as suas alterações (`teeth_history`). O sistema utiliza a notação oficial FDI e impede edições ou remoções no histórico de auditoria.

---

## 2. Tipos Customizados & DDL

```sql
CREATE TYPE tooth_face_enum AS ENUM (
    'MESIAL',
    'DISTAL',
    'OCLUSAL',
    'INCISAL',
    'VESTIBULAR',
    'PALATINA',
    'LINGUAL',
    'GERAL'
);

CREATE TYPE tooth_condition_enum AS ENUM (
    'HIGIDO',
    'CARIADO',
    'RESTAURADO',
    'EXTRAIDO',
    'IMPLANTE',
    'TRATAMENTO_CANAL',
    'COROA_PROTESE',
    'EM_TRATAMENTO'
);

-- Tabela de Estado Atual
CREATE TABLE teeth_status (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    tooth_number INT NOT NULL CHECK (
        (tooth_number BETWEEN 11 AND 48) OR (tooth_number BETWEEN 51 AND 85)
    ),
    face tooth_face_enum NOT NULL DEFAULT 'GERAL',
    condition tooth_condition_enum NOT NULL DEFAULT 'HIGIDO',
    updated_by UUID NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT unq_patient_tooth_face UNIQUE (patient_id, tooth_number, face)
);

CREATE INDEX idx_teeth_status_patient ON teeth_status(clinic_id, patient_id);

-- Tabela de Histórico Imutável
CREATE TABLE teeth_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    patient_id UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    teeth_status_id UUID REFERENCES teeth_status(id) ON DELETE SET NULL,
    tooth_number INT NOT NULL CHECK (
        (tooth_number BETWEEN 11 AND 48) OR (tooth_number BETWEEN 51 AND 85)
    ),
    face tooth_face_enum NOT NULL,
    previous_condition tooth_condition_enum,
    new_condition tooth_condition_enum NOT NULL,
    notes TEXT,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_teeth_history_lookup ON teeth_history(clinic_id, patient_id, tooth_number);
```

---

## 3. Especificação das Políticas RLS & RBAC Estrito

### 3.1 Habilitação de RLS
```sql
ALTER TABLE teeth_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE teeth_history ENABLE ROW LEVEL SECURITY;
```

### 3.2 Políticas da Tabela `teeth_status` (Apenas Dentista Admin)
```sql
CREATE POLICY teeth_status_select_policy ON teeth_status
    FOR SELECT USING (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY teeth_status_insert_policy ON teeth_status
    FOR INSERT WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY teeth_status_update_policy ON teeth_status
    FOR UPDATE USING (clinic_id = current_clinic_id() AND is_admin())
    WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY teeth_status_delete_policy ON teeth_status
    FOR DELETE USING (clinic_id = current_clinic_id() AND is_admin());
```

### 3.3 Políticas da Tabela `teeth_history` (Append-Only para Admin)
```sql
CREATE POLICY teeth_history_select_policy ON teeth_history
    FOR SELECT USING (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY teeth_history_insert_policy ON teeth_history
    FOR INSERT WITH CHECK (clinic_id = current_clinic_id() AND is_admin());
```

---

## 4. Trigger de Imutabilidade do Histórico

```sql
CREATE OR REPLACE FUNCTION prevent_teeth_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Operação não permitida: O histórico do odontograma é um registro imutável.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_teeth_history_mutation
BEFORE UPDATE OR DELETE ON teeth_history
FOR EACH ROW EXECUTE FUNCTION prevent_teeth_history_mutation();
```
