-- Apenas para bancos DESCARTÁVEIS de teste (PR e dev local): simula os papéis internos
-- do Supabase para exercitar os REVOKE. Nunca executar no Supabase real.
DO $$
DECLARE
  r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('CREATE ROLE %I NOLOGIN', r);
    END IF;
  END LOOP;
END $$;
