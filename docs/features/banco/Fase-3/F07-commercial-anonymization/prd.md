# PRD — F07 Anonimização com histórico comercial e financeiro (Fase 3)

**Derivação:** PRD global da Fase 3, US-309, decisão C4, FR-14; spec global, seções 3.1 (`crm_definer`), 4.7 e S15. Estende a anonimização da Fase 1 (F06) e da Fase 2 (F07).
**Dependências externas:** F02 (`budgets`), F03 (`budget_documents`), F04 (`installments`), F06 (auditoria), Fase 1 F06 (`core.anonymize_patient` da V013), Fase 2 F07 (V021, padrão de substituição pelo `crm_definer`). Consumida pelo backend (anonimização, limpeza dos objetos do Storage).

## 1. Introdução

- **Problema:** o paciente pode pedir anonimização (LGPD), mas a clínica precisa manter o histórico contábil; o PDF do orçamento traz o nome do paciente e o texto livre das notas pode citar a pessoa.
- **Solução:** `core.anonymize_patient` passa a recusar com `CR006` se houver parcela em aberto, zerar `budgets.notes`, marcar os PDFs como removidos e **manter** orçamentos, itens, parcelas e pagamentos.
- **Metas:**
  1. Anonimização com parcela `OPEN` falha com `CR006`, sem alterar nada.
  2. Depois de anonimizar: 0 `budgets.notes` com valor; todos os `budget_documents` do paciente com `deleted_at`; financeiro intacto.
  3. Nenhum privilégio novo além do mínimo escopado ao tenant para o `crm_definer`.

## 2. User stories

### US-001: Bloquear com saldo em aberto
**Descrição:** Como dentista, quero ser impedido de anonimizar um paciente que ainda deve parcelas.

**Critérios de aceite:**
- [ ] Se existir parcela `OPEN` do paciente na clínica, a função falha com `CR006` ("quite ou cancele as parcelas em aberto antes de anonimizar"); a consulta usa `ix_inst_patient`.
- [ ] Parcelas só `PAID` ou `CANCELED` passam; orçamento `DRAFT` ou `SENT` sem parcelas passa (não é saldo em aberto).
- [ ] Precedência dos erros: `CR002` (retenção) → `CR004` (agendamento futuro) → `CR006`.

### US-002: Limpar texto livre e PDFs
**Descrição:** Como titular, quero que nada com o nome ou a observação do paciente sobreviva à anonimização.

**Critérios de aceite:**
- [ ] `UPDATE core.budgets SET notes = NULL, version = version + 1 WHERE patient_id = ? AND notes IS NOT NULL`, mesmo em orçamento congelado (exceção do `guard_budget_update` pela flag `core.anonymizing`).
- [ ] `UPDATE core.budget_documents SET deleted_at = COALESCE(deleted_at, now())` do paciente; as `storage_key` (uuid) ficam para o backend limpar o Storage.
- [ ] Orçamentos (número, total, status, itens), parcelas e pagamentos **permanecem**.
- [ ] Cada orçamento limpo gera 1 linha `UPDATE` no `audit_log`, mais o `ANONYMIZE`.

### US-003: Privilégios mínimos do definer
**Descrição:** Como responsável pela segurança, quero que a função só alcance o que precisa, só no tenant da sessão.

**Critérios de aceite:**
- [ ] `crm_definer`: `SELECT` e `UPDATE (notes, version)` em `budgets`; `SELECT` em `installments`; `SELECT` e `UPDATE (deleted_at)` em `budget_documents`.
- [ ] Políticas `FOR SELECT`/`FOR UPDATE TO crm_definer` com `clinic_id = core.current_clinic_id()`; nenhum `USING (true)`.
- [ ] A função continua `SECURITY DEFINER`, `search_path` fixo, sem SQL dinâmico; dono `crm_definer`; `EXECUTE` do `crm_app` mantido.

## 3. Requisitos funcionais

- **FR-1:** Migration `V029__core_commercial_anonymization.sql`: grants e políticas do definer e `CREATE OR REPLACE FUNCTION core.anonymize_patient` (corpo da V021 com os trechos novos).
- **FR-2:** `U029` restaura a função da V021 e remove as políticas e grants novos.
- **FR-3:** Retenção de 20 anos **não** considera orçamento nem financeiro.
- **FR-4:** Códigos de erro anteriores (`42501`, `28000`, `P0002`, `CR001` a `CR004`) e o `ANONYMIZE` no `audit_log` permanecem.

## 4. Fora do escopo

- Apagar orçamentos, parcelas ou pagamentos (retenção contábil).
- Apagar os objetos do Storage (backend, depois do sucesso).
- Limite de prazo contábil para a retenção do financeiro.
- Reverter uma anonimização.
- Anonimização de `procedure_types` (não há dado pessoal).

## 5. Considerações técnicas e riscos

- `CREATE OR REPLACE` pelo `crm_owner` (membro `NOINHERIT` do `crm_definer`) exige o padrão da V021: `GRANT CREATE ON SCHEMA core TO crm_definer`, `SET LOCAL ROLE crm_definer`, troca, `RESET ROLE`, `REVOKE CREATE`.
- A limpeza de `budgets.notes` roda **depois** de ligar a flag `core.anonymizing`; fora dela o trigger de congelamento negaria (`CR007`).
- Corrida com pagamento/estorno: o guard do pagamento trava a parcela e o paciente (`FOR KEY SHARE`), e a anonimização trava o paciente (`FOR UPDATE`); se um estorno reabrir uma parcela durante a anonimização, a anonimização espera e então vê `OPEN` → `CR006`.
- Se a limpeza do Storage falhar depois do sucesso, ficam objetos órfãos com nome do paciente; o backend concilia pelas linhas `deleted_at IS NOT NULL`.
- **Pergunta aberta:** nenhuma.
