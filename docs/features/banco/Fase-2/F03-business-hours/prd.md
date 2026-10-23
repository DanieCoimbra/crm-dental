# PRD — F03 Horário comercial (Fase 2)

**Derivação:** PRD global da Fase 2, US-206, decisão A8, FR-1, FR-2 e FR-5; spec global, seções 1.2 (S2, S3), 2.3 (`business_hours`), 2.6 e 3.2.
**Dependências externas:** F01 (`clinics.timezone`: os horários são em hora local da clínica), Fase 1 (`core.current_app_role()`, `core.current_user_id()`, `core.forbid_immutable_columns`, `core.set_updated_at`), Fase 0 (`clinics`, `users`). Consumida pelo backend (aviso/bloqueio de agendamento fora do horário). Independente de F02.

## 1. Introdução

- **Problema:** a clínica precisa informar em que dias e horários atende, com intervalo de almoço, para o backend avisar quando o agendamento cai fora do expediente.
- **Solução:** tabela `core.business_hours` com **uma linha por clínica e dia** (expediente e almoço na mesma linha, `CHECK`s atômicos), legível pelos dois papéis e gravável só pelo dentista.
- **Metas:**
  1. Expediente e almoço inconsistentes nunca são gravados (`CHECK`), sem trigger entre linhas.
  2. 0 gravações possíveis para `RECEPTIONIST`.
  3. `crm_app` sem `DELETE`: dia fechado é `is_open = false`.

## 2. User stories

### US-001: Configurar o expediente por dia da semana
**Descrição:** Como dentista, quero definir abertura, fechamento e almoço de cada dia da semana.

**Critérios de aceite:**
- [ ] `core.business_hours` com `weekday` (1 = segunda … 7 = domingo), `is_open`, `opens_at`, `closes_at`, `lunch_starts_at`, `lunch_ends_at` (`time(0)`, hora local da clínica).
- [ ] `UNIQUE (clinic_id, weekday)`: uma linha por dia.
- [ ] `ck_bh_open`: aberto exige `opens_at < closes_at`; fechado exige os quatro horários nulos.
- [ ] `ck_bh_lunch`: almoço nulo ou completo, com `opens_at < lunch_starts_at < lunch_ends_at < closes_at`.
- [ ] Dia sem linha significa "não configurado" (o backend o trata como fechado).

### US-002: Leitura pelos dois papéis, escrita só pelo dentista
**Descrição:** Como titular, quero que a recepção veja o horário, mas não o altere.

**Critérios de aceite:**
- [ ] `SELECT` para `DENTIST_ADMIN` e `RECEPTIONIST`; `INSERT`/`UPDATE` só `DENTIST_ADMIN` (com `created_by = app.user_id` no `INSERT`).
- [ ] Sem política de `DELETE` e sem grant de `DELETE` ao `crm_app`.
- [ ] `clinic_id`, `weekday`, `id`, `created_by`, `created_at` imutáveis.
- [ ] `app.role` ausente ou inválido: tudo negado.

### US-003: O banco não bloqueia agendamento fora do horário
**Descrição:** Como dentista, quero poder encaixar um paciente fora do expediente.

**Critérios de aceite:**
- [ ] Nenhuma constraint ou trigger relaciona `appointments` a `business_hours`.
- [ ] O contrato diz que o aviso/bloqueio é do backend.

## 3. Requisitos funcionais

- **FR-1:** Migration `V016__core_business_hours.sql`: tabela, `UNIQUE`, `CHECK`s, triggers (`set_updated_at`, `forbid_immutable`), RLS `ENABLE` + `FORCE`, políticas e grants.
- **FR-2:** Política `SELECT TO crm_backup USING (true)`.
- **FR-3:** `crm_app` recebe `SELECT, INSERT, UPDATE`; nenhum `DELETE`.
- **FR-4:** Sem auditoria nesta tabela (configuração sem dado pessoal).
- **FR-5:** Rollback `U016`.

## 4. Fora do escopo

- Três ou mais faixas por dia, horários que atravessam a meia-noite.
- Bloqueio de agendamento fora do horário no banco; cálculo de horário livre.
- Exceções por data (use feriados, F04).
- Conversão para `timestamptz` (backend, com `clinics.timezone`).

## 5. Considerações técnicas e riscos

- `time(0)` aceita `'24:00:00'`; fechar à meia-noite é permitido.
- Trocar o horário de um dia é `UPDATE` da mesma linha; não há estado intermediário inválido.
- **Risco:** o backend precisa criar as linhas na primeira configuração (o cadastro da clínica não semeia horários).
- **Pergunta aberta:** nenhuma.
