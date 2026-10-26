# PRD — F04 Envio do orçamento e PDF

**Derivação:** PRD global da Fase 3, US-P3-05 e US-P3-06, FR-4, FR-5 e FR-16; decisões H1 e H2; padrões "PDF do orçamento", "Chave do objeto" e "Download do PDF". Spec global, seções 2.2 (`BudgetDocument`), 4.6, 4.7, 5.1, 6.1, 6.2 e 7.
**Dependências externas:** F01 (`StorageKeys.ofBudget`, bean `documentStorage`, `crm.documents.*`, `ClinicIdentity`, OpenPDF, tradução de erros), F03 (`Budget`, `BudgetItem`, `BudgetTransitions`, `BudgetService`), `PatientLookup.briefs`, migration V025 (`budget_documents`). Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** o dentista precisa enviar o orçamento ao paciente e **provar depois exatamente o que foi oferecido**; o PDF contém o nome do paciente e não tem RLS, então só o servidor o gera e só sai por URL assinada de curta duração.
- **Solução:** `send` muda o orçamento para `SENT`, gera o PDF (OpenPDF), grava o objeto no Storage e registra o metadado em `budget_documents`, **tudo numa transação com compensação** (H1); novas revisões e o download completam o ciclo.
- **Metas:**
  1. Com falha injetada no Storage, no `sha256` e no `INSERT`, o orçamento permanece `DRAFT`, não existe linha em `budget_documents` e o objeto não fica órfão (verificado no `FileStorage` de teste).
  2. Envio (PDF de 20 itens + Storage local) p95 ≤ 1,5 s; geração do PDF em memória ≤ 300 ms.
  3. Com 3 envios lentos simulados, uma leitura de orçamento ainda responde em p95 ≤ 200 ms (o pool de 10 não é esgotado).
  4. Cobertura ≥ 80% em `BudgetDocumentService` e `BudgetPdfRenderer`.

## 2. User stories

### US-001: Enviar o orçamento
**Descrição:** Como dentista, quero enviar o orçamento e guardar o PDF que ofereci.

**Critérios de aceite:**
- [ ] `POST /api/v1/budgets/{id}/send` (só D, `{version}`): `DRAFT → SENT` e geração do PDF na mesma transação; devolve 200 com o orçamento atualizado e o documento (revisão 1).
- [ ] Sem itens ou com total zero → 409 "Inclua ao menos um item com valor maior que zero."; fora de `DRAFT` → 409 com o status atual; `version` defasada → 409.
- [ ] Falha no Storage → `ROLLBACK`, orçamento volta a `DRAFT`, nenhuma linha em `budget_documents`, objeto apagado, resposta 503; falha no `INSERT` do documento → `ROLLBACK`, objeto apagado, 500.
- [ ] Falha na compensação não muda a resposta ao cliente: gera o evento `StorageCompensationFailed` (só `budgetId`) e `log.error` sem chave.
- [ ] PDF maior que 5 MB → 422 `PDF_TOO_LARGE`, nada gravado.
- [ ] O "envio" **não** dispara e-mail nem WhatsApp: marca como enviado e disponibiliza o PDF para o dentista baixar e entregar.

### US-002: Conteúdo do PDF
**Descrição:** Como dentista, quero um PDF simples e fiel ao orçamento congelado.

**Critérios de aceite:**
- [ ] Contém nome, endereço e telefone da clínica; "Orçamento nº N" (e "Revisão n" quando n > 1); data de envio; nome do paciente (**sem CPF, telefone ou e-mail**); tabela `Descrição | Qtd | Preço unit. | Desconto | Total`; subtotal, desconto do orçamento e total; "Válido até" quando houver; observações.
- [ ] A4, Helvetica `CP1252` (acentos e `R$` corretos), formatação pt-BR; rodapé "Página x de y".
- [ ] O `sha256` (hex minúsculo) e o `size_bytes` gravados conferem com o objeto; o objeto fica em `<clinic>/<patient>/budgets/<uuid>`.
- [ ] O renderizador não acessa banco, Storage nem relógio (entrada: `BudgetPdfModel`).

