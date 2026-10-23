# PRD — F02 Agendamentos (Fase 2)

**Derivação:** PRD global da Fase 2, US-201, US-202, US-203, US-204, decisões A1–A6 e A12, FR-1 a FR-7; spec global, seções 1.2 (S1, S10, S12), 2.3, 2.4, 2.6, 3.2, 4.1, 4.2, 4.5 e 4.6.
**Dependências externas:** F01 (`btree_gist` e `clinics.timezone`), Fase 1 (`patients`, `core.current_app_role()`, `core.current_user_id()`, `core.forbid_immutable_columns`, `core.set_updated_at`), Fase 0 (`clinics`, `users`). Consumida por F05 (FK da nota), F06 (auditoria), F07 (anonimização) e pelo backend (módulo `scheduling`).

## 1. Introdução

- **Problema:** a clínica precisa de uma agenda de um só dentista em que dois atendimentos nunca ocupem o mesmo horário, mesmo com recepção e dentista agendando ao mesmo tempo, e em que a recepção não mexa no que é do dentista (iniciar e finalizar atendimento).
- **Solução:** tabela `core.appointments` com `EXCLUDE` por clínica (não sobreposição no próprio banco), RLS por clínica e por papel, lista de status por `CHECK` e trigger que impede agendar paciente inativo ou anonimizado.
- **Metas:**
  1. Duas gravações concorrentes de horários sobrepostos na mesma clínica: exatamente uma passa; em clínicas diferentes, ambas.
  2. Recepção só grava `SCHEDULED`, `CONFIRMED`, `CANCELED`, `NO_SHOW` e só sobre linhas `SCHEDULED` ou `CONFIRMED`.
  3. Agenda da semana ≤ 10 ms e conflito ≤ 5 ms com 20 mil agendamentos por clínica, sem `Seq Scan`.
  4. `crm_app` sem `DELETE`; cancelar é status.

## 2. User stories

### US-001: Guardar agendamentos por clínica
**Descrição:** Como backend, quero armazenar os agendamentos de cada clínica.

**Critérios de aceite:**
- [ ] `core.appointments` conforme a spec (colunas, tipos, nulidade, defaults).
- [ ] `status` limitado por `CHECK` a `SCHEDULED`, `CONFIRMED`, `IN_PROGRESS`, `COMPLETED`, `NO_SHOW`, `CANCELED`; default `SCHEDULED`.
- [ ] `CHECK`s de intervalo: `ends_at > starts_at`, duração entre 5 min e 12 h e múltipla de 5 min, `starts_at` sem segundos.
- [ ] `notes` até 500 caracteres, texto claro; `procedure_type_id` nulo **sem FK** e sem índice.
- [ ] FK composta `(patient_id, clinic_id)` e `(created_by, clinic_id)`, `ON DELETE RESTRICT`.
- [ ] `clinic_id`, `id`, `patient_id`, `created_by` e `created_at` imutáveis; `updated_at` por trigger.

### US-002: Bloqueio de conflito no banco
**Descrição:** Como dentista, quero que o sistema nunca aceite dois atendimentos no mesmo horário.

**Critérios de aceite:**
- [ ] `ex_appt_no_overlap`: `EXCLUDE USING gist (clinic_id WITH =, tstzrange(starts_at, ends_at) WITH &&) WHERE (status <> 'CANCELED' AND status <> 'NO_SHOW')`; violação é `23P01`.
- [ ] Atendimentos consecutivos (fim = início) passam; clínicas diferentes não conflitam.
- [ ] Cancelar e faltar liberam o horário; reativar sobre horário ocupado falha com `23P01`.
- [ ] Gravações concorrentes: a segunda espera e falha com `23P01` se a primeira confirmar.

### US-003: Papéis na agenda
**Descrição:** Como titular, quero que a recepção confirme e cancele, mas só o dentista inicie e finalize atendimentos, mesmo que o backend erre.

