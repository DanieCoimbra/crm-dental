# Spec — F01 Tipos de procedimento (Fase 3)

Complementa `docs/global/banco/Fase-3/spec.md` (seções 2.3, 2.4, 2.5, 3 e 7).

## 1. Visão técnica
- **Feature:** catálogo de procedimentos e FK do agendamento.
- **Stack:** PostgreSQL 16, migrations `V022` e `V023`, triggers genéricos da V002. Sem extensão nova.
- **Abordagem:** tabela de configuração com soft delete; a FK é composta com `clinic_id` para garantir a mesma clínica.

## 2. Objetos

### 2.1 `V022__core_procedure_types.sql` (como `crm_owner`)
```sql
CREATE TABLE core.procedure_types (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id uuid NOT NULL REFERENCES core.clinics (id) ON DELETE RESTRICT,
  name varchar(80) NOT NULL CONSTRAINT ck_ptype_name CHECK (char_length(btrim(name)) >= 2),
  base_price numeric(12,2) NOT NULL CONSTRAINT ck_ptype_price CHECK (base_price BETWEEN 0 AND 999999.99),
  default_duration_minutes smallint NOT NULL
    CONSTRAINT ck_ptype_duration CHECK (default_duration_minutes BETWEEN 5 AND 720 AND default_duration_minutes % 5 = 0),
  color varchar(7) NOT NULL CONSTRAINT ck_ptype_color CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
  active boolean NOT NULL DEFAULT true,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT fk_ptype_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_ptype_id_clinic UNIQUE (id, clinic_id)
);
CREATE UNIQUE INDEX ux_ptype_clinic_name ON core.procedure_types (clinic_id, lower(name)) WHERE active;

CREATE TRIGGER trg_ptype_set_updated_at BEFORE UPDATE ON core.procedure_types
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
CREATE TRIGGER trg_ptype_forbid_immutable BEFORE UPDATE ON core.procedure_types
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id','clinic_id','created_by','created_at');

ALTER TABLE core.procedure_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.procedure_types FORCE  ROW LEVEL SECURITY;
-- T = clinic_id = core.current_clinic_id(); papéis como OR de igualdades, funções de sessão como (SELECT ...)
CREATE POLICY ptype_sel ON core.procedure_types FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND ((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST'));
CREATE POLICY ptype_ins ON core.procedure_types FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id() AND created_by = core.current_user_id()
              AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');
CREATE POLICY ptype_upd ON core.procedure_types FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN')
  WITH CHECK (clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN');
CREATE POLICY ptype_backup_sel ON core.procedure_types FOR SELECT TO crm_backup USING (true);

GRANT SELECT, INSERT, UPDATE ON core.procedure_types TO crm_app;
GRANT SELECT                 ON core.procedure_types TO crm_backup;
```
O índice de `created_by` é omitido de propósito (`users` nunca é apagado); a migration registra a decisão no cabeçalho.

### 2.2 `V023__core_appointments_procedure_fk.sql`
```sql
ALTER TABLE core.appointments ADD CONSTRAINT fk_appt_procedure
  FOREIGN KEY (procedure_type_id, clinic_id) REFERENCES core.procedure_types (id, clinic_id) ON DELETE RESTRICT;
CREATE INDEX ix_appt_procedure ON core.appointments (clinic_id, procedure_type_id)
  WHERE procedure_type_id IS NOT NULL;
```
Sem `NOT VALID`: as linhas atuais têm o campo nulo (o backend da Fase 2 não o mapeia), então a validação é imediata. Com `MATCH SIMPLE`, a FK ignora linhas com `procedure_type_id` nulo.

## 3. Componentes
`V022`, `V023`, `U022`, `U023`, `tests/procedure_types.sql` (novo), `tests/phase3_isolation.sql` (parte), `tests/appointments_procedure_fk.sql` (novo). Entidade JPA `ProcedureType` (backend da Fase 3) e mapeamento de `procedure_type_id` em `Appointment`.

## 4. Lógica
1. `INSERT` por `DENTIST_ADMIN`: a RLS exige tenant, papel e `created_by = app.user_id`; `CHECK`s validam preço, duração e cor.
2. Nome duplicado entre ativos: `23505` em `ux_ptype_clinic_name`. Inativar (`active = false`) libera o nome.
3. `UPDATE` de preço **não** altera orçamentos existentes (o item guarda snapshot, F02).
4. `INSERT`/`UPDATE` de `appointments` com `procedure_type_id` não nulo: o RI check procura `(id, clinic_id)` em `procedure_types` sem passar pela RLS; outra clínica não casa.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Preço negativo ou acima de 999.999,99 | `23514` |
| Duração 7 ou 725 minutos | `23514` |
| Cor `red`, `#12345`, `#GGGGGG` | `23514` |
| Nome em branco ou de 1 caractere | `23514` |
| Nome ativo repetido, caixa diferente | `23505` |
| Nome inativo reutilizado | Passa |
| Mesmo nome em outra clínica | Passa |
| Recepção grava | `INSERT` → `42501`; `UPDATE` afeta 0 linhas |
| `app.role` ausente ou `'ADMIN'` | Tudo negado |
| `created_by` ≠ `app.user_id` | `42501` |
| `DELETE` | Negado (`42501`) |
| Alterar `clinic_id`, `id`, `created_by`, `created_at` | `23514` |
| Agendamento com procedimento de outra clínica | `23503` |
| Agendamento sem procedimento | Passa |

## 6. Segurança e desempenho
- Sem dado pessoal; RLS `FORCE`; `crm_backup` só leitura.
- Menor privilégio: sem `DELETE`, sem `GRANT ALL`.
- Volume: dezenas de linhas por clínica; o índice único parcial basta.
