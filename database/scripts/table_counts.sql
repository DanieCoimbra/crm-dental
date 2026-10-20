-- Linhas por tabela de core (sem o histórico do Flyway), no formato tabela|contagem, ordenado.
-- Executa com os privilégios (e a RLS) de quem chama: como crm_backup, a política USING (true) mostra todas as clínicas.
SELECT c.relname || '|' || (xpath('/row/c/text()',
         query_to_xml(format('SELECT count(*) AS c FROM core.%I', c.relname), false, true, '')))[1]::text
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'core' AND c.relkind IN ('r', 'p') AND c.relname <> 'flyway_schema_history'
 ORDER BY c.relname;
