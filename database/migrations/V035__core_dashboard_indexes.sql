-- =====================================================================
-- V035__core_dashboard_indexes.sql | Fase 4 / F05-dashboard-indexes
-- Objetivo : índice da taxa de retorno do dashboard (K4 da spec global, 2.6): a sonda "este paciente já teve outro atendimento
--            COMPLETED nos 12 meses anteriores?" vira Index Only Scan. Nenhuma view, view materializada ou tabela de resumo (D9).
-- Executar : scripts/apply_migrations.sh, como crm_owner, numa única transação (tabela pequena).
--            Em tabela real grande, crie o índice à mão com CREATE INDEX CONCURRENTLY como crm_owner (fora de transação).
-- Reverter : database/rollback/U035__core_dashboard_indexes.sql
-- Depende  : V015 (appointments).
-- Observações:
--   * Predicado com igualdade (status = 'COMPLETED'), não IN: o pg_dump reinterpreta varchar IN (...) e o guarda de catálogo da
--     restauração acusaria diferença (lição da V015).
--   * ix_patients_clinic_active (K1) é CONDICIONAL: só entra se o teste phase4_performance reprovar a meta; não foi necessário.
--   * K1, K2, K3 e K5 usam índices existentes (ix_patients_clinic_name, ix_appt_clinic_start, ix_budgets_decided).
-- =====================================================================

CREATE INDEX ix_appt_completed_patient
  ON core.appointments (clinic_id, patient_id, starts_at)
  WHERE status = 'COMPLETED';
