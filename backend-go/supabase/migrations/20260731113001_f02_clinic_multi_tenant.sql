-- Migration: F02 Clinic Multi-Tenant Management
-- Description: Criação da tabela clinics com RLS e idempotência completa.

CREATE TABLE IF NOT EXISTS public.clinics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    cnpj_cpf VARCHAR(20),
    phone VARCHAR(20),
    email VARCHAR(255) NOT NULL UNIQUE,
    trial_ends_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '14 days'),
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'trial_expired', 'canceled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Ativação do Row Level Security
ALTER TABLE public.clinics ENABLE ROW LEVEL SECURITY;

-- Remoção condicional de políticas para idempotência
DROP POLICY IF EXISTS clinics_select_policy ON public.clinics;
DROP POLICY IF EXISTS clinics_update_policy ON public.clinics;

-- Política de Leitura: Usuário autenticado só lê os dados da sua própria clínica
CREATE POLICY clinics_select_policy ON public.clinics
    FOR SELECT TO authenticated
    USING (id = public.get_current_clinic_id());

-- Política de Atualização: Apenas administradores da clínica podem atualizar
CREATE POLICY clinics_update_policy ON public.clinics
    FOR UPDATE TO authenticated
    USING (id = public.get_current_clinic_id() AND public.get_current_user_role() = 'admin')
    WITH CHECK (id = public.get_current_clinic_id() AND public.get_current_user_role() = 'admin');
