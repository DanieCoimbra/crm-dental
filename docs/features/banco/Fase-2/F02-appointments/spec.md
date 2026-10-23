# Spec — F02 Agendamentos (Fase 2)

Complementa `docs/global/banco/Fase-2/spec.md` (seções 2.3, 2.4, 2.6, 3, 4.1, 4.2, 4.5 e 4.6).

## 1. Visão técnica
- **Feature:** `core.appointments`, `ex_appt_no_overlap` e triggers.
- **Stack:** PostgreSQL 16, `btree_gist`, PL/pgSQL, migration `V015`.
- **Abordagem:** a tabela tem `clinic_id`, RLS `ENABLE` + `FORCE` e uma política por operação; o conflito é uma constraint de exclusão GiST; o papel da recepção é reforçado em `USING`/`WITH CHECK` do `UPDATE`; um trigger `SECURITY INVOKER` valida o paciente quando o agendamento passa a ocupar horário.

## 2. Objetos (`V015__core_appointments.sql`, como `crm_owner`, transação única)

### 2.1 Guarda da extensão (primeira instrução)
```sql
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist') THEN
    RAISE EXCEPTION 'btree_gist ausente: execute bootstrap/20_extensions.sql como postgres e reaplique a V015';
  END IF;
END $$;
```

### 2.2 Tabela, constraints e índices
```sql
CREATE TABLE core.appointments (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id   uuid        NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  patient_id  uuid        NOT NULL,
  starts_at   timestamptz NOT NULL,
  ends_at     timestamptz NOT NULL,
  status      varchar(15) NOT NULL DEFAULT 'SCHEDULED',
  notes       varchar(500),
  procedure_type_id uuid,                       -- sem FK: a tabela é da Fase 3
  created_by  uuid        NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  version     bigint      NOT NULL DEFAULT 0,
  CONSTRAINT ck_appt_status CHECK (status IN ('SCHEDULED','CONFIRMED','IN_PROGRESS','COMPLETED','NO_SHOW','CANCELED')),
  CONSTRAINT ck_appt_range  CHECK (ends_at > starts_at),
  CONSTRAINT ck_appt_start_minute CHECK (mod(extract(epoch FROM starts_at), 60) = 0),
  CONSTRAINT ck_appt_duration CHECK (
    (ends_at - starts_at) BETWEEN interval '5 minutes' AND interval '12 hours'
    AND mod(extract(epoch FROM (ends_at - starts_at)), 300) = 0),
  CONSTRAINT ck_appt_notes CHECK (notes IS NULL OR char_length(btrim(notes)) >= 1),
  CONSTRAINT fk_appt_patient FOREIGN KEY (patient_id, clinic_id)
    REFERENCES core.patients (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT fk_appt_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_appt_id_patient_clinic UNIQUE (id, patient_id, clinic_id),
  CONSTRAINT ex_appt_no_overlap EXCLUDE USING gist (
    clinic_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
    WHERE (status <> 'CANCELED' AND status <> 'NO_SHOW')
);
CREATE INDEX ix_appt_clinic_start ON core.appointments (clinic_id, starts_at, id);
CREATE INDEX ix_appt_patient ON core.appointments (clinic_id, patient_id, starts_at DESC, id DESC);
```
O índice de `created_by` é omitido de propósito (`users` nunca é apagado); registrar o comentário na migration. `ix_appt_patient` também serve de índice da FK composta do paciente. O intervalo `tstzrange(starts_at, ends_at)` é semiaberto `[)`.

### 2.3 Triggers
```sql
CREATE TRIGGER trg_appt_set_updated_at BEFORE UPDATE ON core.appointments
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
CREATE TRIGGER trg_appt_forbid_immutable BEFORE UPDATE ON core.appointments
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id','clinic_id','patient_id','created_by','created_at');

CREATE FUNCTION core.guard_appointment_patient() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_active     boolean;
  v_anonymized timestamptz;
BEGIN
  -- só quando o agendamento PASSA a ocupar horário
  IF NEW.status = 'CANCELED' OR NEW.status = 'NO_SHOW' THEN
    RETURN NEW;
  END IF;
  IF TG_OP = 'UPDATE' AND NOT (OLD.status = 'CANCELED' OR OLD.status = 'NO_SHOW') THEN
    RETURN NEW;
  END IF;
  SELECT p.active, p.anonymized_at INTO v_active, v_anonymized
    FROM core.patients p
   WHERE p.id = NEW.patient_id AND p.clinic_id = NEW.clinic_id
     FOR KEY SHARE;                 -- espera uma anonimização em curso (mesmo padrão da Fase 1)
  IF v_anonymized IS NOT NULL THEN
    RAISE EXCEPTION 'paciente anonimizado' USING ERRCODE = 'CR003';
  END IF;
  IF NOT v_active THEN
    RAISE EXCEPTION 'paciente inativo' USING ERRCODE = 'CR005';
  END IF;
  RETURN NEW;
END $$;
REVOKE EXECUTE ON FUNCTION core.guard_appointment_patient() FROM PUBLIC;

CREATE TRIGGER trg_appt_guard_patient BEFORE INSERT OR UPDATE ON core.appointments
  FOR EACH ROW EXECUTE FUNCTION core.guard_appointment_patient();
```
`FOR KEY SHARE` exige `UPDATE` em `patients` e passa pela política de `UPDATE` de `patients` (`T AND A`); ambos os papéis a satisfazem (mesmo precedente do trigger da Fase 1).

