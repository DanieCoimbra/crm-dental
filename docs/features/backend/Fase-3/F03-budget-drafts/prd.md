# PRD — F03 Orçamento em rascunho

**Derivação:** PRD global da Fase 3, US-P3-03 e US-P3-04, FR-2, FR-12 e FR-13; padrão "Orçamento em uma só chamada". Spec global, seções 2.2 (`Budget`, `BudgetItem`), 3.1, 3.2, 4.1, 4.4 e 4.5 (tabela `BudgetTransitions`).
**Dependências externas:** F01 (`Money`, tradução de `CR003`/`CR005`/`CR007`/`CR008`, rotas), F02 (`ProcedureTypeLookup.quotes/requireActive`), `PatientLookup.requireSchedulable/briefs` e `AppointmentLookup.requireOfPatient` (Fases 1 e 2), migration V024 (`budgets`, `budget_items`). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** o dentista precisa montar um orçamento com itens e desconto, ajustá-lo e consultá-lo antes de enviar; o banco impõe número, total e congelamento, e a recepção só pode ler.
- **Solução:** criar, editar (substituindo os itens), consultar, listar e descartar o orçamento em `DRAFT`, gravando na ordem que o banco exige e relendo as colunas calculadas por trigger.
- **Metas:**
  1. Dois `POST /budgets` simultâneos na mesma clínica recebem números distintos e sequenciais, em 20 repetições, sem 500.
  2. Orçamento de 20 itens criado com p95 ≤ 250 ms (banco ≤ 100 ms); lista do paciente p95 ≤ 100 ms.
  3. Nenhum campo calculado pelo banco (`number`, `totalAmount`, `unitPrice`, `description`, `lineTotal`, carimbos) é aceito do cliente.
  4. Cobertura ≥ 80% em `BudgetService`, `BudgetDraftValidator` e `BudgetTransitions`.

## 2. User stories

### US-001: Criar o rascunho
**Descrição:** Como dentista, quero montar um orçamento para um paciente com itens e desconto.

**Critérios de aceite:**
- [ ] `POST /api/v1/budgets` (só D) recebe `patientId`, `appointmentId?`, `items[]` (1 a 50: `procedureTypeId`, `quantity` 1–999, `discountAmount?`), `discountAmount?`, `validUntil?` e `notes?` (≤ 500); cria em `DRAFT` e devolve 201 com `number`, itens com `description`, `unitPrice`, `lineTotal` e `totalAmount` (calculados pelo banco) e `Location`.
- [ ] Gravação na ordem do banco: cabeçalho (desconto 0) → itens → desconto; tudo numa transação, com releitura do orçamento.
- [ ] Paciente inexistente → 404; anonimizado ou inativo → 409. `appointmentId` inexistente → 404; de outro paciente → 409.
- [ ] Procedimento inexistente → 404; inativo → 422 `PROCEDURE_INACTIVE`.
- [ ] Desconto de item maior que a linha, desconto do orçamento maior que a soma, linha, soma ou total acima de R$ 999.999,99 → 400 com o caminho do campo (`items[2].discountAmount`); a corrida de preço que escapar vira 422 `AMOUNT_OUT_OF_RANGE`.
- [ ] `number`, `totalAmount`, `unitPrice`, `description`, `status`, `sentAt`, `decidedAt` e `clinicId` do corpo são ignorados. A documentação avisa que `notes` vai ao PDF e **não leva dado clínico**.

### US-002: Editar o rascunho
**Descrição:** Como dentista, quero corrigir itens, desconto e observações enquanto o orçamento é rascunho.

**Critérios de aceite:**
- [ ] `PUT /budgets/{id}` (só D, com `version`) **substitui** a lista de itens inteira (posições 1..n) e atualiza `discountAmount`, `validUntil`, `notes` e `appointmentId`; `patientId` não é aceito.
- [ ] Status diferente de `DRAFT` → 409 com o status atual; `version` defasada → 409.
- [ ] A substituição respeita a ordem de flush (zera o desconto, remove os itens, insere os novos, grava o desconto final) e nunca estoura `ck_budgets_total` em estado intermediário.
- [ ] As mesmas validações da criação valem na edição; a resposta traz o orçamento relido e a `version` final.

