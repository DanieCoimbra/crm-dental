# PRD — F07 Anonimização e agendamentos (Fase 2)

**Derivação:** PRD global da Fase 2, US-209 (segunda parte), decisão A10, FR-10 (parte), spec global, seções 1.2 (S7), 4.4 e 6.3. Altera a função `core.anonymize_patient` da Fase 1 (F06).
**Dependências externas:** F02 (`appointments`), F06 (auditoria dos agendamentos), Fase 1 F06 (`core.anonymize_patient`, `core.guard_anonymized_patient`, políticas do `crm_definer`), Fase 1 F05 (`core.audit_log`). Consumida pelo backend (módulo `patient`, endpoint de anonimização).

## 1. Introdução

- **Problema:** `appointments.notes` é texto livre e pode citar nome ou queixa do paciente; e um agendamento futuro em aberto deixaria um horário ocupado por "Paciente anonimizado".
- **Solução:** a anonimização passa a (1) recusar paciente com agendamento futuro em aberto (`CR004`) e (2) zerar `notes` dos agendamentos do paciente, mantendo horário e status.
- **Metas:**
  1. Depois da anonimização, nenhum `appointments.notes` do paciente mantém valor.
  2. Horário, status e `patient_id` dos agendamentos permanecem.
  3. Paciente com agendamento futuro `SCHEDULED`, `CONFIRMED` ou `IN_PROGRESS` não é anonimizado.
  4. A retenção de 20 anos continua ignorando agendamentos.

## 2. User stories

### US-001: Recusar anonimização com agendamento futuro em aberto
**Descrição:** Como dentista, quero ser avisado de que preciso cancelar os agendamentos futuros antes de anonimizar o paciente.

**Critérios de aceite:**
- [ ] Depois da checagem de retenção, existindo agendamento do paciente com `status` `SCHEDULED`, `CONFIRMED` ou `IN_PROGRESS` e `ends_at > now()`, a função falha com **`CR004`**.
- [ ] Futuros `CANCELED`/`NO_SHOW` e agendamentos passados não bloqueiam.
- [ ] A recusa não altera nada (a transação reverte).

### US-002: Limpar o texto livre da agenda
**Descrição:** Como titular, quero que a anonimização apague o texto livre dos agendamentos do paciente.

**Critérios de aceite:**
- [ ] Antes do `UPDATE` de `patients`, `UPDATE core.appointments SET notes = NULL, version = version + 1 WHERE patient_id = p AND notes IS NOT NULL`.
- [ ] `starts_at`, `ends_at`, `status` e `patient_id` não mudam.
- [ ] Cada agendamento limpo gera 1 linha `appointments`/`UPDATE` no `audit_log` (além do `ANONYMIZE` do paciente).

### US-003: Privilégio mínimo do definer
**Descrição:** Como responsável pela segurança, quero que a função só consiga o necessário na agenda.

**Critérios de aceite:**
- [ ] `crm_definer` com `SELECT` e `UPDATE (notes, version)` em `appointments` e **nada mais**.
- [ ] Políticas do definer escopadas ao tenant: `SELECT` e `UPDATE` com `clinic_id = core.current_clinic_id()`; nunca `USING (true)`.
- [ ] A função continua `SECURITY DEFINER`, com `search_path` fixo e sem SQL dinâmico; `EXECUTE` só ao `crm_app` (preservado por `CREATE OR REPLACE`).

## 3. Requisitos funcionais

- **FR-1:** Migration `V021__core_appointments_anonymization.sql`: grants e políticas do definer, `CREATE OR REPLACE FUNCTION core.anonymize_patient(uuid)`.
- **FR-2:** Códigos de erro anteriores inalterados (`42501`, `28000`, `P0002`, `CR001`, `CR002`, `CR003`); novo `CR004`. (`CR005` pertence ao trigger de agendamento, F02.)
- **FR-3:** A retenção do prontuário não considera agendamentos.
- **FR-4:** Rollback `U021` restaura a função da V013 (sem `CR004` e sem limpeza de `notes`) e remove os grants e as políticas do definer em `appointments`.

## 4. Fora do escopo

- Cancelar automaticamente os agendamentos futuros (o backend os cancela antes, ao inativar o paciente).
- Apagar agendamentos do paciente (mantidos; não guardam dado pessoal).
- Anonimizar `audit_log` (guarda só identificadores).
- Alterar a ordem ou o comportamento dos demais passos da anonimização.

## 5. Considerações técnicas e riscos

- Um agendamento passado ainda `SCHEDULED` (esquecido) não bloqueia; o backend deve fechar a agenda diária.
- `version` entra no grant do definer para o `@Version` do JPA detectar a mudança; `updated_at` é atualizado por trigger (sem exigir grant).
- Se o projeto real exigir `CREATE` no schema para `CREATE OR REPLACE`, repetir o `GRANT CREATE ON SCHEMA core TO crm_definer` temporário da V013.
- **Pergunta aberta:** nenhuma (o `CR004` foi aprovado como decisão S7 da spec global).
