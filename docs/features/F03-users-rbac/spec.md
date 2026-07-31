# Especificação Técnica: User Profiles & RBAC

## 1. Visão Geral Técnica
- **Domínio:** `F03-users-rbac`
- **Ferramentas:** PostgreSQL, Supabase Auth FK, Supabase RLS

## 2. Modelagem & DDL (`public.users`)

```sql
CREATE TABLE public.users (
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

CREATE INDEX idx_users_clinic_id ON public.users(clinic_id);
CREATE INDEX idx_users_role ON public.users(role);

ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;

CREATE POLICY users_select_policy ON public.users
    FOR SELECT TO authenticated
    USING (clinic_id = public.get_current_clinic_id());

CREATE POLICY users_admin_write_policy ON public.users
    FOR ALL TO authenticated
    USING (clinic_id = public.get_current_clinic_id() AND public.get_current_user_role() = 'admin')
    WITH CHECK (clinic_id = public.get_current_clinic_id() AND public.get_current_user_role() = 'admin');
```
