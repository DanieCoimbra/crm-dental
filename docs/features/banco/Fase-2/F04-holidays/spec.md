# Spec — F04 Feriados (Fase 2)

Complementa `docs/global/banco/Fase-2/spec.md` (seções 1.2 S5/S6/S9, 2.3, 3.2 e 5.4).

## 1. Visão técnica
- **Feature:** `core.holidays` (global), `core.clinic_holidays` (por clínica) e o seed 2026–2030.
- **Stack:** PostgreSQL 16, migrations `V017` e `V018`.
- **Abordagem:** `holidays` é dado público, legível por `crm_app` e escrito só por migrations (`crm_owner`, por políticas próprias sob `FORCE`); `clinic_holidays` segue o padrão por clínica com escrita só do dentista.

## 2. Objetos

### 2.1 `V017__core_holidays.sql` (como `crm_owner`, transação única)
```sql
CREATE TABLE core.holidays (
  holiday_date date PRIMARY KEY,
  name         varchar(80) NOT NULL CONSTRAINT ck_holidays_name CHECK (char_length(btrim(name)) >= 2)
);
ALTER TABLE core.holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.holidays FORCE  ROW LEVEL SECURITY;

CREATE POLICY hol_sel        ON core.holidays FOR SELECT TO crm_app    USING (true);
CREATE POLICY hol_backup_sel ON core.holidays FOR SELECT TO crm_backup USING (true);
-- o dono também obedece à RLS (FORCE): uma política por operação, só para migrations/seed
CREATE POLICY hol_owner_sel ON core.holidays FOR SELECT TO crm_owner USING (true);
CREATE POLICY hol_owner_ins ON core.holidays FOR INSERT TO crm_owner WITH CHECK (true);
CREATE POLICY hol_owner_upd ON core.holidays FOR UPDATE TO crm_owner USING (true) WITH CHECK (true);
CREATE POLICY hol_owner_del ON core.holidays FOR DELETE TO crm_owner USING (true);

GRANT SELECT ON core.holidays TO crm_app, crm_backup;

CREATE TABLE core.clinic_holidays (
  id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id    uuid        NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  holiday_date date        NOT NULL,
  name         varchar(80) NOT NULL,
  created_by   uuid        NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  version      bigint      NOT NULL DEFAULT 0,
  CONSTRAINT ck_chol_date CHECK (holiday_date >= DATE '2000-01-01'),
  CONSTRAINT ck_chol_name CHECK (char_length(btrim(name)) >= 2),
  CONSTRAINT ux_clinic_holidays_date UNIQUE (clinic_id, holiday_date),
  CONSTRAINT fk_chol_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT
);
CREATE TRIGGER trg_chol_set_updated_at BEFORE UPDATE ON core.clinic_holidays
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
CREATE TRIGGER trg_chol_forbid_immutable BEFORE UPDATE ON core.clinic_holidays
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id','clinic_id','created_by','created_at');

ALTER TABLE core.clinic_holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.clinic_holidays FORCE  ROW LEVEL SECURITY;

CREATE POLICY chol_sel ON core.clinic_holidays FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
     AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));
CREATE POLICY chol_ins ON core.clinic_holidays FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN'
     AND created_by = core.current_user_id());
CREATE POLICY chol_upd ON core.clinic_holidays FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');
CREATE POLICY chol_del ON core.clinic_holidays FOR DELETE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');
CREATE POLICY chol_backup_sel ON core.clinic_holidays FOR SELECT TO crm_backup USING (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON core.clinic_holidays TO crm_app;
GRANT SELECT                         ON core.clinic_holidays TO crm_backup;
```
`DELETE` físico em `clinic_holidays` é a **única** exceção da aplicação à regra "sem `DELETE`" (S5): sem dado pessoal, sem histórico e sem tabela que aponte para o feriado. O índice de `created_by` é omitido de propósito (`users` nunca é apagado).

### 2.2 `V018__core_holidays_seed_2026_2030.sql` (como `crm_owner`, idempotente)
```sql
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
  (DATE '2026-12-25', 'Natal')
  -- ... idem para 2027 a 2030 (10 por ano; Sexta-feira Santa: 2027-03-26, 2028-04-14, 2029-03-30, 2030-04-19)
ON CONFLICT (holiday_date) DO NOTHING;
```
Total: **50 linhas** (10 por ano em 5 anos). O seed usa só `INSERT`; as políticas `hol_owner_ins` e o grant implícito do dono bastam.

## 3. Componentes
`V017`, `V018`, `U017`, `U018`, `tests/holidays.sql`, entidades `Holiday` (somente leitura) e `ClinicHoliday` do backend.

## 4. Lógica
1. O backend consulta o período: `SELECT holiday_date, name FROM core.holidays WHERE holiday_date BETWEEN ? AND ?` e o mesmo em `clinic_holidays` (a RLS já limita à clínica); une os dois.
2. O dentista cadastra (`INSERT`), renomeia (`UPDATE`) ou remove (`DELETE`) feriados da clínica.
3. Atualizar o calendário nacional é nova migration de seed (`ON CONFLICT DO NOTHING`).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `crm_app` escreve em `holidays` | `42501` |
| Data repetida na mesma clínica | `23505` (`ux_clinic_holidays_date`) |
| Mesma data em outra clínica | Passa |
| Data anterior a 2000, nome com menos de 2 caracteres | `23514` |
| Recepção grava ou remove em `clinic_holidays` | `INSERT` → `42501`; `UPDATE`/`DELETE` → 0 linhas |
| `DELETE` de feriado de outra clínica | 0 linhas |
| `app.role` ausente | `clinic_holidays` negada; `holidays` segue legível |
| Rodar o seed duas vezes | Mantém 50 linhas |
| `created_by` ≠ `app.user_id` | `42501` |

## 6. Segurança e desempenho
- `holidays` é dado público; escrita só do `crm_owner` por migration, via políticas dedicadas.
- `clinic_holidays` sob RLS `FORCE`; o `DELETE` exige dentista e clínica.
- Consultas por PK/`UNIQUE` (50 linhas globais e poucas por clínica): trivial.
