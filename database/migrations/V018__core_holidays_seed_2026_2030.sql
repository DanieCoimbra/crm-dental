-- =====================================================================
-- V018__core_holidays_seed_2026_2030.sql | Fase 2 / F04-holidays
-- Objetivo : seed idempotente dos feriados NACIONAIS POR LEI de 2026 a 2030 em core.holidays (50 linhas: 9 datas
--            fixas por ano mais a Sexta-feira Santa, que é móvel). Carnaval e Corpus Christi são ponto facultativo e
--            ficam para o cadastro da clínica (core.clinic_holidays).
-- Executar : scripts/apply_migrations.sh, como crm_owner (as políticas hol_owner_* da V017 permitem a escrita sob FORCE).
-- Reverter : database/rollback/U018__core_holidays_seed_2026_2030.sql
-- Depende  : V017 (core.holidays).
-- Idempotência: ON CONFLICT (holiday_date) DO NOTHING; rodar duas vezes mantém 50 linhas.
-- Manutenção: o calendário termina em 2030. Atualizá-lo (ou incluir feriado criado por lei nova) é uma NOVA migration
--            de seed; esta nunca é editada depois de aplicada.
-- Sexta-feira Santa: 2026-04-03, 2027-03-26, 2028-04-14, 2029-03-30, 2030-04-19.
-- =====================================================================

INSERT INTO core.holidays (holiday_date, name) VALUES
  (DATE '2026-01-01', 'Confraternização Universal'),
  (DATE '2026-04-03', 'Sexta-feira Santa'),
  (DATE '2026-04-21', 'Tiradentes'),
  (DATE '2026-05-01', 'Dia do Trabalho'),
  (DATE '2026-09-07', 'Independência do Brasil'),
  (DATE '2026-10-12', 'Nossa Senhora Aparecida'),
  (DATE '2026-11-02', 'Finados'),
  (DATE '2026-11-15', 'Proclamação da República'),
  (DATE '2026-11-20', 'Dia da Consciência Negra'),
  (DATE '2026-12-25', 'Natal'),
  (DATE '2027-01-01', 'Confraternização Universal'),
  (DATE '2027-03-26', 'Sexta-feira Santa'),
  (DATE '2027-04-21', 'Tiradentes'),
  (DATE '2027-05-01', 'Dia do Trabalho'),
  (DATE '2027-09-07', 'Independência do Brasil'),
  (DATE '2027-10-12', 'Nossa Senhora Aparecida'),
  (DATE '2027-11-02', 'Finados'),
  (DATE '2027-11-15', 'Proclamação da República'),
  (DATE '2027-11-20', 'Dia da Consciência Negra'),
  (DATE '2027-12-25', 'Natal'),
  (DATE '2028-01-01', 'Confraternização Universal'),
  (DATE '2028-04-14', 'Sexta-feira Santa'),
  (DATE '2028-04-21', 'Tiradentes'),
  (DATE '2028-05-01', 'Dia do Trabalho'),
  (DATE '2028-09-07', 'Independência do Brasil'),
  (DATE '2028-10-12', 'Nossa Senhora Aparecida'),
  (DATE '2028-11-02', 'Finados'),
  (DATE '2028-11-15', 'Proclamação da República'),
  (DATE '2028-11-20', 'Dia da Consciência Negra'),
  (DATE '2028-12-25', 'Natal'),
  (DATE '2029-01-01', 'Confraternização Universal'),
  (DATE '2029-03-30', 'Sexta-feira Santa'),
  (DATE '2029-04-21', 'Tiradentes'),
  (DATE '2029-05-01', 'Dia do Trabalho'),
  (DATE '2029-09-07', 'Independência do Brasil'),
  (DATE '2029-10-12', 'Nossa Senhora Aparecida'),
  (DATE '2029-11-02', 'Finados'),
  (DATE '2029-11-15', 'Proclamação da República'),
  (DATE '2029-11-20', 'Dia da Consciência Negra'),
  (DATE '2029-12-25', 'Natal'),
  (DATE '2030-01-01', 'Confraternização Universal'),
  (DATE '2030-04-19', 'Sexta-feira Santa'),
  (DATE '2030-04-21', 'Tiradentes'),
  (DATE '2030-05-01', 'Dia do Trabalho'),
  (DATE '2030-09-07', 'Independência do Brasil'),
  (DATE '2030-10-12', 'Nossa Senhora Aparecida'),
  (DATE '2030-11-02', 'Finados'),
  (DATE '2030-11-15', 'Proclamação da República'),
  (DATE '2030-11-20', 'Dia da Consciência Negra'),
  (DATE '2030-12-25', 'Natal')
ON CONFLICT (holiday_date) DO NOTHING;
