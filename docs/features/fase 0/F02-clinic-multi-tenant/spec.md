# Especificação Técnica: Clinic Multi-Tenant Management

## 1. Visão Geral Técnica
- **Domínio:** `F02-clinic-multi-tenant`
- **Ferramentas:** PostgreSQL, Supabase RLS

## 2. Modelagem & DDL (`public.clinics`)

```sql
CREATE TABLE public.clinics (
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

ALTER TABLE public.clinics ENABLE ROW LEVEL SECURITY;

CREATE POLICY clinics_select_policy ON public.clinics
    FOR SELECT TO authenticated
    USING (id = public.get_current_clinic_id());

CREATE POLICY clinics_update_policy ON public.clinics
    FOR UPDATE TO authenticated
    USING (id = public.get_current_clinic_id() AND public.get_current_user_role() = 'admin')
    WITH CHECK (id = public.get_current_clinic_id() AND public.get_current_user_role() = 'admin');
```
