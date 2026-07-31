# Contrato de Banco de Dados & RLS: F02-odontogram-db

**Feature:** F02-odontogram-db  
**Versão:** 1.0.0  
**Consumidores Primários:** Backend Go (`backend-go`), Odontograma Flutter Widget  

---

## 1. Contrato de Esquema SQL (DDL)

```sql
-- ENUMs do Odontograma
DO $$ BEGIN
    CREATE TYPE tooth_face_enum AS ENUM ('MESIAL', 'DISTAL', 'OCLUSAL', 'INCISAL', 'VESTIBULAR', 'PALATINA', 'LINGUAL', 'GERAL');
EXCEPTION WHEN duplicate_object THEN null; END $$;

DO $$ BEGIN
    CREATE TYPE tooth_condition_enum AS ENUM ('HIGIDO', 'CARIADO', 'RESTAURADO', 'EXTRAIDO', 'IMPLANTE', 'TRATAMENTO_CANAL', 'COROA_PROTESE', 'EM_TRATAMENTO');
EXCEPTION WHEN duplicate_object THEN null; END $$;

-- Tabela teeth_status
CREATE TABLE IF NOT EXISTS teeth_status (
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

CREATE INDEX IF NOT EXISTS idx_teeth_status_patient ON teeth_status(clinic_id, patient_id);

-- Tabela teeth_history (Imutável)
CREATE TABLE IF NOT EXISTS teeth_history (
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

CREATE INDEX IF NOT EXISTS idx_teeth_history_lookup ON teeth_history(clinic_id, patient_id, tooth_number);
```

---

## 2. Contrato de Segurança & Políticas RLS

```sql
-- Habilitar RLS
ALTER TABLE teeth_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE teeth_history ENABLE ROW LEVEL SECURITY;

-- Policies para teeth_status (Exclusivo Admin)
CREATE POLICY teeth_status_select_policy ON teeth_status
    FOR SELECT USING (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY teeth_status_insert_policy ON teeth_status
    FOR INSERT WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY teeth_status_update_policy ON teeth_status
    FOR UPDATE USING (clinic_id = current_clinic_id() AND is_admin())
    WITH CHECK (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY teeth_status_delete_policy ON teeth_status
    FOR DELETE USING (clinic_id = current_clinic_id() AND is_admin());

-- Policies para teeth_history (Append-Only Exclusivo Admin)
CREATE POLICY teeth_history_select_policy ON teeth_history
    FOR SELECT USING (clinic_id = current_clinic_id() AND is_admin());

CREATE POLICY teeth_history_insert_policy ON teeth_history
    FOR INSERT WITH CHECK (clinic_id = current_clinic_id() AND is_admin());
```

---

## 3. Contrato de Imutabilidade (Trigger)

```sql
CREATE OR REPLACE FUNCTION prevent_teeth_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Operação não permitida: O histórico do odontograma é um registro imutável.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_teeth_history_mutation ON teeth_history;
CREATE TRIGGER trg_prevent_teeth_history_mutation
BEFORE UPDATE OR DELETE ON teeth_history
FOR EACH ROW EXECUTE FUNCTION prevent_teeth_history_mutation();
```

---

## 4. Garantias & Limites do Contrato

1. **Bloqueio do Papel `receptionist`**: Negação total (0 registros retornados / erro RLS) para qualquer tentativa de consulta ou mutação em `teeth_status` ou `teeth_history`.
2. **Validação Anatômica FDI**: Rejeição automática pelo PostgreSQL para números de dentes fora da notação oficial FDI.
3. **Imutabilidade Auditada**: Tentativas de `UPDATE` ou `DELETE` na tabela `teeth_history` resultam em exceção fatal disparada pela Trigger do banco de dados.
