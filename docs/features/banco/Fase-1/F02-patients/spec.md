# Spec — F02 Pacientes (Fase 1)

Complementa `docs/global/banco/Fase-1/spec.md` (seções 2.3, 2.4, 2.6, 3 e 4.4).

## 1. Visão técnica
- **Feature:** tabela `core.patients`.
- **Stack:** SQL, migration (`V008`), `pg_trgm`.
- **Abordagem:** tabela de negócio padrão da Fase 0 com RLS por tenant e por papel; FK composta de `created_by`; grants por coluna para proteger `anonymized_at`.

## 2. Objetos (`V008__core_patients.sql`, como `crm_owner`)

### 2.1 Tabela (ilustrativo; colunas completas na spec global, 2.3)
```sql
CREATE TABLE core.patients (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  clinic_id uuid NOT NULL REFERENCES core.clinics(id) ON DELETE RESTRICT,
  name varchar(120) NOT NULL,
  cpf varchar(11),
  birth_date date NOT NULL,
  sex varchar(15) NOT NULL DEFAULT 'NOT_INFORMED',
  profession varchar(100),
  phone varchar(20) NOT NULL,
  email varchar(254),
  address varchar(255),
  insurance_name varchar(100),
  insurance_number varchar(40),
  acquaintance_name varchar(120),
  acquaintance_phone varchar(20),
  active boolean NOT NULL DEFAULT true,
  deactivated_at timestamptz,
  anonymized_at timestamptz,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CONSTRAINT ck_patients_name CHECK (char_length(btrim(name)) >= 2),
  CONSTRAINT ck_patients_cpf CHECK (cpf ~ '^[0-9]{11}$'),
  CONSTRAINT ck_patients_birth CHECK (birth_date >= DATE '1900-01-01'),
  CONSTRAINT ck_patients_sex CHECK (sex IN ('FEMALE','MALE','OTHER','NOT_INFORMED')),
  CONSTRAINT ck_patients_phone CHECK (phone ~ '^[0-9]{10,13}$'),
  CONSTRAINT ck_patients_email CHECK (email = lower(email)),
  CONSTRAINT ck_patients_insurance CHECK (insurance_number IS NULL OR insurance_name IS NOT NULL),
  CONSTRAINT ck_patients_acq CHECK ((acquaintance_name IS NULL) = (acquaintance_phone IS NULL)),
  CONSTRAINT ck_patients_acq_phone CHECK (acquaintance_phone ~ '^[0-9]{10,13}$'),
  CONSTRAINT ck_patients_deactivated CHECK ((active AND deactivated_at IS NULL) OR (NOT active AND deactivated_at IS NOT NULL)),
  CONSTRAINT ck_patients_anonymized CHECK (anonymized_at IS NULL OR NOT active),
  CONSTRAINT fk_patients_created_by FOREIGN KEY (created_by, clinic_id)
    REFERENCES core.users (id, clinic_id) ON DELETE RESTRICT,
  CONSTRAINT ux_patients_id_clinic UNIQUE (id, clinic_id)
);
```

### 2.2 Índices
`ux_patients_clinic_cpf (clinic_id, cpf) WHERE cpf IS NOT NULL`; `ix_patients_clinic_name (clinic_id, name, id)`; `ix_patients_name_trgm GIN (lower(name) extensions.gin_trgm_ops)`; `ix_patients_name_prefix (clinic_id, lower(name) text_pattern_ops)`; `ix_patients_created_by (created_by, clinic_id)` (criado na `V008`).

### 2.3 Triggers, RLS e grants
> **Forma implementada:** o papel é escrito `((SELECT core.current_app_role()) = 'DENTIST_ADMIN' OR (SELECT core.current_app_role()) = 'RECEPTIONIST')` (e não `IN (...)`), por estabilidade no dump/restore e para a função ser avaliada uma vez por consulta. O SQL abaixo é ilustrativo; vale `database/migrations/V008__core_patients.sql`.