### US-003: Revisões e download
**Descrição:** Como dentista ou recepcionista, quero baixar o PDF; como dentista, quero gerar uma nova revisão.

**Critérios de aceite:**
- [ ] `GET /budgets/{id}/documents` (D, R) lista as revisões **ativas** em ordem decrescente (`id`, `revision`, `generatedAt`, `sizeBytes`), nunca a `storageKey`.
- [ ] `GET /budgets/{id}/documents/{docId}/download-url` (D, R) devolve `{url, expiresInSeconds}` de 60 s; documento removido, de outro orçamento ou de outra clínica → 404; `no-store`; a URL nunca vai a log.
- [ ] `POST /budgets/{id}/documents` (só D, sem corpo) gera nova revisão de orçamento em `SENT`, `APPROVED` ou `REJECTED` sem mudar o status; `DRAFT` → 409; a anterior é conservada; a revisão impressa é a atribuída pelo banco.
- [ ] Recepção recebe 403 na geração e 200 nas duas leituras.

### US-004: Contenção de carga
**Descrição:** Como responsável pela operação, quero que os envios não esgotem o pool de conexões.

**Critérios de aceite:**
- [ ] No máximo 3 envios/revisões simultâneos por instância (`DocumentSlots`); sem vaga em 2 s → 503 com `Retry-After: 5`.
- [ ] Mais de 6 envios ou revisões por minuto por usuário → 429 com `Retry-After`.
- [ ] Upload com timeout de 10 s (bean `documentStorage`) e transação com teto de 20 s; o `release()` do semáforo ocorre também quando o commit falha.

## 3. Requisitos funcionais

- **FR-1:** `BudgetDocumentService` (sem `@Transactional`; `TransactionTemplate` com timeout) com `send`, `regenerate`, `list` e `downloadUrl`.
- **FR-2:** `BudgetPdfRenderer` puro e `BudgetPdfModel` imutável; `sha256` e tamanho calculados fora dele.
- **FR-3:** Entidade `BudgetDocument` (`@Immutable`, `Persistable`); `revision` e `generated_at` pelo banco, lidos por `reload`.
- **FR-4:** Compensação: registrar a chave **antes** de gravar; ao falhar, apagar o objeto; commit incerto → conferir `budget_documents` antes de apagar.
- **FR-5:** `DocumentSlots` e `DocumentRateLimiter` + `DocumentRateLimitInterceptor` em `POST /budgets/*/send` e `POST /budgets/*/documents`.
- **FR-6:** Download por URL assinada fora de transação; `StorageKeys.belongsTo` conferido.
- **FR-7:** Eventos `Budget.Sent`, `Budget.DocumentGenerated` e `Budget.StorageCompensationFailed`, só com ids e `revision`.
- **FR-8:** `Phase3SchemaValidateIT` ganha `BudgetDocument`.

## 4. Fora do escopo

- Envio por e-mail ou WhatsApp, assinatura digital, aceite do paciente, layout configurável e versionamento de modelo.
- Aprovação, recusa e parcelas (F05); anonimização dos PDFs (F07).
- Reconciliação automática de objetos órfãos e backup do bucket; limite de 10 MB e MIME do bucket (pendência de segurança do Supabase).
- Download por proxy do binário.

## 5. Considerações técnicas e riscos

- **Pool:** a transação segura 1 conexão (de 10) até ~15 s; por isso o semáforo de 3, o timeout de 10 s e o limite por usuário.
- **Commit incerto** (conexão perdida após o ack): a conferência por `storage_key` evita apagar o objeto de uma linha que existe.
- A revisão do PDF é calculada (`max + 1`) sob a trava do orçamento só para imprimir; o banco atribui a real e devem coincidir.
- O PDF contém o nome do paciente: bucket privado, URL de 60 s, `sha256` e remoção na anonimização (F07).