### 2.4 RLS e grants
```sql
ALTER TABLE core.appointments ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.appointments FORCE  ROW LEVEL SECURITY;

CREATE POLICY appt_sel ON core.appointments FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
     AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));

CREATE POLICY appt_ins ON core.appointments FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND created_by = core.current_user_id()
     AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN'
          OR ((SELECT core.current_app_role()) = 'RECEPTIONIST' AND status = 'SCHEDULED')));

CREATE POLICY appt_upd ON core.appointments FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id()
     AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN'
          OR ((SELECT core.current_app_role()) = 'RECEPTIONIST' AND (status = 'SCHEDULED' OR status = 'CONFIRMED'))))
  WITH CHECK (clinic_id = core.current_clinic_id()
     AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN'
          OR ((SELECT core.current_app_role()) = 'RECEPTIONIST'
              AND (status = 'SCHEDULED' OR status = 'CONFIRMED' OR status = 'CANCELED' OR status = 'NO_SHOW'))));

CREATE POLICY appt_backup_sel ON core.appointments FOR SELECT TO crm_backup USING (true);

GRANT SELECT, INSERT, UPDATE ON core.appointments TO crm_app;
GRANT SELECT                 ON core.appointments TO crm_backup;
```
Sem política de `DELETE`. As políticas e grants do `crm_definer` (`SELECT`; `UPDATE (notes, version)`) entram na F07.

## 3. Componentes
`V015`, `U015`, `tests/appointments_*.sql` (F08 consolida), entidade `Appointment` do backend (módulo `scheduling`).

## 4. Lógica

1. **Gravação:** o backend define `app.clinic_id`, `app.role`, `app.user_id`; `INSERT`/`UPDATE` passam pela RLS, pelos `CHECK`s, pelo trigger do paciente e, por fim, pela constraint de exclusão. Em disputa por horário, a segunda transação espera a primeira (`23P01` se esta confirmar).
2. **Cancelar/faltar:** muda o status; o predicado parcial tira a linha do índice de exclusão e o horário fica livre.
3. **Reativar** (`CANCELED`/`NO_SHOW` → ocupante): só dentista (política); o trigger confere o paciente (`CR005`/`CR003`); a constraint confere o horário (`23P01`).

**Consultas críticas e planos esperados**

| Consulta | Índice | Plano |
|---|---|---|
| Agenda: `clinic_id = ? AND starts_at >= :ini - interval '12 hours' AND starts_at < :fim AND ends_at > :ini ORDER BY starts_at, id` | `ix_appt_clinic_start` | `Index Scan`, sem *sort* |
| Conflito (pré-checagem): `clinic_id = ? AND status <> 'CANCELED' AND status <> 'NO_SHOW' AND tstzrange(starts_at, ends_at) && tstzrange(?, ?) AND id <> ?` | `ex_appt_no_overlap` | `Index Scan` no GiST |
| Histórico do paciente (keyset `(starts_at, id)`) | `ix_appt_patient` | `Index Scan` |

O recuo de 12 h na agenda vale pelo teto de duração (`ck_appt_duration`) e pega o atendimento que começou antes da janela e entra nela.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Sobreposição parcial, total ou contida | `23P01` |
| Fim = início do próximo | Passa |
| Mesmo horário em outra clínica | Passa |
| Duas transações no mesmo horário | A segunda espera; `23P01` se a primeira confirma |
| Reativar cancelado sobre horário ocupado | `23P01` |
| Fim ≤ início; duração < 5 min, > 12 h ou fora de múltiplo de 5; início com segundos; `notes` só com espaços; status fora da lista | `23514` |
| Paciente de outra clínica | `23503` |
| Paciente inativo / anonimizado (ocupante) | `CR005` / `CR003` |
| Recepção grava `IN_PROGRESS`/`COMPLETED` ou insere com status ≠ `SCHEDULED` | `42501` |
| Recepção altera linha `IN_PROGRESS`/`COMPLETED`/`CANCELED`/`NO_SHOW` | 0 linhas afetadas |
| Dentista grava transição fora de ordem | Passa (A4) |
| `app.role` ausente ou inválido | Tudo negado |
| `created_by` ≠ `app.user_id` | `42501` |
| `DELETE` por `crm_app` | `42501` |
| Alterar `clinic_id`, `patient_id`, `created_by`, `created_at`, `id` | `23514` (trigger de imutáveis) |

## 6. Segurança e desempenho
- RLS `FORCE`, uma política por operação; autoria verdadeira; sem `DELETE`; `notes` é texto claro (aviso no contrato).
- Agenda da semana ≤ 10 ms e conflito ≤ 5 ms com 20 mil agendamentos na clínica e 200 mil no banco, sem `Seq Scan` (teste 81, F08).
- Custo do `EXCLUDE`: uma busca no GiST por escrita; do trigger do paciente: um `SELECT ... FOR KEY SHARE` só quando o agendamento passa a ocupar horário.
