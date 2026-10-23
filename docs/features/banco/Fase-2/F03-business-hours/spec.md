# Spec — F03 Horário comercial (Fase 2)

Complementa `docs/global/banco/Fase-2/spec.md` (seções 1.2 S2/S3, 2.3, 2.6 e 3.2).

## 1. Visão técnica
- **Feature:** `core.business_hours`.
- **Stack:** PostgreSQL 16, migration `V016`.
- **Abordagem:** uma linha por (clínica, dia) com expediente e almoço; invariantes por `CHECK`; RLS por clínica e papel; sem `DELETE`.

## 2. Objetos (`V016__core_business_hours.sql`, como `crm_owner`, transação única)

### 2.1 Tabela
```sql
CREATE TABLE core.business_hours (
  id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id       uuid        NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  weekday         smallint    NOT NULL,
  is_open         boolean     NOT NULL,
  opens_at        time(0),
  closes_at       time(0),
  lunch_starts_at time(0),
  lunch_ends_at   time(0),
  created_by      uuid        NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         bigint      NOT NULL DEFAULT 0,
  CONSTRAINT ck_bh_weekday CHECK (weekday BETWEEN 1 AND 7),
  CONSTRAINT ck_bh_open CHECK (
       (is_open AND opens_at IS NOT NULL AND closes_at IS NOT NULL AND opens_at < closes_at)
    OR (NOT is_open AND opens_at IS NULL AND closes_at IS NULL
        AND lunch_starts_at IS NULL AND lunch_ends_at IS NULL)),
  CONSTRAINT ck_bh_lunch CHECK (
       (lunch_starts_at IS NULL AND lunch_ends_at IS NULL)
    OR (lunch_starts_at IS NOT NULL AND lunch_ends_at IS NOT NULL
        AND opens_at < lunch_starts_at AND lunch_starts_at < lunch_ends_at
        AND lunch_ends_at < closes_at)),
  CONSTRAINT ux_bh_clinic_weekday UNIQUE (clinic_id, weekday),
  CONSTRAINT fk_bh_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT
);
```
`ux_bh_clinic_weekday` é também o índice de leitura da semana e da FK de `clinic_id`. O índice de `created_by` é omitido de propósito (`users` nunca é apagado); registrar na migration.

### 2.2 Triggers
```sql
CREATE TRIGGER trg_bh_set_updated_at BEFORE UPDATE ON core.business_hours
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
CREATE TRIGGER trg_bh_forbid_immutable BEFORE UPDATE ON core.business_hours
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id','clinic_id','weekday','created_by','created_at');
```

### 2.3 RLS e grants
```sql
ALTER TABLE core.business_hours ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.business_hours FORCE  ROW LEVEL SECURITY;

CREATE POLICY bh_sel ON core.business_hours FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
     AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));
CREATE POLICY bh_ins ON core.business_hours FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN'
     AND created_by = core.current_user_id());
CREATE POLICY bh_upd ON core.business_hours FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');
CREATE POLICY bh_backup_sel ON core.business_hours FOR SELECT TO crm_backup USING (true);

GRANT SELECT, INSERT, UPDATE ON core.business_hours TO crm_app;
GRANT SELECT                 ON core.business_hours TO crm_backup;
```
Sem política de `DELETE`.

## 3. Componentes
`V016`, `U016`, `tests/business_hours.sql`, entidade `BusinessHours` do backend.

## 4. Lógica
1. O dentista grava a semana: `INSERT` (primeira vez) ou `UPDATE` por (clínica, dia); o backend pode usar `ON CONFLICT (clinic_id, weekday) DO UPDATE`, já que o dentista tem `SELECT`, `INSERT` e `UPDATE`.
2. Dia fechado: `is_open = false` e os quatro horários nulos.
3. O backend lê as linhas (≤ 7 por clínica), converte as horas locais com `clinics.timezone` e decide avisar ou bloquear.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Fechado com horário preenchido; aberto sem horário; `opens_at >= closes_at` | `23514` (`ck_bh_open`) |
| Almoço fora do expediente, só uma ponta, ou em dia fechado | `23514` (`ck_bh_lunch` / `ck_bh_open`) |
| `weekday` 0 ou 8 | `23514` |
| Segunda linha do mesmo dia | `23505` (`ux_bh_clinic_weekday`) |
| Recepção grava | `INSERT` → `42501`; `UPDATE` → 0 linhas |
| `DELETE` por `crm_app` | `42501` |
| `app.role` ausente | Tudo negado |
| `created_by` ≠ `app.user_id` | `42501` |
| Alterar `weekday` ou `clinic_id` | `23514` (trigger de imutáveis) |

## 6. Segurança e desempenho
- RLS `FORCE`; escrita só do dentista; sem `DELETE`; sem dado pessoal.
- No máximo 7 linhas por clínica: leitura trivial por `ux_bh_clinic_weekday`.