```sql
CREATE TRIGGER trg_patients_set_updated_at BEFORE UPDATE ON core.patients
  FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();
CREATE TRIGGER trg_patients_forbid_immutable BEFORE UPDATE ON core.patients
  FOR EACH ROW EXECUTE FUNCTION core.forbid_immutable_columns('id','clinic_id','created_by','created_at');

ALTER TABLE core.patients ENABLE ROW LEVEL SECURITY;
ALTER TABLE core.patients FORCE  ROW LEVEL SECURITY;
CREATE POLICY patients_sel ON core.patients FOR SELECT TO crm_app
  USING (clinic_id = core.current_clinic_id()
         AND core.current_app_role() IN ('DENTIST_ADMIN','RECEPTIONIST'));
CREATE POLICY patients_ins ON core.patients FOR INSERT TO crm_app
  WITH CHECK (clinic_id = core.current_clinic_id()
              AND core.current_app_role() IN ('DENTIST_ADMIN','RECEPTIONIST')
              AND created_by = core.current_user_id());
CREATE POLICY patients_upd ON core.patients FOR UPDATE TO crm_app
  USING (clinic_id = core.current_clinic_id() AND core.current_app_role() IN ('DENTIST_ADMIN','RECEPTIONIST'))
  WITH CHECK (clinic_id = core.current_clinic_id() AND core.current_app_role() IN ('DENTIST_ADMIN','RECEPTIONIST'));
CREATE POLICY patients_backup_sel ON core.patients FOR SELECT TO crm_backup USING (true);

GRANT SELECT ON core.patients TO crm_app, crm_backup;
GRANT INSERT (id, clinic_id, name, cpf, birth_date, sex, profession, phone, email, address,
              insurance_name, insurance_number, acquaintance_name, acquaintance_phone,
              active, deactivated_at, created_by, created_at, updated_at, version)
  ON core.patients TO crm_app;
GRANT UPDATE (name, cpf, birth_date, sex, profession, phone, email, address, insurance_name,
              insurance_number, acquaintance_name, acquaintance_phone, active, deactivated_at,
              updated_at, version)
  ON core.patients TO crm_app;
```
Como `anonymized_at` fica fora dos grants de `INSERT`/`UPDATE`, só quem tem o privilégio total (`crm_definer`, F06) o grava. As colunas imutáveis (`id`, `clinic_id`, `created_by`, `created_at`) também ficam fora do `GRANT UPDATE`; o trigger de imutáveis é a segunda barreira.

## 3. Componentes
`V008`, `tests/patients_constraints.sql`, `tests/performance_search.sql` (F07), entidade `Patient` do backend.

## 4. Lógica
- **Cadastro:** `INSERT` com `id` gerado, `created_by = app.user_id`. Falha por CPF duplicado → `23505` em `ux_patients_clinic_cpf`.
- **Busca por nome (≥ 3 caracteres):** `lower(name) LIKE '%' || :termo || '%'` + `clinic_id = :tenant` (a RLS repete o filtro). **1–2 caracteres:** `lower(name) LIKE :termo || '%'`.
- **Listagem:** `WHERE (name, id) > (:n, :i) ORDER BY name, id LIMIT :n`.
- **Inativação:** `UPDATE SET active = false, deactivated_at = now()`.

### 4.1 Achado de desempenho: índices de `LIKE` sob RLS
Medido com `crm_app` e a RLS ligada (teste 55, `tests/performance_search.sql`), a busca por trecho (`lower(name) LIKE '%x%'`) e por prefixo **não usam** `ix_patients_name_trgm` nem `ix_patients_name_prefix`: o plano lê, pelo índice de `clinic_id`, as linhas da clínica e aplica o `LIKE` como filtro. Motivo: `textlike` não é `LEAKPROOF`, então o PostgreSQL não o aplica como condição de índice antes das políticas de RLS. Sem RLS (superusuário) o mesmo plano usa o trigram em ~0,07 ms. Efeito: o custo cresce com os pacientes **da clínica** (≈ 5 ms com 10 mil), não com os do banco; está dentro da meta (≤ 20 ms), mas os dois índices de nome só ajudariam consultas sem RLS. A busca por CPF e a listagem por keyset usam índice normalmente (operadores `LEAKPROOF`). Decisão pendente do projeto: manter os índices (como contratado) ou removê-los junto com `pg_trgm` para reduzir custo de escrita e superfície.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| CPF duplicado na clínica | `23505` |
| CPF igual em outra clínica | Permitido |
| `created_by` ≠ `app.user_id` | `WITH CHECK` falha (`42501`) |
| `created_by` de outra clínica | FK composta falha (`23503`) |
| Recepção altera paciente | Permitido |
| `app.role` ausente | 0 linhas; gravação falha |
| Backend tenta gravar `anonymized_at` | `42501` (sem grant por coluna) |
| `active = false` sem `deactivated_at` | `23514` |
| Telefone com máscara (`(11) 9...`) | `23514`; o backend normaliza para dígitos |

## 6. Segurança e desempenho
- Dados em texto claro (P2) sob RLS; `DELETE` inexistente.
- Busca por trecho ≤ 20 ms e CPF ≤ 5 ms com 10 mil pacientes na clínica (100 mil no banco).
- Listagem por keyset sem *sort*.
