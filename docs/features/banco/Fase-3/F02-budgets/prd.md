# PRD — F02 Orçamento e itens (Fase 3)

**Derivação:** PRD global da Fase 3, US-303, decisões C1 e C2, "Padrões adotados" (snapshot de preço, numeração, status, desconto nos dois níveis, guarda do paciente), FR-3 a FR-6, FR-10, FR-15; spec global, seções 2.3 (`budgets`, `budget_items`), 4.1 a 4.4, S2 a S9 e S16.
**Dependências externas:** F01 (`procedure_types`), Fase 1 (`patients`, funções de sessão), Fase 2 (`appointments` e seu `UNIQUE (id, patient_id, clinic_id)`), Fase 0 (V002). Consumida por F03 (PDF), F04 (parcelas), F06 (auditoria) e F07 (anonimização).

## 1. Introdução

- **Problema:** o orçamento é o ponto de partida do dinheiro; um total errado, um item editado depois do envio ou dois orçamentos com o mesmo número comprometem tudo que vem depois (PDF, parcelas, pagamentos).
- **Solução:** `budgets` e `budget_items` em `core`, com total calculado pelo banco, itens que só mudam em `DRAFT`, snapshot de preço e descrição copiados do catálogo, status com estados terminais, carimbos e número gerados pelo banco, e RLS por clínica e por papel.
- **Metas:**
  1. Em qualquer sequência de operações, `total_amount = Σ line_total − discount_amount`; o cliente nunca grava o total.
  2. Orçamento fora de `DRAFT` não aceita mudança de itens nem de conteúdo (`CR007`); `APPROVED` e `REJECTED` são terminais (`CR008`).
  3. Dois orçamentos simultâneos na mesma clínica recebem números distintos e sequenciais.
  4. Recepção lê e não grava; 0 linhas de outra clínica.

## 2. User stories

### US-001: Cabeçalho do orçamento
**Descrição:** Como dentista, quero criar um orçamento para um paciente, opcionalmente ligado ao atendimento, e enviá-lo ou decidi-lo.

**Critérios de aceite:**
- [ ] `core.budgets` conforme a spec global (2.3): `number`, `patient_id`, `appointment_id` (nulo), `status`, `discount_amount`, `total_amount`, `valid_until`, `notes`, `sent_at`, `decided_at`, `created_by` e colunas de controle; `UNIQUE (clinic_id, number)`, `UNIQUE (id, clinic_id)`, `UNIQUE (id, patient_id, clinic_id)`.
- [ ] Nasce `DRAFT` (qualquer outro status no `INSERT` → `CR008`); desconto no `INSERT` falha (`23514`, `ck_budgets_total`).
- [ ] `number` atribuído pelo banco (lock consultivo da clínica + `max + 1`); valor enviado pelo cliente é ignorado.
- [ ] `sent_at` e `decided_at` carimbados pelo banco na transição; valores do cliente ignorados; `ck_budgets_stamps` rejeita estados incoerentes.
- [ ] `patient_id` e `number` sempre imutáveis; `appointment_id`, `discount_amount`, `notes`, `valid_until` mudam só em `DRAFT` (`CR007` depois).
- [ ] FK composta do atendimento exige o **mesmo paciente** e a mesma clínica (`23503`).

### US-002: Itens, snapshot e total
**Descrição:** Como dentista, quero montar o orçamento com procedimentos, quantidades e descontos e ver o total sempre correto.

**Critérios de aceite:**
- [ ] `core.budget_items` conforme a spec global: `procedure_type_id`, `description`, `quantity` (1 a 999), `unit_price`, `discount_amount`, `line_total` gerada (`quantity * unit_price - discount_amount`, 0 a 999.999,99), `position` (1 a 50, único por orçamento, adiável).
- [ ] No `INSERT`, `description` e `unit_price` são copiados de `procedure_types` pelo trigger (valor do cliente sobrescrito) e ficam imutáveis; mudar o `base_price` depois não altera o item.
- [ ] `total_amount` recalculado a cada `INSERT`, `UPDATE` e `DELETE` de item; total acima do teto, negativo ou desconto maior que a soma falha com `23514`.
- [ ] Itens só entram, mudam ou saem com o orçamento em `DRAFT` (`CR007`); o trigger trava o orçamento (`FOR NO KEY UPDATE`), então a edição de itens e o envio se serializam.
- [ ] Máximo de 50 itens (posição + unicidade); `crm_app` tem `DELETE` em `budget_items` (a única exceção da fase).

