# PRD — F04 Feriados (Fase 2)

**Derivação:** PRD global da Fase 2, US-207, decisão A9, FR-1, FR-6 e FR-11; spec global, seções 1.2 (S5, S6, S9), 2.3 (`holidays`, `clinic_holidays`), 3.2 e 5.4.
**Dependências externas:** Fase 1 (`core.current_app_role()`, `core.current_user_id()`, `core.forbid_immutable_columns`, `core.set_updated_at`), Fase 0 (`clinics`, `users`). Consumida pelo backend (aviso/bloqueio de agendamento em feriado). Independente de F02.

## 1. Introdução

- **Problema:** o backend precisa saber quais dias são feriado, nacional ou da clínica, para avisar ao agendar.
- **Solução:** `core.holidays` global (feriados nacionais por lei, seed 2026–2030, só leitura para a aplicação) e `core.clinic_holidays` por clínica (leitura pelos dois papéis, escrita só pelo dentista).
- **Metas:**
  1. 50 feriados nacionais (10 por ano, 2026–2030) semeados de forma idempotente.
  2. `crm_app` nunca escreve em `holidays`.
  3. 0 linhas de outra clínica em `clinic_holidays`; recepção só lê.

## 2. User stories

### US-001: Feriados nacionais já cadastrados
**Descrição:** Como dentista, quero os feriados nacionais prontos, sem cadastrar.

**Critérios de aceite:**
- [ ] `core.holidays` (`holiday_date date` PK, `name varchar(80)`), sem `clinic_id`.
- [ ] `ENABLE` + `FORCE`; política `SELECT TO crm_app USING (true)` e `SELECT TO crm_backup USING (true)`; **sem** política de escrita para `crm_app`; uma política por operação para `crm_owner` (S6).
- [ ] `crm_app` com só `SELECT` na tabela.

### US-002: Seed idempotente
**Descrição:** Como responsável pelo banco, quero os feriados de 2026 a 2030 em migration separada do schema.

**Critérios de aceite:**
- [ ] `V018` insere 50 linhas (nove feriados fixos por ano mais a Sexta-feira Santa), com `ON CONFLICT (holiday_date) DO NOTHING`.
- [ ] Sexta-feira Santa: 2026-04-03, 2027-03-26, 2028-04-14, 2029-03-30, 2030-04-19.
- [ ] Rodar a migration duas vezes mantém 50 linhas.
- [ ] Carnaval e Corpus Christi **não** entram (decisão S9).

### US-003: Feriados da própria clínica
**Descrição:** Como dentista, quero cadastrar e remover feriados da minha clínica (municipais, recesso).

**Critérios de aceite:**
- [ ] `core.clinic_holidays` (`holiday_date`, `name`, `created_by`, `version`) com `UNIQUE (clinic_id, holiday_date)`.
- [ ] `SELECT` para os dois papéis; `INSERT`, `UPDATE`, `DELETE` só `DENTIST_ADMIN`.
- [ ] `crm_app` com `DELETE` **apenas** nesta tabela (exceção S5).
- [ ] Pode repetir uma data nacional; a mesma data em outra clínica é permitida.

## 3. Requisitos funcionais

- **FR-1:** Migration `V017__core_holidays.sql` (as duas tabelas, índices, triggers, RLS, políticas do `crm_owner` em `holidays`, grants) e `V018__core_holidays_seed_2026_2030.sql` (seed, como `crm_owner`).
- **FR-2:** Política `SELECT TO crm_backup USING (true)` nas duas tabelas.
- **FR-3:** Triggers `set_updated_at` e `forbid_immutable` em `clinic_holidays`.
- **FR-4:** Sem auditoria nas duas tabelas.
- **FR-5:** Rollbacks `U017` e `U018`.

## 4. Fora do escopo

- Feriados móveis não nacionais (Carnaval, Corpus Christi) no seed; municipais e estaduais prontos.
- Bloqueio de agendamento em feriado no banco (backend decide).
- Seed de anos depois de 2030 (nova migration).
- Cálculo de dias úteis.

## 5. Considerações técnicas e riscos

- Sob `FORCE`, o `crm_owner` precisa de políticas próprias em `holidays` para o seed gravar; elas valem só para migrations (a aplicação não usa o `crm_owner`). O teste de catálogo (`catalog_rls_pattern.sql`) deve aceitá-las.
- **Risco:** o seed termina em 2030; criar nova migration antes disso. Lei nova de feriado nacional também exige migration.
- **Pergunta aberta:** nenhuma.
