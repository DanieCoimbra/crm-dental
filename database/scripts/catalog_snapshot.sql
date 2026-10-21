-- Catálogo de core, uma linha por item. Usado pelo backup.sh (grava em schema.txt) e pela
-- restore_check.sh (regenera após a restauração e compara): a MESMA consulta nos dois lados evita deriva.
-- Donos e GRANTs ficam de fora de propósito: o dump usa --no-owner --no-acl.
-- A exibição de constraints (pg_get_constraintdef, regclass) depende do search_path da sessão; fixá-lo faz o backup e a restauração
-- imprimirem o mesmo texto mesmo com papéis de search_path diferente (crm_backup x postgres).
-- Uso: psql -X -q -At -f catalog_snapshot.sql | LC_ALL=C sort   (-q: sem a etiqueta "SET" na saída)
SET search_path = pg_catalog;

SELECT 'constraint|' || c.conrelid::regclass || '|' || c.conname || '|' || pg_get_constraintdef(c.oid)
  FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
 WHERE n.nspname = 'core'
UNION ALL
SELECT 'index|' || tablename || '|' || indexname || '|' || indexdef
  FROM pg_indexes WHERE schemaname = 'core'
UNION ALL
SELECT 'policy|' || tablename || '|' || policyname || '|' || cmd || '|' || roles::text || '|' || coalesce(qual, '') || '|' || coalesce(with_check, '')
  FROM pg_policies WHERE schemaname = 'core'
UNION ALL
SELECT 'rls|' || c.relname || '|' || c.relrowsecurity || '|' || c.relforcerowsecurity
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'core' AND c.relkind IN ('r', 'p')
UNION ALL
SELECT 'trigger|' || t.tgrelid::regclass || '|' || t.tgname || '|' || pg_get_triggerdef(t.oid)
  FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'core' AND NOT t.tgisinternal
UNION ALL
SELECT 'function|' || p.oid::regprocedure || '|' || p.prosecdef || '|' || coalesce(p.proconfig::text, '')
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'core';