### US-003: Status, envio e aprovação
**Descrição:** Como dentista, quero enviar, aprovar ou recusar o orçamento sem que ele possa voltar atrás.

**Critérios de aceite:**
- [ ] `DRAFT → SENT` ou `APPROVED` exige ao menos 1 item e `total_amount > 0` (`CR008`); `DRAFT → REJECTED` descarta o rascunho.
- [ ] `SENT → DRAFT` e qualquer saída de `APPROVED`/`REJECTED` falha (`CR008`); `DRAFT → APPROVED` direto é permitido (decisão S6).
- [ ] Fora de `DRAFT` o conteúdo é congelado (`CR007`); a única exceção é `notes → NULL` dentro da anonimização (flag `core.anonymizing`).
- [ ] A ordem entre os demais status é do backend.

### US-004: Guarda do paciente
**Descrição:** Como dentista, quero que não se crie orçamento para paciente inativo ou anonimizado.

**Critérios de aceite:**
- [ ] `INSERT` para paciente anonimizado → `CR003`; inativo → `CR005`; a checagem trava o paciente (`FOR KEY SHARE`) e espera uma anonimização em curso.
- [ ] Orçamento existente de paciente inativado continua editável e decidível.

### US-005: Papéis e isolamento
**Descrição:** Como titular, quero que a recepção veja os orçamentos mas não os altere.

**Critérios de aceite:**
- [ ] RLS `ENABLE` + `FORCE` em `budgets` e `budget_items`; `SELECT` para os dois papéis; `INSERT` (com `created_by = app.user_id` no cabeçalho) e `UPDATE` só `DENTIST_ADMIN`; `DELETE` só em `budget_items`, só `DENTIST_ADMIN`.
- [ ] Política `SELECT TO crm_backup USING (true)` nas duas tabelas.
- [ ] Com `app.role` ausente ou inválido, tudo negado; sem `app.clinic_id`, 0 linhas.

## 3. Requisitos funcionais

- **FR-1:** Migration `V024__core_budgets.sql` com as duas tabelas, índices, triggers, funções (`budgets_before_insert`, `guard_budget_update`, `guard_budget_item`, `refresh_budget_total`, `budget_items_total`), RLS e grants.
- **FR-2:** `crm_app` com `SELECT`, `INSERT`, `UPDATE` em `budgets`; `SELECT`, `INSERT`, `UPDATE`, `DELETE` em `budget_items`; `EXECUTE` em `core.budget_items_total(uuid)`.
- **FR-3:** Códigos novos: `CR007` (congelado) e `CR008` (status inválido), conforme a spec global, seção 6.1.
- **FR-4:** Rollback `U024`: só seguro antes de haver dados; depois, restaurar do `pg_dump`.

## 4. Fora do escopo

- PDF (F03), parcelas (F04) e pagamentos (F05).
- Cancelamento, expiração automática, versionamento e duplicação de orçamento.
- Dente, face ou região por item; odontograma.
- Desconto em percentual (o backend converte para valor).
- Máquina completa de status no banco (só terminalidade e congelamento).
- Auditoria (F06) e anonimização (F07).

## 5. Considerações técnicas e riscos

- **Corrida item × envio:** o lock do orçamento no `BEFORE` do item decide quem entra no PDF; testado com duas conexões (teste 99).
- **JPA:** as colunas que o banco preenche (`number`, `total_amount`, `sent_at`, `decided_at`, `description`, `unit_price`, `line_total`) precisam ser lidas de volta; o contrato lista o mapeamento.
- **Numeração** depende de `READ COMMITTED`; sob isolamento maior a segunda transação falha com `23505`.
- O desconto do orçamento só entra por `UPDATE` depois dos itens (S7): o backend precisa dessa ordem.
- **Pergunta aberta:** nenhuma.
