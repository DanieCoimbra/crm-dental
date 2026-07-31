# Especificação Técnica Global de Banco de Dados & Cibersegurança — Fase 1 (Núcleo Clínico)

**Documento:** Especificação Técnica Global de Banco de Dados & Segurança (Supabase / PostgreSQL)  
**PRD de Origem:** `docs/global/prd-fase1-bd.md`  
**Projeto:** SaaS Clínica Dental Solo (CRM Clínica Dental)  
**Fase:** Fase 1 — Núcleo Clínico  
**Autor:** Arquiteto de Banco de Dados e Cibersegurança Sênior (`supabase-sec-dba`)  
**Status:** Em Revisão / Aguardando Aprovação Humana  

---

## 1. Visão Geral da Arquitetura de Banco de Dados & Segurança

- **Engine de Banco de Dados:** PostgreSQL 15+ (Gerenciado via Supabase).
- **Mecanismo de Segurança Central:** Row Level Security (RLS) habilitado em 100% das tabelas.
- **Autenticação & Injeção de Contexto:** Integrado ao `auth.users` do Supabase via tokens JWT contendo `clinic_id` e `role` em `app_metadata`.
- **Estratégia de Criptografia:** Criptografia simétrica AES-256 / PGP no PostgreSQL via extensão `pgcrypto` ou na camada de serviço Go.
- **Padrão de Integridade Multi-tenant:** Chave estrangeira `clinic_id` obrigatória e indexada em todas as tabelas.

---

## 2. Tipos Customizados & ENUMs

Para padronizar o odontograma e o prontuário clínico no PostgreSQL, serão definidos os seguintes tipos enumerados:

```sql
-- Status Anatômico e Clínico de cada Dente/Face
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

-- Categorização dos Arquivos Anexos
CREATE TYPE patient_file_type_enum AS ENUM (
    'RADIOGRAFIA',
    'FOTO_INTRAORAL',
    'EXAME_LABORATORIAL',
    'OUTRO'
);
```

---

## 3. Schemas e Definição das Tabelas DDL (Fase 1)

### 3.1 Tabela `patients`
Armazena dados demográficos e cadastrais do paciente da clínica.

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

-- Índices de Performance e Pesquisa Multi-tenant
CREATE INDEX idx_patients_clinic_id ON patients(clinic_id);
CREATE INDEX idx_patients_full_name ON patients(clinic_id, full_name);
CREATE INDEX idx_patients_deleted_at ON patients(deleted_at) WHERE deleted_at IS NULL;
```

### 3.2 Tabela `teeth_status` (Estado Atual do Odontograma)
Mantém o estado anatômico vigente de cada dente ou face do paciente.

```sql
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
```

### 3.3 Tabela `teeth_history` (Log Imutável de Evolução Dental)
Histórico imutável de todas as modificações ocorridas no mapa dental.

```sql
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

CREATE INDEX idx_teeth_history_patient_tooth ON teeth_history(clinic_id, patient_id, tooth_number);
```

### 3.4 Tabela `clinical_notes` (Prontuário / Notas de Atendimento)
Evoluções clínicas registradas pelo dentista pós-atendimento.

```sql
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
```

### 3.5 Tabela `patient_files` (Radiografias e Documentos)
Metadados de exames e radiografias armazenados em bucket seguro.

```sql
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

## 4. Funções Auxiliares de Segurança & Contexto RLS

Para otimização e isolamento, criaremos funções `STABLE` / `SECURITY DEFINER` que lêem as claims do token JWT assinado pelo Supabase:

```sql
-- Retorna o clinic_id da sessão ativa
CREATE OR REPLACE FUNCTION current_clinic_id()
RETURNS UUID AS $$
BEGIN
    RETURN NULLIF(
        (current_setting('request.jwt.claims', true)::json -> 'app_metadata' ->> 'clinic_id'),
        ''
    )::UUID;
EXCEPTION
    WHEN OTHERS THEN RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

-- Retorna a role (admin/receptionist) da sessão ativa
CREATE OR REPLACE FUNCTION current_user_role()
RETURNS TEXT AS $$
BEGIN
    RETURN COALESCE(
        (current_setting('request.jwt.claims', true)::json -> 'app_metadata' ->> 'role'),
        ''
    );
EXCEPTION
    WHEN OTHERS THEN RETURN '';
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;

-- Verifica se o usuário ativo é admin (Dentista Solo)
CREATE OR REPLACE FUNCTION is_admin()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN current_user_role() = 'admin';
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER;
```

---

## 5. Especificação das Políticas de Row Level Security (RLS)

### 5.1 Tabela `patients` (Acesso por Admin e Recepcionista)
- **Habilitar RLS:** `ALTER TABLE patients ENABLE ROW LEVEL SECURITY;`
- **Politica SELECT:** Permite leitura se `clinic_id = current_clinic_id()` e `deleted_at IS NULL`.
- **Politica INSERT:** Permite inserção se `clinic_id = current_clinic_id()`.
- **Politica UPDATE:** Permite edição se `clinic_id = current_clinic_id()`.
- **Politica DELETE:** Permite exclusão (ou soft delete) apenas para `is_admin()`.

### 5.2 Tabelas Clínicas Restritas (`teeth_status`, `teeth_history`, `clinical_notes`, `patient_files`)
- **Habilitar RLS:** `ENABLE ROW LEVEL SECURITY` em todas.
- **Regra Geral:** Acesso restrito a usuários onde `clinic_id = current_clinic_id()` **AND** `is_admin() = true`.
- **Bloqueio de Recepcionista:** Se `current_user_role()` for `'receptionist'`, todas as operações (`SELECT`, `INSERT`, `UPDATE`, `DELETE`) em tabelas clínicas falham instantaneamente no PostgreSQL.

### 5.3 Imutabilidade do Histórico (`teeth_history`)
- **Sem Política de UPDATE ou DELETE:** Não existirá nenhuma política RLS concedendo privilégios de `UPDATE` ou `DELETE` na tabela `teeth_history`.
- **Trigger de Bloqueio Rígido:**
```sql
CREATE OR REPLACE FUNCTION prevent_teeth_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Operação não permitida: O histórico do odontograma é imutável.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_teeth_history_update_delete
BEFORE UPDATE OR DELETE ON teeth_history
FOR EACH ROW EXECUTE FUNCTION prevent_teeth_history_mutation();
```

---

## 6. Próximos Passos (Workflow Supabase Sec DBA)

1. **[CONCLUÍDO] Etapa 1:** PRD de Banco de Dados da Fase 1 (`docs/global/prd-fase1-bd.md`).
2. **[ATUAL] Etapa 2:** Aprovação desta Especificação Técnica Global de Banco de Dados (`docs/global/spec-fase1-bd.md`).
3. **Etapa 3 (`breakdown`):** Fracionar a Fase 1 em subpastas de domínios isolados (`docs/features/patients`, `docs/features/odontogram`, `docs/features/clinical_records`).
4. **Etapa 4 (`spec-write`):** Especificação técnica detalhada por domínio.
5. **Etapa 5 (`contract`):** Geração dos Contratos de Dados, DDLs e RLS finais (`contract.md`).
