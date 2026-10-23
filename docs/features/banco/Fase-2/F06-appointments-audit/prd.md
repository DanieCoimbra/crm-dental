# PRD — F06 Auditoria dos agendamentos (Fase 2)

**Derivação:** PRD global da Fase 2, US-209 (primeira parte), decisão A10, FR-10 (parte); spec global, seções 2.3 (`audit_log`), 2.6 e 4. Estende a trilha da Fase 1 (F05).
**Dependências externas:** F02 (`appointments`), Fase 1 F05 (`core.audit_log`, `core.write_audit()`), Fase 1 F01 (`current_user_id()`). Consumida pelo backend (consulta de auditoria, só `DENTIST_ADMIN`).

## 1. Introdução

- **Problema:** agendamentos revelam quem é paciente da clínica e quem os alterou; a trilha de auditoria só cobre as quatro tabelas clínicas da Fase 1.
- **Solução:** ampliar o `CHECK` de `audit_log.table_name` e acrescentar o trigger de auditoria em `appointments`, reaproveitando `core.write_audit()` sem alterá-la.
- **Metas:**
  1. 100% dos `INSERT/UPDATE/DELETE` em `appointments` geram exatamente 1 linha de auditoria, sem valores.
  2. Sem `app.user_id`, a gravação do agendamento falha.
  3. `business_hours`, `clinic_holidays` e `holidays` não geram auditoria.

## 2. User stories

### US-001: Aceitar `appointments` no log
**Descrição:** Como dentista, quero que as mudanças da agenda apareçam na trilha de auditoria.

**Critérios de aceite:**
- [ ] `ck_audit_table` recriada aceitando `patients`, `medical_histories`, `clinical_notes`, `patient_files` **e** `appointments`.
- [ ] Linhas antigas do log permanecem intactas (a recriação não dispara os triggers de `UPDATE`/`DELETE`).
- [ ] Nome de tabela fora da lista continua rejeitado.

### US-002: Auditar cada alteração de agendamento
**Descrição:** Como titular, quero saber quem criou ou alterou um agendamento.

**Critérios de aceite:**
- [ ] `trg_appt_audit AFTER INSERT OR UPDATE OR DELETE ... FOR EACH ROW` chama `core.write_audit()` (função da Fase 1, sem alteração).
- [ ] Cada operação gera 1 linha com `table_name = 'appointments'`, `record_id` do agendamento e `action`.
- [ ] Sem `app.user_id`, falha com `28000` e a transação inteira reverte.
- [ ] Nenhuma coluna de valor: status, horário e `notes` nunca entram no log.

## 3. Requisitos funcionais

- **FR-1:** Migration `V020__core_appointments_audit.sql`: recriar `ck_audit_table` e criar `trg_appt_audit`.
- **FR-2:** `core.write_audit()`, políticas e grants de `audit_log` **não mudam**.
- **FR-3:** Rollback `U020`: remove o trigger e restaura o `CHECK` anterior (só seguro se não houver linhas `appointments` no log; senão, restaurar do `pg_dump`).

## 4. Fora do escopo

- Registrar o novo status ou valores antigo/novo (o log não tem colunas de valor).
- Auditar leitura da agenda.
- Auditar `business_hours`, `clinic_holidays` e `holidays`.
- Alterar `write_audit()`.

## 5. Considerações técnicas e riscos

- Como o log não guarda o novo status, ele diz **quem e quando**, mas não "quem finalizou o atendimento às 10h". Se o produto precisar da hora real de início/fim, entram colunas na F02 (decisão S8 da spec global).
- O `crm_owner` precisa conseguir `CREATE TRIGGER ... EXECUTE FUNCTION core.write_audit()`; a função pertence a `crm_definer` com `EXECUTE` revogado de `PUBLIC`. Se o privilégio não for herdado por pertencimento, a V020 usa `GRANT EXECUTE` temporário ao `crm_owner`, revogado ao fim (spec global, seção 10, item 7).
- **Pergunta aberta:** nenhuma.