### US-003: Consultar orçamentos
**Descrição:** Como dentista ou recepcionista, quero listar e abrir orçamentos.

**Critérios de aceite:**
- [ ] `GET /budgets?status&patientId&number&page&size` (D, R): `status` repetível; `number` ≥ 1 busca o número exato; `size` 1–50 (padrão 20); ordenado por `createdAt DESC, id DESC`; valor inválido → 400. Cada item traz `id`, `number`, `patient {id, name}`, `status`, `totalAmount`, `validUntil`, `sentAt`, `decidedAt`, `createdAt`, `version`.
- [ ] `GET /budgets/{id}` (D, R) traz itens, desconto, `notes`, `appointmentId` e os documentos ativos (`id`, `revision`, `generatedAt`, `sizeBytes`; **nunca** `storageKey`); de outra clínica → 404. A recepção recebe o mesmo, **sem** parcelas.
- [ ] `GET /patients/{id}/budgets?page&size` (D, R) lista os orçamentos do paciente (qualquer status; inativo ou anonimizado continua consultável; inexistente → 404).
- [ ] Orçamento de paciente anonimizado continua listado, com o nome anonimizado e `notes` nulo. `Cache-Control: no-store`.

### US-004: Descartar rascunho
**Descrição:** Como dentista, quero descartar um rascunho que não vou usar.

**Critérios de aceite:**
- [ ] `POST /budgets/{id}/discard` (só D, `{version}`): `DRAFT → REJECTED`; não gera PDF e não exige itens.
- [ ] Outro status → 409 com o status atual; recepção → 403.

## 3. Requisitos funcionais

- **FR-1:** Entidades `Budget`, `BudgetStatus` e `BudgetItem` (`@Immutable`, `Persistable`), repositórios e `BudgetService`.
- **FR-2:** `BudgetDraftValidator` (regra pura) com os limites por linha, soma, desconto e total.
- **FR-3:** `BudgetTransitions` (tabela pura, completa para a fase: `SEND`, `APPROVE`, `REJECT`, `DISCARD`, `EDIT`, `REGENERATE_DOCUMENT`, `PLAN`); a F03 usa `EDIT` e `DISCARD`.
- **FR-4:** `BudgetLookup` (interface pública) com `brief(ids)`; as demais operações entram nas F05 e F06.
- **FR-5:** Carregar o orçamento com `@Lock(PESSIMISTIC_WRITE)` nas escritas; `reload` (`flush` + `refresh`) após cada gravação.
- **FR-6:** `GET /budgets` por `Specification` (Criteria), sem `(:p is null or …)`.
- **FR-7:** Eventos `Budget.Created`, `Updated` e `Discarded` após o commit, só com identificadores.
- **FR-8:** `Phase3SchemaValidateIT` ganha `Budget` e `BudgetItem`.

## 4. Fora do escopo

- Envio e PDF (F04); aprovação e recusa (F05); parcelas (F05).
- Endpoint de item avulso (a remoção é só por `PUT` substitutivo).
- Percentual de desconto (o front converte); duplicar orçamento; busca textual.
- Migration ou alteração de banco.

## 5. Considerações técnicas e riscos

- Colunas preenchidas por trigger são omitidas do `INSERT` (`insertable = false`); o `NOT NULL` só é checado depois do `BEFORE INSERT` (ponto 1 da seção 9 da spec).
- O `@Version` sobe a cada `UPDATE` de entidade dentro da transação (até 3 por `PUT`): o cliente usa o `version` da resposta.
- O preço do procedimento pode mudar entre a leitura e a gravação do item: a validação prévia pode divergir do que o banco copia; o `23514` residual vira 422.
- A numeração depende de `READ COMMITTED` e do lock consultivo do banco; nunca mudar o isolamento.
