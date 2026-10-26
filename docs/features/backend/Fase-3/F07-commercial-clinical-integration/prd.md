# PRD — F07 Integração com o núcleo clínico (anonimização e auditoria)

**Derivação:** PRD global da Fase 3, US-P3-13 e US-P3-14, FR-15 e FR-16; seção 2 (SPI de anonimização) e os padrões "Anonimização" e "Códigos do banco → HTTP". Spec global, seções 4.12, 4.14 e 5.1; divergência D2.
**Dependências externas:** F01 (`AnonymizationContributor`, tradução de `CR006`, `StorageKeys` com `budgets/`), F04 (`BudgetDocument`, objetos no Storage), F05 (`Installment`, `CR006`), `PatientAnonymizationService`, `PatientFileService.storageKeysOf/purgeObjects` e `AuditQueryService` (Fase 1), migrations V028 (auditoria) e V029 (anonimização). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** a anonimização LGPD do paciente precisa conviver com o histórico contábil: manter orçamentos, parcelas e pagamentos, **apagar os PDFs** (que trazem o nome) e recusar enquanto houver dívida aberta; e o dentista precisa saber quem mexeu em dinheiro.
- **Solução:** o `PatientAnonymizationService` passa a traduzir `CR006`, a coletar as chaves de Storage dos PDFs por uma SPI implementada em `commercial` e a informar quantos orçamentos em aberto o paciente tinha; a consulta de auditoria passa a aceitar as quatro tabelas novas.
- **Metas:**
  1. 100% dos PDFs do paciente saem do listado/download e seus objetos são apagados depois do sucesso; falha no Storage mantém a anonimização e responde `PENDING`.
  2. Orçamentos, itens, parcelas e pagamentos permanecem íntegros; `budgets.notes` do paciente fica `NULL` (conferido por IT).
  3. `patient` nunca importa `commercial` nem `finance` (teste de arquitetura).
  4. Cobertura ≥ 80% nas classes de serviço alteradas de `patient` e `record` e em `BudgetAnonymizationContributor`.

## 2. User stories

### US-001: Anonimizar paciente com histórico comercial
**Descrição:** Como dentista, quero anonimizar um paciente sem perder o histórico contábil e sem deixar o nome dele em PDF.

**Critérios de aceite:**
- [ ] `POST /api/v1/patients/{id}/anonymize`: `CR006` (parcela `OPEN` do paciente) → 409 "Quite ou cancele as parcelas em aberto antes de anonimizar."; a precedência `CR002 → CR004 → CR006` é a do banco.
- [ ] Depois do sucesso: `budgets.notes` do paciente `NULL`; `number`, `totalAmount`, `status`, itens, parcelas e pagamentos **permanecem**; os PDFs ficam com `deleted_at` e deixam de aparecer na listagem e no download (404).
- [ ] As chaves dos PDFs são listadas **depois** da função do banco e **na mesma transação**, junto às dos exames; depois do commit os objetos são apagados em lote. Falha no Storage → anonimização mantida e `storageCleanup: PENDING` (comportamento atual).
- [ ] A resposta ganha `openBudgets` (orçamentos `DRAFT`/`SENT` do paciente); é aviso, não bloqueia. Orçamento `SENT` sem parcelas não impede a anonimização.
- [ ] Orçamento novo para paciente anonimizado → 409 (`CR003`); parcela e pagamento também.

### US-002: Auditoria comercial e financeira
**Descrição:** Como dentista, quero saber quem criou ou alterou orçamentos, parcelas e pagamentos.

**Critérios de aceite:**
- [ ] `GET /api/v1/audit-log?tableName=budgets|installments|payments|budget_documents&recordId=…` (só D) devolve `INSERT`, `UPDATE` e `ANONYMIZE` (autor, ação, instante), **sem valores**.
- [ ] `procedure_types` e `budget_items` **não** são auditados: `tableName` com esses nomes → 400 "tableName desconhecido.".
- [ ] A troca automática de `status` da parcela por pagamento aparece como `UPDATE` da parcela; o estorno, como `INSERT` em `payments`; a anonimização gera 1 `UPDATE` por orçamento com `notes` limpo, mais o `ANONYMIZE`.
- [ ] Nenhum endpoint de escrita na auditoria; falha da auditoria do banco (`28000`) reverte a operação e responde 500 sem detalhes.

## 3. Requisitos funcionais

- **FR-1:** `PatientAnonymizationService` consulta `List<AnonymizationContributor>` (injeção): chaves em `objectKeys` e contagem em `openItems`; `PatientFileService.storageKeysOf` segue direto (D2).
- **FR-2:** `BudgetAnonymizationContributor` em `commercial` devolve as `storage_key` de **todos** os `budget_documents` do paciente (inclusive os marcados agora) e a contagem de orçamentos `DRAFT`/`SENT`.
- **FR-3:** Tradução de `CR006` no service (mensagem própria) e no handler global.
- **FR-4:** `AnonymizationResponse(anonymizedAt, storageCleanup, openBudgets)`.
- **FR-5:** `AuditQueryService.TABLES` acrescenta `budgets`, `installments`, `payments` e `budget_documents`; a lista continua fechada.
- **FR-6:** `files.purgeObjects(allKeys)` aceita as duas formas de chave (`StorageKeys.require` da F01).
- **FR-7:** Evento `Patient.Anonymized` passa a levar a contagem total de objetos; só ids e contagens em log.

## 4. Fora do escopo

- Reconciliação automática de objetos órfãos (a falha continua `PENDING` e manual).
- Anonimizar o `description` dos itens (é cópia do nome do procedimento, não dado pessoal) e qualquer outro texto livre além de `notes`.
- Exportação de dados do titular; prazo de 20 anos (herdados).
- Auditoria de leitura e de valores antigo/novo; migration ou alteração de banco.

## 5. Considerações técnicas e riscos

- **Ordem:** listar as chaves **depois** da função garante que um upload ou envio concorrente falhe (`CR003`) em vez de deixar objeto fora da lista.
- `patient → record` já existe; a SPI evita o novo ciclo `patient → commercial` (D2).
- Os dumps do `crm_backup` seguem trazendo os dados originais por até 30 dias (herdado da Fase 1).
- O PDF removido do Storage não é recuperável: só o `sha256` e os metadados ficam.
