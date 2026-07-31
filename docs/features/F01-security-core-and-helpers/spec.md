# Especificação Técnica: Security Core & Multi-Tenant Helpers

## 1. Visão Geral Técnica
- **Domínio:** `F01-security-core-and-helpers`
- **Ferramentas:** PostgreSQL PL/pgSQL, Supabase Auth JWT
- **Abordagem:** Funções `SECURITY DEFINER` e `STABLE` para leitura zero-cost das claims do token no RLS.

## 2. Modelagem & Funções SQL

### `public.get_current_clinic_id()`
```sql
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
```

### `public.get_current_user_role()`
```sql
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
```

## 3. Tratamento de Erros e Segurança
- **Fallback:** Se a claim do JWT não estiver presente, consulta o registro do usuário logado via `auth.uid()`.
- **Prevenção de Injeção SQL:** Uso restrito de SQL nativo com tipos explícitos (`UUID`, `TEXT`).
