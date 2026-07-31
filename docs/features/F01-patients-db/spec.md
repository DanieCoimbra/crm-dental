# Especificação Técnica de Banco de Dados: F01-patients-db

**Feature:** F01-patients-db — Domínio de Dados de Pacientes  
**Documento de Origem:** `docs/global/spec-fase1-bd.md`  
**Projeto:** SaaS Clínica Dental Solo  

---

## 1. Visão Geral do Domínio

O domínio **F01-patients-db** responde pela persistência, privacidade e integridade dos dados cadastrais dos pacientes. Ele estabelece o isolamento multi-tenant por `clinic_id`, garante o cumprimento da LGPD através de campos criptografados (CPF, Telefone, Observações) e provê controle de exclusão lógica (Soft Delete).

---

## 2. Estrutura da Tabela & Definições DDL

```sql
CREATE TABLE patients (
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

-- Índices de Desempenho e Filtros Otimizados
CREATE INDEX idx_patients_clinic_id ON patients(clinic_id);
CREATE INDEX idx_patients_clinic_name ON patients(clinic_id, full_name);
CREATE INDEX idx_patients_active ON patients(clinic_id, deleted_at) WHERE deleted_at IS NULL;
```

---

## 3. Especificação das Políticas RLS (Row Level Security)

### 3.1 Habilitação
```sql
ALTER TABLE patients ENABLE ROW LEVEL SECURITY;
```

### 3.2 Política de Leitura (`SELECT`)
Permite a visualização por qualquer usuário autenticado da própria clínica, desde que o registro não esteja marcado como excluído.
```sql
CREATE POLICY patients_select_policy ON patients
    FOR SELECT
    USING (
        clinic_id = current_clinic_id()
        AND deleted_at IS NULL
    );
```

### 3.3 Política de Inserção (`INSERT`)
Permite a inserção por usuários autenticados da clínica (`admin` e `receptionist`).
```sql
CREATE POLICY patients_insert_policy ON patients
    FOR INSERT
    WITH CHECK (
        clinic_id = current_clinic_id()
    );
```

### 3.4 Política de Edição (`UPDATE`)
Permite atualização por usuários da clínica.
```sql
CREATE POLICY patients_update_policy ON patients
    FOR UPDATE
    USING (
        clinic_id = current_clinic_id()
    )
    WITH CHECK (
        clinic_id = current_clinic_id()
    );
```

### 3.5 Política de Exclusão (`DELETE`)
Operações de exclusão (ou soft delete) são restritas estritamente ao papel `admin`.
```sql
CREATE POLICY patients_delete_policy ON patients
    FOR DELETE
    USING (
        clinic_id = current_clinic_id()
        AND is_admin()
    );
```

---

## 4. Trigger de Timestamping
```sql
CREATE OR REPLACE FUNCTION update_patients_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_patients_updated_at
BEFORE UPDATE ON patients
FOR EACH ROW EXECUTE FUNCTION update_patients_timestamp();
```
