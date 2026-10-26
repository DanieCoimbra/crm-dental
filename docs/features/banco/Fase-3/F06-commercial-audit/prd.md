# PRD — F06 Auditoria do comercial e financeiro (Fase 3)

**Derivação:** PRD global da Fase 3, US-307 e FR-13; spec global, seções 2.3 (`audit_log`), 2.6 e 5.3. Estende a trilha da Fase 1 (F05) e repete o padrão da Fase 2 (F06).
**Dependências externas:** F02 (`budgets`), F03 (`budget_documents`), F04 (`installments`), F05 (`payments`), Fase 1 F05 (`core.audit_log`, `core.write_audit()`), Fase 2 F06 (V020, padrão de `SET LOCAL ROLE crm_definer`). Consumida pelo backend (consulta de auditoria, só `DENTIST_ADMIN`).

## 1. Introdução

- **Problema:** orçamento, parcela, pagamento e PDF mexem com dinheiro e dado pessoal; sem trilha não há como saber quem criou, enviou, aprovou, cancelou ou pagou.
- **Solução:** ampliar o `CHECK` de `audit_log.table_name` e acrescentar o trigger de auditoria nas quatro tabelas, reaproveitando `core.write_audit()` sem alterá-la.
- **Metas:**
  1. 100% dos `INSERT/UPDATE/DELETE` em `budgets`, `installments`, `payments` e `budget_documents` geram exatamente 1 linha de auditoria, sem valores.
  2. Sem `app.user_id`, a gravação falha.
  3. `procedure_types` e `budget_items` não geram auditoria.

## 2. User stories

### US-001: Aceitar as quatro tabelas no log
**Descrição:** Como dentista, quero que as mudanças comerciais e financeiras apareçam na trilha de auditoria.

**Critérios de aceite:**
- [ ] `ck_audit_table` recriada aceitando as tabelas atuais (`patients`, `medical_histories`, `clinical_notes`, `patient_files`, `appointments`) **e** `budgets`, `installments`, `payments`, `budget_documents`.
- [ ] Linhas antigas do log permanecem intactas; nome fora da lista continua rejeitado (`23514`).

### US-002: Auditar cada alteração
**Descrição:** Como titular, quero saber quem registrou cada orçamento, parcela, pagamento e PDF.

**Critérios de aceite:**
- [ ] `AFTER INSERT OR UPDATE OR DELETE ... FOR EACH ROW` chama `core.write_audit()` em `budgets`, `installments`, `payments` e `budget_documents`.
- [ ] Cada operação gera 1 linha com o `table_name` certo, `record_id` e `action`; sem valor algum (nem valor monetário, status ou `storage_key`).
- [ ] Sem `app.user_id`, falha com `28000` e a transação reverte.
- [ ] Efeitos automáticos também são auditados: mudança de item gera `UPDATE` em `budgets` (total recalculado); pagamento que quita gera `UPDATE` em `installments`.
- [ ] `procedure_types` e `budget_items` não geram linhas.

## 3. Requisitos funcionais

- **FR-1:** Migration `V028__core_commercial_audit.sql`: recriar `ck_audit_table` e criar os quatro triggers (`trg_budgets_audit`, `trg_inst_audit`, `trg_pay_audit`, `trg_bdoc_audit`).
- **FR-2:** `core.write_audit()`, políticas e grants de `audit_log` **não mudam**.
- **FR-3:** Rollback `U028`: remove os triggers e restaura o `CHECK` anterior (só seguro se não houver linhas das tabelas novas no log; senão, restaurar do `pg_dump`).

## 4. Fora do escopo

- Registrar valores (monetários, status antigo/novo) ou auditar leitura.
- Auditar `procedure_types` e `budget_items`.
- Alterar `write_audit()`.
- Relatório ou tela de auditoria (backend).

## 5. Considerações técnicas e riscos

- O log não guarda o novo status nem o valor: diz **quem e quando**, não o quê.
- `write_audit()` pertence ao `crm_definer` e o `EXECUTE` foi revogado de `PUBLIC`; o `crm_owner` é membro `NOINHERIT`, então a V028 usa a concessão temporária por `SET LOCAL ROLE crm_definer` (padrão da V020) e a revoga ao fim, na mesma transação.
- Cada mudança de item gera uma linha `UPDATE` em `budgets`: a trilha de um orçamento em montagem fica longa (aceito).
- Anonimização (F07) também gera linhas (`UPDATE` de `budgets.notes`; marca de `budget_documents`).
- **Pergunta aberta:** nenhuma.