**Critérios de aceite:**
- [ ] `SELECT` para `DENTIST_ADMIN` e `RECEPTIONIST`; `INSERT` por ambos, com `created_by = app.user_id`, e `RECEPTIONIST` só com `status = 'SCHEDULED'`.
- [ ] `UPDATE` da recepção: `USING` só linhas `SCHEDULED`/`CONFIRMED`; `WITH CHECK` só grava `SCHEDULED`, `CONFIRMED`, `CANCELED`, `NO_SHOW`.
- [ ] `DENTIST_ADMIN` sem restrição de status; o banco não valida a ordem das transições (decisão A4).
- [ ] `DELETE` sem política e sem grant.
- [ ] `app.role` ausente ou inválido: tudo negado.

### US-004: Paciente apto a ser agendado
**Descrição:** Como dentista, quero que paciente inativo ou anonimizado não receba agendamento novo.

**Critérios de aceite:**
- [ ] Trigger `BEFORE INSERT OR UPDATE`: quando o agendamento passa a ocupar horário (insert ocupante ou update de `CANCELED`/`NO_SHOW` para ocupante), paciente anonimizado → `CR003`; inativo → `CR005`.
- [ ] Em qualquer outro caso o trigger retorna sem consultar o paciente.
- [ ] Agendamentos existentes de paciente depois inativado permanecem legíveis.

### US-005: Consulta rápida da agenda
**Descrição:** Como recepcionista, quero ver o dia e a semana com rapidez.

**Critérios de aceite:**
- [ ] `ix_appt_clinic_start (clinic_id, starts_at, id)` e `ix_appt_patient (clinic_id, patient_id, starts_at DESC, id DESC)`.
- [ ] `EXPLAIN` documentado da consulta canônica (spec, seção 4) sem `Seq Scan` e sem *sort*.

## 3. Requisitos funcionais

- **FR-1:** Migration `V015__core_appointments.sql`: guarda de `btree_gist`, tabela, constraints, `EXCLUDE`, índices, triggers (`set_updated_at`, `forbid_immutable`, `guard_patient`), RLS `ENABLE` + `FORCE`, políticas e grants. A auditoria é da F06 e as políticas do `crm_definer` da F07.
- **FR-2:** Política `SELECT TO crm_backup USING (true)`.
- **FR-3:** `UNIQUE (id, patient_id, clinic_id)` (alvo da F05); sem `UNIQUE (id, clinic_id)`.
- **FR-4:** `crm_app` recebe `SELECT, INSERT, UPDATE`; nenhum `DELETE`, `TRUNCATE` nem `GRANT ALL`.
- **FR-5:** Predicado do `EXCLUDE` e das políticas escrito com `<>`/`OR` de igualdades (não `IN`), por causa do `pg_dump`.
- **FR-6:** Funções de sessão nas políticas como `(SELECT core.current_app_role())`.
- **FR-7:** Rollback `U015`.

## 4. Fora do escopo

- Máquina de transições no banco (backend); carimbos `confirmed_at`/`started_at`/`finished_at` (decisão S8).
- Bloqueio por horário comercial ou feriado (backend).
- Múltiplos profissionais, recorrência, lembretes, permuta de horários em uma transação.
- Tipos de procedimento e a FK de `procedure_type_id` (Fase 3).
- Auditoria (F06), anonimização (F07), vínculo da nota (F05).

## 5. Considerações técnicas e riscos

- A recepção não reativa cancelado nem faltoso; cria um agendamento novo (reativar é só do dentista).
- O `EXCLUDE` não é `DEFERRABLE`: a permuta de dois horários falha no passo intermediário e fica com o backend.
- **Risco:** transições só no backend (A4): um bug pode gravar `COMPLETED` → `SCHEDULED`; a RLS da recepção e a auditoria atenuam.
- **Risco:** `btree_gist` ausente no Supabase real (guarda da V015 aborta com mensagem clara).
- **Pergunta aberta:** nenhuma.
