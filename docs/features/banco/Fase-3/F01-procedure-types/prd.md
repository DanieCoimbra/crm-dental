# PRD — F01 Tipos de procedimento (Fase 3)

**Derivação:** PRD global da Fase 3, US-301 e US-302, decisões C2 e "Padrões adotados" (procedimento nunca é apagado; duração padrão e cor; FK de `appointments.procedure_type_id`), FR-1 e FR-12; spec global, seções 2.3 (`procedure_types`, `appointments`), 2.5 (V022, V023), 3 e S1.
**Dependências externas:** Fase 0 (`clinics`, `users`, funções de sessão, triggers genéricos da V002), Fase 2 F02 (`core.appointments`, com `procedure_type_id` nulo e sem FK). Consumida por F02 (itens do orçamento) e pelo backend (catálogo, agenda).

## 1. Introdução

- **Problema:** o dentista precisa cadastrar o que cobra (preço-base, duração, cor na agenda) e ligar o agendamento ao procedimento; hoje `appointments.procedure_type_id` é um `uuid` solto, sem integridade.
- **Solução:** a tabela `core.procedure_types` (soft delete por `active`, nome único entre os ativos) e a FK composta aditiva de `appointments.procedure_type_id`, com RLS por clínica e por papel.
- **Metas:**
  1. Sob `app.clinic_id = A`, 0 linhas de B em `procedure_types`; procedimento de outra clínica em `appointments` falha (`23503`).
  2. A recepção lê e não grava; só `DENTIST_ADMIN` grava; ninguém apaga.
  3. A migration da FK não reescreve linhas e os testes 59–85 da Fase 2 seguem verdes.

## 2. User stories

### US-001: Cadastro de procedimentos
**Descrição:** Como dentista, quero cadastrar procedimentos com preço-base, duração e cor para usá-los na agenda e no orçamento.

**Critérios de aceite:**
- [ ] `core.procedure_types` conforme a spec global (2.3): `name`, `base_price`, `default_duration_minutes`, `color`, `active`, `created_by`, `created_at`, `updated_at`, `version`; `UNIQUE (id, clinic_id)`.
- [ ] `CHECK`s: nome ≥ 2 caracteres úteis, preço entre 0 e 999.999,99, duração múltipla de 5 entre 5 e 720, cor `#RRGGBB`; violação é `23514`.
- [ ] Índice único parcial `(clinic_id, lower(name)) WHERE active`: nome repetido entre ativos falha (`23505`); nome inativo pode ser reutilizado; o mesmo nome em outra clínica passa.
- [ ] `clinic_id`, `id`, `created_by` e `created_at` imutáveis (trigger); `updated_at` por trigger.

### US-002: Papéis e isolamento
**Descrição:** Como titular, quero que só o dentista altere o catálogo e que a recepção apenas o consulte.

**Critérios de aceite:**
- [ ] RLS `ENABLE` + `FORCE`; `SELECT` para `DENTIST_ADMIN` e `RECEPTIONIST`; `INSERT` (com `created_by = app.user_id`) e `UPDATE` só `DENTIST_ADMIN`; sem política de `DELETE` e sem grant de `DELETE`.
- [ ] Política `SELECT TO crm_backup USING (true)`.
- [ ] Com `app.role` ausente ou inválido, tudo negado; sem `app.clinic_id`, 0 linhas.

### US-003: Procedimento no agendamento
**Descrição:** Como dentista, quero que o agendamento aponte para um procedimento da minha clínica.

**Critérios de aceite:**
- [ ] FK composta `fk_appt_procedure (procedure_type_id, clinic_id) → procedure_types (id, clinic_id) ON DELETE RESTRICT`; com `procedure_type_id` nulo, nada é verificado.
- [ ] Índice parcial `ix_appt_procedure (clinic_id, procedure_type_id) WHERE procedure_type_id IS NOT NULL`.
- [ ] Procedimento de outra clínica falha com `23503`.
- [ ] Migration aditiva: nenhuma linha reescrita; a política de `UPDATE` da recepção e a regra de conflito da Fase 2 não mudam.

## 3. Requisitos funcionais

- **FR-1:** Migration `V022__core_procedure_types.sql` (tabela, índices, triggers, RLS, grants) e `V023__core_appointments_procedure_fk.sql` (FK e índice).
- **FR-2:** `crm_app` com `SELECT`, `INSERT`, `UPDATE` em `procedure_types`; sem `DELETE`; `crm_backup` com `SELECT`.
- **FR-3:** Rollback `U022` (remove a tabela; só seguro antes de haver dados) e `U023` (remove a FK e o índice, sempre seguro).

## 4. Fora do escopo

- Seed de procedimentos padrão (a clínica cadastra os seus).
- Preço por convênio, tabelas de preço múltiplas, comissão.
- Impedir itens ou agendamentos de procedimento inativo (regra do backend).
- Duração do agendamento preenchida pelo banco (o backend lê `default_duration_minutes` e propõe).
- Auditoria de `procedure_types` (configuração; PRD global US-307).

## 5. Considerações técnicas e riscos

- A busca por `lower(name)` sob RLS não usa índice (função não *leakproof*); a lista de procedimentos de uma clínica tem dezenas de linhas, então o custo é irrelevante.
- O RI check da FK ignora a RLS (dono da tabela referenciada); o isolamento vem do `clinic_id` na chave composta.
- Inativar um procedimento não afeta orçamentos nem agendamentos existentes (FK `RESTRICT`, sem `DELETE`).
- **Pergunta aberta:** nenhuma.
