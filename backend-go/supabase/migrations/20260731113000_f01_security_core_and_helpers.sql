-- Migration: F01 Security Core & Multi-Tenant Helpers
-- Description: Cria as funções PL/pgSQL utilitárias para obtenção de clinic_id e role do JWT / Usuário logado.

CREATE OR REPLACE FUNCTION public.get_current_clinic_id()
RETURNS UUID
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT COALESCE(
        (NULLIF(current_setting('request.jwt.claims', true), '')::json->>'clinic_id')::uuid,
        (SELECT clinic_id FROM public.users WHERE id = auth.uid())
    );
$$;

CREATE OR REPLACE FUNCTION public.get_current_user_role()
RETURNS TEXT
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT COALESCE(
        NULLIF(current_setting('request.jwt.claims', true), '')::json->>'role',
        (SELECT role FROM public.users WHERE id = auth.uid())
    );
$$;
