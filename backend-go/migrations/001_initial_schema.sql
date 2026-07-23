-- ==============================================================================
-- DENTAL CLINIC CRM - MIGRATION COMPLETA DE BANCO DE DADOS (SUPABASE POSTGRESQL)
-- ==============================================================================
-- Este script cria todas as tabelas, relacionamentos, índices e dados iniciais (seed)
-- para o sistema Dental Clinic CRM.
-- Pode ser executado diretamente no SQL Editor do Supabase ou via CLI/psql.
-- ==============================================================================

-- 1. HABILITAR EXTENSÕES REQUERIDAS
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ==============================================================================
-- 2. TABELAS DE DOMÍNIO PRINCIPAL E SAAS
-- ==============================================================================

-- Tabela: clinics
CREATE TABLE IF NOT EXISTS public.clinics (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    cnpj VARCHAR(20) UNIQUE NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(20),
    status VARCHAR(50) DEFAULT 'trial',
    plan VARCHAR(50) DEFAULT 'premium',
    trial_ends_at TIMESTAMPTZ,
    deleted_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_clinics_deleted_at ON public.clinics(deleted_at);

-- Tabela: roles
CREATE TABLE IF NOT EXISTS public.roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    permissions TEXT DEFAULT '[]',
    clinic_id BIGINT REFERENCES public.clinics(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Tabela: rooms
CREATE TABLE IF NOT EXISTS public.rooms (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    deleted_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_rooms_deleted_at ON public.rooms(deleted_at);

-- Tabela: users
CREATE TABLE IF NOT EXISTS public.users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password TEXT NOT NULL,
    cpf VARCHAR(20),
    phone VARCHAR(20),
    address TEXT,
    medical_registry VARCHAR(50),
    ctps VARCHAR(50),
    avatar VARCHAR(255),
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    current_room_id BIGINT REFERENCES public.rooms(id) ON DELETE SET NULL,
    role_id BIGINT REFERENCES public.roles(id) ON DELETE SET NULL,
    theme_preference VARCHAR(50) DEFAULT 'system',
    deleted_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON public.users(deleted_at);

-- Tabela: settings
CREATE TABLE IF NOT EXISTS public.settings (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    key VARCHAR(100) NOT NULL,
    value TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==============================================================================
-- 3. PACIENTES E PRONTUÁRIOS
-- ==============================================================================

-- Tabela: patients
CREATE TABLE IF NOT EXISTS public.patients (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    cpf VARCHAR(20),
    email VARCHAR(255),
    phone VARCHAR(20),
    cep VARCHAR(20),
    street VARCHAR(255),
    neighborhood VARCHAR(255),
    number VARCHAR(50),
    health_insurance VARCHAR(100),
    birth_date DATE,
    medical_history TEXT, -- Dados sensíveis criptografados via AES
    notes TEXT,           -- Dados sensíveis criptografados via AES
    weight NUMERIC(6,2),
    deleted_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_patients_deleted_at ON public.patients(deleted_at);
CREATE INDEX IF NOT EXISTS idx_patients_clinic_id ON public.patients(clinic_id);

-- Tabela: clinical_evolutions
CREATE TABLE IF NOT EXISTS public.clinical_evolutions (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    patient_id BIGINT NOT NULL REFERENCES public.patients(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    content_html TEXT NOT NULL, -- Dados criptografados
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_clinical_evolutions_deleted_at ON public.clinical_evolutions(deleted_at);

-- Tabela: patient_files
CREATE TABLE IF NOT EXISTS public.patient_files (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    patient_id BIGINT NOT NULL REFERENCES public.patients(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    supabase_url VARCHAR(500) NOT NULL DEFAULT '',
    file_type VARCHAR(100),
    category VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_patient_files_deleted_at ON public.patient_files(deleted_at);

-- ==============================================================================
-- 4. AGENDAMENTOS E PROCEDIMENTOS
-- ==============================================================================

-- Tabela: appointment_types
CREATE TABLE IF NOT EXISTS public.appointment_types (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    duration_minutes INT DEFAULT 30,
    color VARCHAR(20) DEFAULT '#3788d8',
    deleted_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_appointment_types_deleted_at ON public.appointment_types(deleted_at);

-- Tabela: appointments
CREATE TABLE IF NOT EXISTS public.appointments (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    doctor_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    patient_id BIGINT NOT NULL REFERENCES public.patients(id) ON DELETE CASCADE,
    room_id BIGINT REFERENCES public.rooms(id) ON DELETE SET NULL,
    appointment_type_id BIGINT REFERENCES public.appointment_types(id) ON DELETE SET NULL,
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    notes TEXT,
    status VARCHAR(50) DEFAULT 'scheduled',
    actual_start_time TIMESTAMPTZ,
    actual_end_time TIMESTAMPTZ,
    deleted_by BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_appointments_deleted_at ON public.appointments(deleted_at);
CREATE INDEX IF NOT EXISTS idx_appointments_clinic_id ON public.appointments(clinic_id);

-- Tabela: waitlists
CREATE TABLE IF NOT EXISTS public.waitlists (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    patient_id BIGINT NOT NULL REFERENCES public.patients(id) ON DELETE CASCADE,
    doctor_id BIGINT REFERENCES public.users(id) ON DELETE SET NULL,
    appointment_type_id BIGINT REFERENCES public.appointment_types(id) ON DELETE SET NULL,
    preferred_days TEXT,
    preferred_time_range VARCHAR(50),
    urgency_level VARCHAR(50),
    notes TEXT,
    status VARCHAR(50) DEFAULT 'pending',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Tabela: shift_assignments
CREATE TABLE IF NOT EXISTS public.shift_assignments (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    doctor_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    room_id BIGINT NOT NULL REFERENCES public.rooms(id) ON DELETE CASCADE,
    date DATE,
    shift VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==============================================================================
-- 5. ESTOQUE E MATERIAIS
-- ==============================================================================

-- Tabela: inventory_items
CREATE TABLE IF NOT EXISTS public.inventory_items (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    sku VARCHAR(100),
    quantity NUMERIC(10,2) DEFAULT 0,
    min_quantity NUMERIC(10,2) DEFAULT 0,
    unit VARCHAR(50),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_inventory_items_deleted_at ON public.inventory_items(deleted_at);
CREATE INDEX IF NOT EXISTS idx_inventory_items_clinic_id ON public.inventory_items(clinic_id);

-- Tabela: inventory_transactions
CREATE TABLE IF NOT EXISTS public.inventory_transactions (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    inventory_item_id BIGINT NOT NULL REFERENCES public.inventory_items(id) ON DELETE CASCADE,
    type VARCHAR(10) NOT NULL, -- 'in' ou 'out'
    quantity NUMERIC(10,2) NOT NULL,
    notes TEXT,
    date TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Tabela: procedure_materials
CREATE TABLE IF NOT EXISTS public.procedure_materials (
    id BIGSERIAL PRIMARY KEY,
    appointment_type_id BIGINT NOT NULL REFERENCES public.appointment_types(id) ON DELETE CASCADE,
    inventory_item_id BIGINT NOT NULL REFERENCES public.inventory_items(id) ON DELETE CASCADE,
    quantity NUMERIC(10,2) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_procedure_materials_appointment_type_id ON public.procedure_materials(appointment_type_id);
CREATE INDEX IF NOT EXISTS idx_procedure_materials_inventory_item_id ON public.procedure_materials(inventory_item_id);

-- ==============================================================================
-- 6. FINANCEIRO E FATURAMENTO
-- ==============================================================================

-- Tabela: clinic_transactions
CREATE TABLE IF NOT EXISTS public.clinic_transactions (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    patient_id BIGINT REFERENCES public.patients(id) ON DELETE SET NULL,
    type VARCHAR(50) NOT NULL, -- "income", "expense"
    category VARCHAR(100),
    description TEXT,
    total_amount BIGINT NOT NULL DEFAULT 0, -- em centavos
    payment_method VARCHAR(100),
    status VARCHAR(50) NOT NULL, -- "paid", "pending", "overdue", "canceled"
    due_date TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_clinic_transactions_clinic_id ON public.clinic_transactions(clinic_id);
CREATE INDEX IF NOT EXISTS idx_clinic_transactions_patient_id ON public.clinic_transactions(patient_id);
CREATE INDEX IF NOT EXISTS idx_clinic_transactions_type ON public.clinic_transactions(type);

-- Tabela: clinic_installments
CREATE TABLE IF NOT EXISTS public.clinic_installments (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES public.clinic_transactions(id) ON DELETE CASCADE,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    number INT NOT NULL,
    total_number INT NOT NULL,
    amount_cents BIGINT NOT NULL,
    due_date TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    status VARCHAR(50) NOT NULL, -- "pending", "paid", "overdue"
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==============================================================================
-- 7. AFILIADOS, CUPONS E MARKETING
-- ==============================================================================

-- Tabela: affiliates
CREATE TABLE IF NOT EXISTS public.affiliates (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,
    commission_pct NUMERIC(5,2) DEFAULT 0,
    balance NUMERIC(15,2) DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_affiliates_deleted_at ON public.affiliates(deleted_at);

-- Tabela: coupons
CREATE TABLE IF NOT EXISTS public.coupons (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    discount_type VARCHAR(20) NOT NULL, -- "percentage", "fixed"
    discount_value NUMERIC(10,2) NOT NULL,
    affiliate_id BIGINT REFERENCES public.affiliates(id) ON DELETE SET NULL,
    max_uses INT DEFAULT 0,
    uses_count INT DEFAULT 0,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_coupons_deleted_at ON public.coupons(deleted_at);

-- Tabela: referral_partners
CREATE TABLE IF NOT EXISTS public.referral_partners (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    commission_rate NUMERIC(5,2) DEFAULT 0,
    pix_key VARCHAR(255),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_referral_partners_deleted_at ON public.referral_partners(deleted_at);

-- Tabela: promo_codes
CREATE TABLE IF NOT EXISTS public.promo_codes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    partner_id UUID REFERENCES public.referral_partners(id) ON DELETE SET NULL,
    code VARCHAR(100) NOT NULL,
    discount_type VARCHAR(50),
    discount_value NUMERIC(10,2) DEFAULT 0,
    max_uses INT DEFAULT 0,
    used_count INT DEFAULT 0,
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT idx_clinic_promo UNIQUE (clinic_id, code)
);
CREATE INDEX IF NOT EXISTS idx_promo_codes_deleted_at ON public.promo_codes(deleted_at);

-- Tabela: subscriptions
CREATE TABLE IF NOT EXISTS public.subscriptions (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT UNIQUE NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'trialing',
    plan VARCHAR(50) NOT NULL DEFAULT 'monthly',
    stripe_customer_id VARCHAR(255),
    stripe_subscription_id VARCHAR(255),
    current_period_end TIMESTAMPTZ,
    trial_ends_at TIMESTAMPTZ,
    coupon_id BIGINT REFERENCES public.coupons(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_subscriptions_deleted_at ON public.subscriptions(deleted_at);

-- Tabela: audit_logs
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id BIGSERIAL PRIMARY KEY,
    clinic_id BIGINT NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    action VARCHAR(100) NOT NULL,
    entity VARCHAR(100) NOT NULL,
    entity_id BIGINT NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    details TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_clinic_id ON public.audit_logs(clinic_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id ON public.audit_logs(user_id);

-- ==============================================================================
-- 8. SEED DE DADOS INICIAIS (ROLES)
-- ==============================================================================

INSERT INTO public.roles (name, permissions, created_at, updated_at)
VALUES 
    ('owner', '["manage_team", "manage_settings", "manage_billing", "view_reports", "manage_appointments"]', NOW(), NOW()),
    ('manager', '["manage_team", "manage_settings", "view_reports", "manage_appointments"]', NOW(), NOW()),
    ('doctor', '["manage_medical_records", "view_appointments"]', NOW(), NOW()),
    ('receptionist', '["manage_appointments", "view_patients", "manage_waitlist"]', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- Final do script de migração
