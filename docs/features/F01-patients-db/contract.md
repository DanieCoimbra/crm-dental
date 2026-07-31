# Contrato de Banco de Dados & RLS: F01-patients-db

**Feature:** F01-patients-db  
**Versão:** 1.0.0  
**Consumidores Primários:** Backend Go (`backend-go`), Supabase Client, Flutter App  

---

## 1. Contrato de Esquema SQL (DDL)

```sql
-- DDL de Criação da Tabela patients
CREATE TABLE IF NOT EXISTS patients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_id UUID NOT NULL REFERENCES clinics(id) ON DELETE CASCADE,
    full_name VARCHAR(150) NOT NULL,
    cpf_encrypted TEXT NOT NULL,
    phone_encrypted TEXT NOT NULL,
    email VARCHAR(150),
    birth_date DATE,
    health_insurance VARCHAR(100),
    notes_encrypted TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

-- Índices de Performance
CREATE INDEX IF NOT EXISTS idx_patients_clinic_id ON patients(clinic_id);
CREATE INDEX IF NOT EXISTS idx_patients_clinic_name ON patients(clinic_id, full_name);
CREATE INDEX IF NOT EXISTS idx_patients_active ON patients(clinic_id, deleted_at) WHERE deleted_at IS NULL;
```

---

## 2. Contrato de Segurança & Políticas RLS

```sql
-- Habilitar RLS
ALTER TABLE patients ENABLE ROW LEVEL SECURITY;

-- Policy: SELECT (Admin e Recepcionista)
CREATE POLICY patients_select_policy ON patients
    FOR SELECT
    USING (
        clinic_id = current_clinic_id()
        AND deleted_at IS NULL
    );

-- Policy: INSERT (Admin e Recepcionista)
CREATE POLICY patients_insert_policy ON patients
    FOR INSERT
    WITH CHECK (
        clinic_id = current_clinic_id()
    );

-- Policy: UPDATE (Admin e Recepcionista)
CREATE POLICY patients_update_policy ON patients
    FOR UPDATE
    USING (
        clinic_id = current_clinic_id()
    )
    WITH CHECK (
        clinic_id = current_clinic_id()
    );

-- Policy: DELETE (Apenas Admin / Dentista Solo)
CREATE POLICY patients_delete_policy ON patients
    FOR DELETE
    USING (
        clinic_id = current_clinic_id()
        AND is_admin()
    );
```

---

## 3. Garantias & Limites do Contrato

1. **Acesso do Papel `receptionist`**: Permitido para cadastrar (`INSERT`), visualizar (`SELECT`) e editar (`UPDATE`) campos básicos de pacientes da mesma clínica.
2. **Exclusão de Registros**: Negada para `receptionist` (bloqueada via RLS). Permitida apenas para `admin`.
3. **Isolamento de Tenant**: Nenhuma query retorna pacientes de outra clínica (`clinic_id != current_clinic_id()`).
4. **Criptografia**: Os campos `cpf_encrypted`, `phone_encrypted` e `notes_encrypted` devem receber texto seguro em base64 ou PGP armored string.
