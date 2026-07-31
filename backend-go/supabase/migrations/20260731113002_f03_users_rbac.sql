-- Migration: F03 User Profiles & RBAC
-- Description: Criação da tabela public.users integrada ao auth.users com suporte a papéis e RLS.

CREATE TABLE IF NOT EXISTS public.users (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    clinic_id UUID NOT NULL REFERENCES public.clinics(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('admin', 'receptionist')),
    avatar_url TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Criação de Índices Otimizados
CREATE INDEX IF NOT EXISTS idx_users_clinic_id ON public.users(clinic_id);
CREATE INDEX IF NOT EXISTS idx_users_role ON public.users(role);

-- Ativação do Row Level Security
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;

-- Remoção condicional de políticas para idempotência
DROP POLICY IF EXISTS users_select_policy ON public.users;
DROP POLICY IF EXISTS users_admin_write_policy ON public.users;

-- Política de Leitura: Membros da mesma clínica visualizam os perfis da equipe
CREATE POLICY users_select_policy ON public.users
    FOR SELECT TO authenticated
    USING (clinic_id = public.get_current_clinic_id());

-- Política de Gestão (INSERT/UPDATE/DELETE): Apenas admin gerencia os usuários da sua clínica
CREATE POLICY users_admin_write_policy ON public.users
    FOR ALL TO authenticated
    USING (clinic_id = public.get_current_clinic_id() AND public.get_current_user_role() = 'admin')
    WITH CHECK (clinic_id = public.get_current_clinic_id() AND public.get_current_user_role() = 'admin');
