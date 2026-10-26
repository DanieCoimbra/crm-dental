# PRD Global (Backend) — Fase 3: Comercial e Financeiro

**Status:** aprovado pelo usuário em 2026-10-26 (perguntas abertas resolvidas conforme as propostas; busca por `number` incluída). Decisões H1–H4 confirmadas na entrevista de descoberta. Próximo passo do pipeline após a aprovação: `spec-write-legacy` (`docs/global/backend/Fase-3/spec.md`) e depois `breakdown-legacy`.
**Escopo:** API REST dos módulos novos `catalog` (tipos de procedimento), `commercial` (orçamento, itens, PDF) e `finance` (parcelas, pagamentos e consultas do financeiro), e os ajustes em `scheduling`, `patient`, `record` e `shared` que a fase exige, sobre as Fases 0, 1 e 2.
**Fontes:** `docs/arq_inicial.md` (módulos Orçamento & Tratamento e Financeiro, Fase 3), `docs/global/backend/Fase-{0,1,2}/{prd,spec}.md`, `docs/global/banco/Fase-3/{prd,spec}.md` (contrato do banco, em especial as seções 2.3, 4, 6 e 7), código atual de `back-java/` e `database/` (V001–V029; **a V022–V029 já está aplicada no Supabase real desde 2026-10-26**). O `AGENTS.md` da raiz continua **vazio**: as premissas vêm dos documentos acima e da memória do projeto.
**Função no pipeline:** `backend`. O backend **não** cria nem altera objetos de banco; consome o contrato da função `banco`. Este PRD é a conciliação prevista no PRD do banco da Fase 3 (risco 11).

## Decisões fechadas

Herdam D1–D8 (Fase 0), E1–E6 (Fase 1) e G1–G4 (Fase 2): monólito modular, Spring Boot 4.1.1 / Java 21, pacote `crm_data`, JWT 15 min + refresh rotativo, filtro Hibernate **+** RLS, papel e tenant só do JWT. Herdam também as decisões do banco C1–C5 e S1–S16 (Fase 3). Novas, confirmadas em 2026-10-26:

| # | Decisão | Valor |
|---|---|---|
| H1 | Envio e Storage | **Transação única com compensação**, como a spec do banco (6.3): `UPDATE` para `SENT`, gerar o PDF, gravar o objeto no Storage, `INSERT` em `budget_documents` e `COMMIT`. Qualquer falha faz `ROLLBACK` (o orçamento volta a `DRAFT`) e apaga o objeto órfão. Isto **relaxa, só para este fluxo,** a regra "nunca dentro de transação" do `FileStorage` (limites na seção 9) |
| H2 | Biblioteca de PDF | **OpenPDF** (o `pom.xml` não tem biblioteca de PDF hoje) |
| H3 | Aprovação | O backend **exige `SENT`** para aprovar ou recusar: `DRAFT → APPROVED` é recusado com 409, embora o banco o permita (S6). Descartar rascunho segue `DRAFT → REJECTED` |
| H4 | Parcelas | **Modo automático e modo manual**: automático (nº de parcelas, 1º vencimento, entrada opcional, a última absorve os centavos) ou manual (lista `{valor, vencimento}`, soma conferida pelo servidor) |

## Padrões adotados (sem pergunta; contestar se discordar)

- **Três módulos novos, sem ciclo.** `catalog` (tipos de procedimento; não depende de ninguém) → usado por `scheduling` e `commercial`; `commercial` (orçamento, itens, PDF) depende de `catalog`, `patient` (`PatientLookup`), `scheduling` (`AppointmentLookup`) e `clinic`; `finance` (parcelas, pagamentos, relatórios) depende de `commercial` por uma interface pública de leitura. Nenhum módulo usa o repositório de outro. Separar `catalog` evita o ciclo `scheduling ↔ commercial`, pois a agenda passa a apontar para o procedimento.
- **Dinheiro na API como texto decimal** (`"1234.50"`, escala 2, em BRL), nunca número de ponto flutuante: o React usa `Number` e arredondaria. Entrada com mais de 2 casas, negativa ou acima de `999999.99` retorna 400. Internamente `BigDecimal`. Descontos em **valor**, nunca em percentual (o front converte).
- **Rotas por recurso e ação**, como na Fase 2: sem `PATCH status` genérico; cada transição é `POST /{id}/<ação>` com `{version}` no corpo. Ativar/inativar procedimento é `PATCH /{id}/active`, como em pacientes.
- **Orçamento em uma só chamada.** O front não conhece a ordem interna do banco (S7, 6.3): `POST /budgets` recebe cabeçalho, itens e desconto e o backend grava, na mesma transação, cabeçalho em `DRAFT` → itens → `UPDATE` do desconto. `PUT /budgets/{id}` (só `DRAFT`, com `version`) **substitui** a lista de itens inteira (posições 1..n) e atualiza desconto, validade, observação e atendimento de origem. `patientId` nunca muda.
- **Colunas do banco nunca vêm do cliente:** `number`, `totalAmount`, `sentAt`, `decidedAt`, `description` e `unitPrice` do item, `revision`, `status` de parcela, `kind` de pagamento criado por rota errada. Após cada gravação o backend **relê a linha** (as colunas preenchidas por trigger) e devolve o recurso atualizado.
- **Status da parcela é do banco.** O backend nunca grava `PAID`/`OPEN`; só `CANCELED` por consulta própria (`UPDATE ... SET status = 'CANCELED'`), conforme a spec do banco 6.2. Pagamento e estorno são só `INSERT`.
- **Pagamento sem pré-checagem de saldo como autoridade.** A autoridade é o banco sob a trava da parcela (`CR010` → 409); o backend só valida formato (`amount` > 0, 2 casas) e a data. `paidOn` (data civil) é "hoje" no fuso da clínica por padrão, aceita data passada (≥ 2000-01-01) e **recusa data futura** (422).
- **Período de relatório:** `from`/`to` são datas locais da clínica (`to` inclusivo). Caixa usa `paid_on` direto; competência (faturamento) converte `[from 00:00, to+1 00:00)` no fuso da clínica para `decided_at`. Janela máxima de 366 dias, `Cache-Control: no-store`.
- **PDF do orçamento:** uma a duas páginas A4; traz nome, endereço e telefone da clínica, **nome do paciente (sem CPF)**, número, data de envio, revisão, itens (descrição, quantidade, preço unitário, desconto, total da linha), desconto do orçamento, total, validade e observações. Fontes base do OpenPDF (WinAnsi cobre o português). Tamanho ≤ 5 MB (limite do banco); acima disso, 422. `sha256` em hex minúsculo calculado pelo backend.
- **Chave do objeto:** `<clinic_id>/<patient_id>/budgets/<uuid>`. `StorageKeys` e as duas implementações de `FileStorage` hoje só aceitam três UUIDs e precisam aceitar o segmento `budgets`; o resto do contrato (nunca registrar chave nem URL em log) vale como está.
- **Download do PDF** por URL assinada de **60 s** (mesmo padrão dos exames); a recepção também baixa (C2), em rota própria. Sem proxy do binário.
- **Procedimento inativo** (regra do backend, spec do banco 6.3-8): item novo e agendamento novo com procedimento `active = false` retornam 422 `business-rule` (`reason: PROCEDURE_INACTIVE`). Orçamento já em `SENT`/`APPROVED` não é afetado.
- **Agenda × procedimento:** `procedureTypeId` entra, opcional, em criar e remarcar agendamento e na resposta (`id`, `name`, `color`). `endsAt` continua **obrigatório**; o front usa `defaultDurationMinutes` para propor o horário final (o backend não calcula a duração).
- **Códigos do banco → HTTP:** `CR007`, `CR008`, `CR009`, `CR010`, `CR011` e `CR006` viram **409** com mensagem própria (seção 6.3 da spec do banco); `CR003`/`CR005` já existem. `42501` num `INSERT`/`UPDATE` é bug do backend (o 403 sai antes). Nenhum `DETAIL` do driver é logado nem devolvido.
- **`CR011` só aparece no `COMMIT`.** Todo fluxo que cria parcelas usa `TransactionTemplate` e traduz a exceção no commit, não só no `flush`.
- **Anonimização:** o contrato é do banco (4.7). O backend lista também as `storage_key` dos PDFs do paciente **depois** de chamar a função e dentro da mesma transação, apaga os objetos depois do commit e traduz `CR006`. A resposta passa a informar quantos orçamentos `DRAFT`/`SENT` o paciente tinha (aviso; não bloqueia).
- **Sem idempotência de pagamento** (sem `Idempotency-Key`) nesta fase: dois cliques geram dois pagamentos se couber no saldo; a correção é o estorno. Risco aceito e listado na seção 10.
- Convenções das Fases 0 a 2 mantidas: Lombok sem `@Data`/`@ToString`/`@EqualsAndHashCode`; `ProblemDetail` (RFC 9457); `@PreAuthorize` nos services como segunda barreira; DTOs sem `clinicId`, `createdBy`, `role` ou `id` vindos do cliente; `@Version Long` com 409 em conflito; UUID gerado pela aplicação e `persist` sem `merge` com `SELECT` prévio; `READ COMMITTED`; logs só com identificadores.

---

## 1. Resumo executivo

**Problema:** com pacientes e agenda prontos (Fases 1 e 2), o consultório ainda não registra o que cobra, não monta orçamentos nem acompanha o que cada paciente já pagou ou deve. O dentista solo precisa de orçamentos que não mudem depois de enviados, de parcelas cuja soma sempre feche o total, de pagamentos que não se apagam e de um caixa em que a recepção não enxergue valores de parcela e pagamento.

**Solução:** os módulos `catalog`, `commercial` e `finance` expõem a API da fase. O backend orquestra o que o banco já impõe (total, congelamento, soma das parcelas, pagamento append-only), conduz a máquina `DRAFT → SENT → APPROVED/REJECTED`, gera e guarda o PDF do orçamento, divide o total em parcelas, lança pagamentos e estornos e entrega as consultas de contas a receber, inadimplência, fluxo de caixa e faturamento. Os módulos `patient`, `record` e `scheduling` passam a conversar com a fase (anonimização com `CR006` e PDFs, auditoria, procedimento na agenda).

**Metas e métricas (backend, Fase 3):**
1. 100% dos endpoints novos têm teste da matriz papel × endpoint (401/403/2xx); `RECEPTIONIST` recebe 403 em **todas** as rotas de escrita da fase e em **todas** as rotas de parcela, pagamento e `/finance/**`.
2. **Invariantes de dinheiro pela API (teste de propriedade):** em sequências aleatórias de operações válidas, (a) `totalAmount` = Σ linhas − desconto; (b) Σ parcelas não canceladas = `totalAmount` do orçamento aprovado; (c) nenhuma parcela com líquido pago > valor; (d) `status = PAID` ⇔ líquido ≥ valor. Operação inválida retorna 4xx sem alterar dados.
3. **Concorrência:** 2 pagamentos simultâneos sobre o último saldo da mesma parcela resultam em exatamente um 201 e um 409, em 20 repetições, sem nenhum 500; em parcelas diferentes, ambos passam. 2 `POST /budgets` simultâneos na mesma clínica recebem números distintos, em 20 repetições. Item editado × orçamento enviado ao mesmo tempo: um dos dois vence e o outro recebe 409, nunca estado misto.
4. **Envio atômico:** com falha injetada no Storage, no cálculo do `sha256` e no `INSERT` de `budget_documents`, o orçamento permanece `DRAFT`, não existe linha em `budget_documents` e o objeto não fica órfão (verificado no `FileStorage` de teste).
5. Com o filtro Hibernate desligado e `app.role = RECEPTIONIST` forjado, a RLS sozinha impede toda escrita da fase e esconde `installments`/`payments`; nenhuma linha de outra clínica aparece em nenhuma tabela nova (defesa em profundidade provada em Testcontainers, como nas Fases 1 e 2).
6. Desempenho com 5 mil parcelas e 5 mil pagamentos na clínica (e 100 mil de cada no banco): contas a receber, inadimplência, entradas do mês, faturamento e lista de orçamentos do paciente com p95 ≤ 100 ms com 4 requisições concorrentes em ambiente local; geração e gravação do PDF de 20 itens p95 ≤ 1,5 s com o `FileStorage` local (`PerfLimits` dobra o limite com `CI`).
7. `ddl-auto=validate` passa contra V001–V029 (fecha o teste 120 do banco e o teste 84 da Fase 2) e `mvn verify` continua verde em máquina limpa; a suíte das Fases 1 e 2 segue verde.
8. Nenhum valor monetário, nome de paciente, observação, conteúdo de PDF, chave de objeto ou URL assinada em log, `toString` ou mensagem de erro (teste de higiene de log, validado por mutação como nas Fases 1 e 2).
9. Cobertura ≥ 80% nas classes de serviço de `catalog`, `commercial` e `finance` e nas classes de serviço alteradas de `scheduling`, `patient` e `record`.

---

## 2. Módulos e mudanças nos módulos existentes

| Módulo | Responsabilidade / mudança |
|---|---|
| `catalog` (novo) | Tipos de procedimento (nome, preço-base, duração padrão, cor, ativo). Interface pública `ProcedureTypeLookup` (existe e está ativo; resumo em lote `id`, `name`, `color`; preço e nome para o orçamento) |
| `commercial` (novo) | Orçamento, itens, máquina de status, PDF (geração, Storage, revisões, URL assinada), numeração lida do banco. Interface pública de leitura `BudgetLookup` (orçamento aprovado, total, paciente) e contribuinte das chaves de Storage na anonimização |
| `finance` (novo) | Plano de parcelas, ajuste de vencimento, cancelamento, pagamento e estorno, ficha financeira do paciente, contas a receber, inadimplência, fluxo de caixa e faturamento |
| `scheduling` (alterado) | `Appointment` mapeia `procedure_type_id`; criar e remarcar aceitam `procedureTypeId` (procedimento da clínica e ativo); a resposta traz o resumo do procedimento (em lote, sem N+1). `AppointmentLookup` continua a via para o vínculo do orçamento |
| `patient` (alterado) | `PatientAnonymizationService` traduz `CR006` e reúne também as chaves dos PDFs; `PatientLookup` ganha a checagem "pode receber orçamento" (existe, não anonimizado, ativo). A resposta da anonimização ganha a contagem de orçamentos em aberto |
| `record` (alterado) | `AuditQueryService` aceita `budgets`, `installments`, `payments` e `budget_documents` como `tableName` |
| `shared` (alterado) | `StorageKeys`/`FileStorage` com a chave `budgets/`; tradução centralizada de `CR006`–`CR011` no `GlobalExceptionHandler`; `Phase3ArchitectureTest` cobre os três módulos novos |

**Direção de dependência (sem ciclo).** `finance → commercial → {catalog, scheduling, patient, clinic}` e `scheduling → catalog`. `patient` **não** pode depender de `commercial` (criaria ciclo, pois `commercial` usa `PatientLookup`): a anonimização coleta as chaves de Storage por uma interface de contribuição **definida em `patient`** e implementada em `record` e `commercial` (inversão de dependência; o mecanismo exato fica para a spec).

---

## 3. Matriz papel × endpoint

| Endpoint | `DENTIST_ADMIN` | `RECEPTIONIST` |
|---|---|---|
| `GET /api/v1/procedure-types`, `GET /api/v1/procedure-types/{id}` | sim | sim |
| `POST /procedure-types`, `PUT /procedure-types/{id}`, `PATCH /procedure-types/{id}/active` | sim | **403** |
| `GET /api/v1/budgets`, `GET /budgets/{id}`, `GET /patients/{id}/budgets` | sim | sim |
| `POST /budgets`, `PUT /budgets/{id}` | sim | **403** |
| `POST /budgets/{id}/send`, `/approve`, `/reject` | sim | **403** |
| `GET /budgets/{id}/documents`, `GET /budgets/{id}/documents/{docId}/download-url` | sim | sim |
| `POST /budgets/{id}/documents` (reenviar, nova revisão) | sim | **403** |
| `GET /budgets/{id}/installments`, `POST /budgets/{id}/installments` (gerar/refazer plano) | sim | **403** |
| `PUT /budgets/{id}/installments` (refazer plano), `PATCH /installments/{id}/due-date` | sim | **403** |
| `GET /installments/{id}/payments`, `POST /installments/{id}/payments`, `POST /payments/{id}/reversal` | sim | **403** |
| `GET /api/v1/finance/receivables`, `/overdue`, `/cash-flow`, `/revenue` | sim | **403** |
| `GET /api/v1/patients/{id}/financial` (ficha financeira) | sim | **403** |

O backend nega com 403 **antes** do banco (`SecurityConfig` por rota e `@PreAuthorize` nos services); a RLS é a segunda barreira. A recepção enxerga o orçamento com seu **total** e os itens (C2), mas nunca parcelas, pagamentos nem relatórios; o `GET /budgets/{id}` da recepção **não** traz o bloco de parcelas.

---

## 4. User stories

### US-P3-01: Tipos de procedimento
**Descrição:** Como dentista, quero cadastrar os procedimentos que realizo, com preço-base, duração e cor.

**Critérios de aceite:**
- [ ] `POST /api/v1/procedure-types` (só D) cria `{name, basePrice, defaultDurationMinutes, color}`: nome 2–80 sem ser só espaços; `basePrice` ≥ 0 e ≤ 999999.99; duração múltipla de 5 entre 5 e 720; cor `#RRGGBB`. Devolve 201 com `id`, `active=true`, `version`. `clinicId`, `createdBy`, `id` e `active` nunca vêm do corpo.
- [ ] Nome repetido entre os **ativos** da clínica, sem diferenciar caixa, retorna 409 (`ux_ptype_clinic_name`); o mesmo nome em outra clínica passa; nome de procedimento inativo pode ser reutilizado.
- [ ] `PUT /procedure-types/{id}` (só D, com `version`) altera os quatro campos; alterar o preço **não** muda orçamentos existentes (snapshot do banco).
- [ ] `PATCH /procedure-types/{id}/active` (só D, `{active, version}`) ativa e inativa; não existe `DELETE`. Inativar não afeta itens nem agendamentos já gravados.
- [ ] `GET /procedure-types?active=true|false|all` (padrão `true`, D e R), sem paginação (dezenas de linhas), ordenado por nome sem diferenciar caixa e `id`. `GET /procedure-types/{id}` de outra clínica retorna 404.
- [ ] Validação devolve todos os campos inválidos de uma vez (`errors[]`); o `23514` do banco é a segunda barreira.

### US-P3-02: Procedimento no agendamento
**Descrição:** Como dentista ou recepcionista, quero indicar o procedimento de um agendamento.

**Critérios de aceite:**
- [ ] `POST /appointments` e `PUT /appointments/{id}` (D, R) aceitam `procedureTypeId` opcional; procedimento inexistente na clínica retorna 404, inativo retorna 422 (`reason: PROCEDURE_INACTIVE`).
- [ ] A resposta e a listagem da agenda trazem `procedure {id, name, color}` (nulo quando não informado), obtido em lote.
- [ ] `PUT` sem `procedureTypeId` **mantém** o valor atual; para remover o vínculo, o corpo envia `procedureTypeId: null` explicitamente (a spec fixa a semântica, como foi feito com `appointmentId` da nota).
- [ ] Agendamentos existentes (campo nulo) seguem válidos; a máquina de status e as regras da Fase 2 não mudam; os testes da Fase 2 continuam verdes.

### US-P3-03: Criar e editar o rascunho de orçamento
**Descrição:** Como dentista, quero montar um orçamento com itens e desconto antes de enviá-lo.

**Critérios de aceite:**
- [ ] `POST /api/v1/budgets` (só D) recebe `patientId`, `appointmentId` (opcional), `items[]` (1 a 50: `procedureTypeId`, `quantity` 1–999, `discountAmount` opcional), `discountAmount` (opcional), `validUntil` (opcional, `date`) e `notes` (opcional, ≤ 500). Cria em `DRAFT` e devolve 201 com `number`, itens (com `description`, `unitPrice` e `lineTotal` copiados e calculados pelo banco) e `totalAmount`.
- [ ] A ordem de gravação é cabeçalho → itens → desconto, na mesma transação; o cliente nunca envia `number`, `totalAmount`, `unitPrice`, `description`, `status`, `sentAt` ou `decidedAt` (campos desconhecidos são ignorados e nunca gravados).
- [ ] Paciente inexistente na clínica retorna 404; **anonimizado** retorna 409 (`CR003`); **inativo** retorna 409 (`CR005`). `appointmentId`, se enviado, deve existir na clínica e ser **do mesmo paciente** (404 / 409).
- [ ] Item com desconto maior que a linha, desconto do orçamento maior que a soma, total negativo ou acima de R$ 999.999,99 retorna 409 ou 400 com mensagem própria (`23514` traduzido; o backend valida antes com `errors[]`).
- [ ] Procedimento inativo ou de outra clínica em item: 422 (`PROCEDURE_INACTIVE`) ou 404.
- [ ] `PUT /budgets/{id}` (só D, `version`) só em `DRAFT`: **substitui** os itens (posições 1..n na mesma transação), desconto, validade, notas e atendimento de origem; qualquer outro status retorna 409 com o status atual (`CR007` traduzido). `patientId` não é aceito no corpo.
- [ ] `version` defasada retorna 409. Dois `POST` simultâneos na mesma clínica recebem números distintos (meta 3); a numeração é sequencial por clínica a partir de 1.
- [ ] **Aviso na documentação da API:** `notes` vai no PDF e **não** leva dado clínico; é texto claro.

### US-P3-04: Consultar orçamentos
**Descrição:** Como dentista ou recepcionista, quero ver os orçamentos e abrir um deles.

**Critérios de aceite:**
- [ ] `GET /api/v1/budgets?status&patientId&number&page&size` (D, R); `number` (inteiro ≥ 1) busca por **número exato** do orçamento na clínica (índice `ux_budgets_clinic_number`), sem texto livre; devolve `PageResponse` (`size` máx. 50) ordenado por `createdAt DESC, id DESC`; `status` repetível (valor desconhecido retorna 400). Cada item: `id`, `number`, `patient {id, name}`, `status`, `totalAmount`, `validUntil`, `sentAt`, `decidedAt`, `createdAt`, `version`; nunca `clinicId`.
- [ ] `GET /budgets/{id}` (D, R) traz também itens, `discountAmount`, `notes`, `appointmentId` e a lista de documentos ativos (`id`, `revision`, `generatedAt`; **sem** `storage_key`). Id de outra clínica retorna 404.
- [ ] `GET /patients/{id}/budgets?page&size` (D, R) lista os orçamentos do paciente (qualquer status; paciente inativo ou anonimizado continua consultável; inexistente ou de outra clínica retorna 404).
- [ ] Para a **recepção**, a resposta **não** inclui parcelas, pagamentos nem situação de cobrança. `Cache-Control: no-store`.
- [ ] Orçamento de paciente anonimizado continua listado, com o resumo anonimizado e `notes` nulo.

### US-P3-05: Enviar o orçamento (PDF)
**Descrição:** Como dentista, quero enviar o orçamento ao paciente e guardar o PDF exato do que foi oferecido.

**Critérios de aceite:**
- [ ] `POST /budgets/{id}/send` (só D, `{version}`): `DRAFT → SENT` e geração do PDF na **mesma transação** (H1): `UPDATE` do status, releitura do orçamento, geração, `sha256`, gravação do objeto em `<clinic>/<patient>/budgets/<uuid>`, `INSERT` em `budget_documents` e `COMMIT`. Devolve o orçamento atualizado com o documento (revisão 1).
- [ ] Orçamento sem itens ou com total zero retorna 409 (`CR008`); fora de `DRAFT` retorna 409 com o status atual; `version` defasada, 409.
- [ ] Falha no Storage, na geração ou no `INSERT` faz `ROLLBACK`: o orçamento continua `DRAFT`, não há linha em `budget_documents` e o objeto gravado é apagado (compensação). Falha na **compensação** é registrada como evento (só contagem e identificador do orçamento) e **não** altera a resposta de erro ao cliente; a resposta é 503 `unavailable` quando o Storage falha e 500 `internal` nos demais casos, sem detalhes.
- [ ] O PDF contém exatamente os campos listados em "PDF do orçamento"; tamanho ≤ 5 MB; o `sha256` gravado confere com o objeto (teste relê o objeto no `FileStorage` de teste).
- [ ] O "envio" **não** dispara e-mail nem WhatsApp: marca o orçamento como enviado e disponibiliza o PDF para o dentista baixar e entregar (ver Non-goals).
- [ ] O upload dentro da transação tem **timeout curto configurável** (padrão 10 s) e ocorre com o orçamento já travado pelo `UPDATE`; o limite e o risco do uso de conexão estão na seção 10.

### US-P3-06: Baixar e reenviar o PDF
**Descrição:** Como dentista ou recepcionista, quero baixar o PDF de um orçamento enviado; como dentista, quero gerar uma nova revisão.

**Critérios de aceite:**
- [ ] `GET /budgets/{id}/documents` (D, R) lista as revisões **ativas** (`deletedAt` nulo) em ordem decrescente, com `id`, `revision`, `generatedAt`, `sizeBytes`; nunca a `storage_key`.
- [ ] `GET /budgets/{id}/documents/{docId}/download-url` (D, R) devolve URL assinada de **60 s**; documento removido (`deleted_at`) ou de outra clínica retorna 404; resposta com `Cache-Control: no-store`; a URL nunca é registrada em log.
- [ ] `POST /budgets/{id}/documents` (só D) gera **nova revisão** (`revision` + 1 pelo banco) de orçamento em `SENT`, `APPROVED` ou `REJECTED`, com o mesmo fluxo atômico do envio e **sem** alterar o status; `DRAFT` retorna 409 (`CR008`). A revisão anterior é conservada.
- [ ] Recepção recebe 403 na geração e 200 nas duas leituras; o download por outra clínica retorna 404.

### US-P3-07: Aprovar e recusar
**Descrição:** Como dentista, quero registrar a decisão do paciente sobre o orçamento enviado.

**Critérios de aceite:**
- [ ] `POST /budgets/{id}/approve` e `/reject` (só D, `{version}`) valem **só para `SENT`** (H3): `SENT → APPROVED` ou `SENT → REJECTED`; o banco carimba `decided_at`. `DRAFT` retorna 409 para `approve`; `reject` de `DRAFT` também retorna 409 (para descartar um rascunho existe a rota própria abaixo).
- [ ] `POST /budgets/{id}/discard` (só D, `{version}`): `DRAFT → REJECTED` (descarta o rascunho; não gera PDF).
- [ ] `APPROVED` e `REJECTED` são terminais: toda ação posterior (inclusive `PUT`) retorna 409 com o status atual; nunca volta a `DRAFT`.
- [ ] A aprovação **não** gera parcelas sozinha (elas vêm de US-P3-08, na mesma chamada do front ou depois); orçamento aprovado sem parcelas é um estado válido.
- [ ] Recepção recebe 403; transição fora da tabela da seção 5 retorna 409 com o status atual.

### US-P3-08: Gerar as parcelas
**Descrição:** Como dentista, quero dividir o orçamento aprovado em parcelas.

**Critérios de aceite:**
- [ ] `POST /budgets/{id}/installments` (só D) só para orçamento `APPROVED` (outro status: 409, `CR009`), com dois modos: **automático** `{mode: "AUTO", count (1–60), firstDueDate, downPayment (opcional)}` e **manual** `{mode: "MANUAL", installments: [{amount, dueDate}...]}` (1 a 60).
- [ ] Automático: o total é dividido em `count` parcelas; com `downPayment`, a **parcela 1 é a entrada**, vence no **dia da geração** (data local da clínica) e as demais, `count − 1`, dividem o saldo, vencendo mensalmente a partir de `firstDueDate` (mesmo dia do mês, ajustado ao último dia quando o mês é curto). Arredondamento: as parcelas regulares usam `RoundingMode.DOWN` em centavos e a **última absorve a diferença**; nenhuma parcela fica zero ou negativa (senão 400).
- [ ] Manual: cada `amount` > 0 e ≤ 999999.99; `dueDate` ≥ 2000-01-01; a soma deve ser **igual ao total** do orçamento, senão 400 com a diferença; o backend numera 1..n na ordem de `dueDate` e do envio.
- [ ] O plano é gravado numa única transação com `TransactionTemplate`; `CR011` no `COMMIT` é traduzido para 409 (não deveria ocorrer se a validação do servidor estiver correta; fica como rede). Recarregar as parcelas antes de responder.
- [ ] Orçamento que **já tem parcelas ativas** retorna 409 na geração; refazer o plano é a US-P3-09. `GET /budgets/{id}/installments` (só D) lista as parcelas (inclusive `CANCELED`) com `status`, `amount`, `dueDate`, `netPaid`, `balance`, `version`.
- [ ] Paciente **inativo** pode ter parcelas geradas (a dívida continua); **anonimizado** retorna 409 (`CR003`). Recepção recebe 403.

### US-P3-09: Refazer o plano e ajustar vencimento
**Descrição:** Como dentista, quero corrigir o plano de parcelas sem apagar o histórico.

**Critérios de aceite:**
- [ ] `PUT /budgets/{id}/installments` (só D) **refaz** o plano: na mesma transação cancela as parcelas ativas **sem pagamento líquido** e insere as novas (mesmos dois modos da US-P3-08, mesma validação de soma); parcela com líquido pago > 0 retorna 409 com orientação ("estorne antes"). Os números 1..n são reaproveitados (o banco só exige unicidade entre as não canceladas).
- [ ] `PATCH /installments/{id}/due-date` (só D, `{dueDate, version}`) só em parcela `OPEN`; parcela `PAID` ou `CANCELED` retorna 409 (`CR009`).
- [ ] **Não existe rota de cancelamento isolado de parcela:** cancelar uma parcela sem substituí-la quebra a soma (`CR011`). O cancelamento só acontece dentro de refazer o plano, e só para parcela `OPEN` com líquido pago zero (inclusive depois de estorno); o `CR011` do banco é a rede.
- [ ] Toda gravação lê a parcela de novo (o banco altera `status` e `version` por trigger) e devolve o recurso atualizado; `version` defasada retorna 409, nunca 500.
- [ ] Parcela de outra clínica retorna 404; recepção, 403.

### US-P3-10: Registrar pagamento
**Descrição:** Como dentista, quero registrar o que o paciente pagou em cada parcela, inclusive parcialmente.

**Critérios de aceite:**
- [ ] `POST /installments/{id}/payments` (só D) recebe `{amount, method, paidOn (opcional)}`; `method` ∈ `CASH`, `PIX`, `DEBIT_CARD`, `CREDIT_CARD`, `BANK_TRANSFER`, `OTHER`. Cria `kind = PAYMENT` e devolve 201 com o pagamento e o `status` atual da parcela (`OPEN` ou `PAID`).
- [ ] `paidOn` ausente = hoje no fuso da clínica; data futura retorna 422 (`reason: FUTURE_DATE`). Não há campo de texto livre.
- [ ] Pagamento maior que o saldo retorna 409 (`CR010`); em parcela `CANCELED`, 409 (`CR010`); `amount` ≤ 0 ou com mais de 2 casas, 400.
- [ ] **Concorrência:** 2 pagamentos simultâneos sobre o último saldo resultam em um 201 e um 409 (meta 3); pagamentos em parcelas diferentes não se bloqueiam.
- [ ] O pagamento do último centavo faz a parcela `PAID` (por trigger); o backend **não** grava o status da parcela.
- [ ] Pagamento em parcela de paciente **inativo** é aceito; de **anonimizado**, 409 (`CR003`). `GET /installments/{id}/payments` (só D) lista pagamentos e estornos em ordem cronológica.
- [ ] Recepção recebe 403; parcela de outra clínica retorna 404.

### US-P3-11: Estornar pagamento
**Descrição:** Como dentista, quero desfazer um lançamento errado sem perder o histórico.

**Critérios de aceite:**
- [ ] `POST /payments/{id}/reversal` (só D, sem corpo ou `{}`) cria uma linha `REVERSAL` com o mesmo valor, a mesma parcela e `reverses_payment_id` do pagamento; o `paidOn` do estorno é hoje no fuso da clínica. A parcela volta a `OPEN` se estava `PAID`.
- [ ] Estorno repetido retorna 409 (`23505` em `ux_pay_reverses` traduzido); estorno de estorno retorna 409 (`CR010`); pagamento de outra clínica retorna 404.
- [ ] Não existe `PUT`, `PATCH` nem `DELETE` em pagamentos; o estorno é total (não há estorno parcial).
- [ ] O estorno gera sua auditoria; a resposta traz o estorno e o `status` atual da parcela.

### US-P3-12: Consultas do financeiro
**Descrição:** Como dentista, quero ver o que está a receber, o que está vencido, o que entrou e o que faturei.

**Critérios de aceite:**
- [ ] `GET /api/v1/finance/receivables?from&to&page&size` (só D): parcelas `OPEN` com vencimento no intervalo (datas locais), com paciente resumido e orçamento (`number`), ordenadas por `dueDate` e `id`; `size` máx. 100.
- [ ] `GET /finance/overdue?page&size` (só D): parcelas `OPEN` com `dueDate` anterior a **hoje na clínica**, mais antigas primeiro; cada item traz `daysOverdue` e saldo; o resumo traz a contagem e a soma em atraso. **Atraso é derivado**, nunca gravado.
- [ ] `GET /finance/cash-flow?from&to` (só D): entradas **líquidas** (Σ `PAYMENT` − Σ `REVERSAL`) por `paid_on`, total e **por dia** e por `method`, janela máx. 366 dias. É regime de **caixa**; só receitas (não há saídas).
- [ ] `GET /finance/revenue?from&to` (só D): faturamento = soma de `total_amount` dos orçamentos `APPROVED` por `decided_at` (competência, período convertido pelo fuso da clínica), com a contagem e a lista resumida por orçamento.
- [ ] `GET /patients/{id}/financial` (só D): ficha com orçamentos aprovados, parcelas (todas), total devido, total pago e em atraso; paciente inativo ou anonimizado continua consultável (anonimizado sem dados pessoais).
- [ ] Meta 6: p95 ≤ 100 ms e **sem `Seq Scan`** nos planos (teste de desempenho em IT, como na Fase 2); janela inválida ou `to < from` retorna 400; recepção recebe 403 em todas.

### US-P3-13: Anonimização com histórico comercial
**Descrição:** Como dentista, quero anonimizar um paciente sem perder o histórico contábil e sem deixar o nome dele em PDF.

**Critérios de aceite:**
- [ ] `POST /patients/{id}/anonymize`: `CR006` do banco (parcela `OPEN`) vira 409 "quite ou cancele as parcelas em aberto antes de anonimizar"; a ordem de precedência dos erros é a do banco (`CR002` → `CR004` → `CR006`).
- [ ] Depois do sucesso, `budgets.notes` do paciente fica nulo; número, total, status, itens, parcelas e pagamentos **permanecem** (conferido por IT); os PDFs ficam com `deleted_at` e deixam de constar na listagem e no download (404).
- [ ] As chaves dos PDFs entram na lista de objetos a apagar, **listadas depois** da função e na mesma transação (como `patient_files`); depois do commit, o backend apaga os objetos. Falha no Storage mantém a anonimização e responde `storageCleanup: PENDING`, como já ocorre hoje.
- [ ] A resposta informa `openBudgets` (contagem de orçamentos `DRAFT`/`SENT` do paciente); não bloqueia.
- [ ] Orçamento novo para paciente anonimizado retorna 409 (`CR003`).

### US-P3-14: Auditoria comercial e financeira
**Descrição:** Como dentista, quero saber quem criou ou alterou orçamentos, parcelas e pagamentos.

**Critérios de aceite:**
- [ ] `GET /audit-log?tableName=budgets|installments|payments|budget_documents&recordId=…` (só D) devolve `INSERT`/`UPDATE`/`ANONYMIZE` (autor, ação, instante), sem valores; `procedure_types` e `budget_items` **não** aparecem (400 para `tableName` fora da lista).
- [ ] A mudança automática de `status` da parcela por pagamento aparece como `UPDATE` da parcela; o estorno aparece como `INSERT` em `payments`.
- [ ] Falha da auditoria do banco (`28000`, sem `app.user_id`) reverte a operação e responde 500 `internal` sem detalhes; nenhum endpoint de escrita na auditoria.

### US-P3-15: Isolamento, papel e plataforma provados
**Descrição:** Como responsável pelo produto, quero garantia técnica de que uma clínica não vê o dinheiro de outra e de que a recepção não alcança o financeiro, mesmo com bug no backend.

**Critérios de aceite:**
- [ ] Com duas clínicas e o filtro Hibernate desligado, nenhuma consulta das seis tabelas novas (inclusive SQL nativo e relatórios) devolve linha da outra.
- [ ] Com `app.role = RECEPTIONIST` forjado: `installments` e `payments` ficam invisíveis (0 linhas); `procedure_types`, `budgets`, `budget_items` e `budget_documents` não aceitam escrita; `app.role` ausente ou inválido nega tudo.
- [ ] A matriz da seção 3 está coberta por teste web (401/403/2xx) e por IT; as rotas novas entram em `Phase3Routes`.
- [ ] `ddl-auto=validate` passa contra V001–V029 com as entidades novas e a `Appointment` ampliada (`@Version Long` onde há `version`; `payments` e `budget_documents` sem `@Version`; `@Enumerated(STRING)`; `insertable/updatable=false` nas colunas preenchidas ou imutáveis).
- [ ] O teste de arquitetura impede ciclo entre módulos e uso de repositório alheio; o teste de higiene de log cobre valores, nomes, observações, chaves e URLs.

---

## 5. Máquinas de estado (validadas no servidor)

**Orçamento.** O banco só nega o que sai de estado terminal, o retorno a `DRAFT` e o congelamento; a coluna **Backend** é regra do servidor.

| De | Ação (rota) | Para | Quem | Regras do backend |
|---|---|---|---|---|
| — | `POST /budgets` | `DRAFT` | só D | paciente ativo e não anonimizado; atendimento do mesmo paciente |
| `DRAFT` | `PUT` (editar) | `DRAFT` | só D | substitui itens; procedimento ativo |
| `DRAFT` | `send` | `SENT` | só D | ≥ 1 item e total > 0; PDF atômico (H1) |
| `DRAFT` | `discard` | `REJECTED` | só D | sem PDF |
| `SENT` | `approve` | `APPROVED` | só D | H3: só a partir de `SENT` |
| `SENT` | `reject` | `REJECTED` | só D | — |
| `SENT`, `APPROVED`, `REJECTED` | `POST /documents` | mesmo status | só D | nova revisão do PDF |
| `APPROVED`, `REJECTED` | qualquer outra | — | — | 409 (terminal) |
| `DRAFT` | `approve` ou `reject` | — | — | 409 (o banco aceitaria `DRAFT → APPROVED`; o backend recusa, H3) |

**Parcela** (o `status` é mantido pelo banco; o backend só cancela):

| De | Evento | Para | Quem |
|---|---|---|---|
| — | gerar plano (orçamento `APPROVED`) | `OPEN` | só D |
| `OPEN` | pagamento que cobre o valor | `PAID` (banco) | só D |
| `PAID` | estorno que reduz o líquido | `OPEN` (banco) | só D |
| `OPEN` (líquido 0) | refazer o plano | `CANCELED` (terminal) | só D |
| `PAID`, `CANCELED` | ajustar vencimento | — | 409 (`CR009`) |

---

## 6. Requisitos funcionais

- **FR-1:** O sistema deve manter o cadastro de tipos de procedimento (nome, preço-base, duração padrão, cor, ativo) com unicidade de nome entre os ativos e sem exclusão.
- **FR-2:** O sistema deve permitir criar e editar rascunhos de orçamento com 1 a 50 itens e desconto em valor nos dois níveis, gravando na ordem exigida pelo banco (cabeçalho → itens → desconto) e relendo as colunas calculadas pelo banco.
- **FR-3:** O sistema deve conduzir `DRAFT → SENT → APPROVED/REJECTED` conforme a seção 5, recusando `approve` e `reject` fora de `SENT` (H3) e toda transição fora da tabela com 409 e o status atual.
- **FR-4:** O sistema deve gerar o PDF do orçamento com OpenPDF, calcular o `sha256`, gravá-lo no Storage com a chave `<clinic>/<patient>/budgets/<uuid>` e registrá-lo em `budget_documents` **na mesma transação do envio** (H1), com compensação em qualquer falha.
- **FR-5:** O sistema deve gerar novas revisões do PDF sem alterar o status, listar as revisões ativas e entregar o download por URL assinada de 60 s, nunca devolvendo a `storage_key`.
- **FR-6:** O sistema deve dividir o total do orçamento aprovado em parcelas nos modos automático e manual (H4), com arredondamento explícito (`RoundingMode.DOWN` nas regulares, a última absorve os centavos), e conferir a soma antes do banco.
- **FR-7:** O sistema deve refazer o plano de parcelas cancelando as abertas sem pagamento e inserindo as novas na mesma transação (sem cancelamento isolado), ajustar vencimento de parcela `OPEN` e **nunca** gravar `PAID`/`OPEN`.
- **FR-8:** O sistema deve registrar pagamentos parciais e estornos totais como linhas novas (`PAYMENT`/`REVERSAL`), sem `UPDATE`/`DELETE`, e traduzir `CR010` em 409; `paidOn` é calculado no fuso da clínica e nunca no futuro.
- **FR-9:** O sistema deve entregar contas a receber, inadimplência (derivada de "hoje" na clínica), fluxo de caixa (regime de caixa) e faturamento (regime de competência) por consultas sobre os índices do banco, com período local da clínica e janela máxima de 366 dias.
- **FR-10:** O sistema deve aceitar `procedureTypeId` em agendamento, validando existência na clínica e atividade, e devolver o resumo do procedimento.
- **FR-11:** O sistema deve traduzir `CR003`, `CR005`–`CR011` e `23505`/`23514`/`23503` das tabelas novas em 4xx com mensagem própria, sem `DETAIL` do driver, e tratar `CR011` no `COMMIT`.
- **FR-12:** O sistema deve aplicar controle otimista (`version`) em procedimento, orçamento e parcela, e responder 409 também quando o estado mudou por trigger ou concorrência (nunca 500).
- **FR-13:** O sistema deve negar por papel no servidor (`SecurityConfig` e `@PreAuthorize`) antes de qualquer acesso ao banco, restringir a recepção à leitura de procedimentos, orçamentos e PDFs (seção 3) e nunca aceitar `clinicId`, `createdBy`, `role`, `status`, `number`, `totalAmount` ou `kind` do cliente.
- **FR-14:** O sistema deve definir `app.clinic_id`, `app.role` e `app.user_id` em toda transação (ponto único da Fase 1), em `READ COMMITTED`, e usar `created_by = app.user_id` em toda gravação nova.
- **FR-15:** O sistema deve tratar a anonimização conforme a US-P3-13 (inclui `CR006`, PDFs e chaves de Storage) e aceitar as quatro tabelas novas em `tableName` da auditoria.
- **FR-16:** O sistema deve manter valores monetários, nomes de paciente, observações, conteúdo de PDF, chaves de objeto e URLs assinadas fora de logs, `toString` e mensagens de erro; eventos de log levam só identificadores, status e contagens.
- **FR-17:** Plataforma: mapear as entidades novas e a `Appointment` ampliada com `ddl-auto=validate` contra V001–V029; nenhuma migration e nenhuma alteração em `database/`; adicionar a dependência OpenPDF ao `pom.xml`.

---

## 7. Non-goals (fora de escopo)

- Envio por e-mail, WhatsApp ou SMS, assinatura digital do PDF, aceite do paciente pelo link, versionamento de layout e modelo de PDF configurável.
- Cancelamento, expiração automática, duplicação e versionamento de orçamento (`validUntil` é só informativo); aprovação direta `DRAFT → APPROVED` (H3).
- Juros, multa, mora, correção, desconto por parcela, renegociação formal, parcelamento no cartão com operadora (taxas, antecipação), boleto, PIX com integração externa e conciliação bancária.
- Nota fiscal, recibo fiscal e qualquer numeração fiscal.
- Estorno parcial, pagamento maior que o saldo (crédito do paciente), troca de forma de pagamento sem estorno e idempotência de pagamento por chave.
- Contas a pagar, despesas, estoque e fluxo de caixa com **saídas**; relatórios exportáveis (CSV/PDF) e gráficos.
- Dashboard e KPIs ("faturamento do mês" no painel): Fase 4 (esta fase entrega as consultas).
- Odontograma, dente/face por item, plano de tratamento clínico, preço por convênio, tabela de preços múltiplas, comissão e multimoeda.
- Seed de procedimentos padrão (a clínica cadastra os seus).
- Auditoria de **leitura** e comparação de valores antigo/novo (como na Fase 2).
- Reconciliação automática de objetos órfãos no Storage (apenas evento de log) e backup do bucket.
- Migration nova ou qualquer alteração de banco (a função `banco` não é acionada); frontend (consumidor desta API; ainda não iniciado).
- Duração do agendamento calculada pelo backend a partir do procedimento (o front propõe com `defaultDurationMinutes`).

## 8. Requisitos de IA

Não se aplica.

---

## 9. Especificações técnicas e considerações de design

**Arquitetura (fluxo):**
```
React ─▶ /api/v1/** ─▶ filtro JWT (clinic_id, user_id, role) ─▶ controller ─▶ service (@PreAuthorize, @Transactional)
                                                            │
                        transação: set_config(app.clinic_id, app.role, app.user_id, true) + filtro Hibernate (READ COMMITTED)
                                                            │
 catalog ─▶ core.procedure_types ◀─ FK ─ core.appointments.procedure_type_id (scheduling)
 commercial ─▶ core.budgets ─▶ core.budget_items   (itens só em DRAFT; total pelo banco)
            ─▶ core.budget_documents ◀──── FileStorage (Supabase/Local): <clinic>/<patient>/budgets/<uuid>
 finance ─▶ core.installments ─▶ core.payments (append-only; status da parcela por trigger)
         ─▶ consultas: receivables / overdue / cash-flow / revenue    ──▶ core.audit_log (budgets, installments, payments, budget_documents)
 patient ──interface de contribuição──▶ chaves de Storage (record e commercial) na anonimização
```

**Fluxo de envio (H1), resumido:** `TransactionTemplate` → `UPDATE budgets SET status='SENT'` (carimba `sent_at`, congela) → reler orçamento e itens → OpenPDF em memória → `sha256` → `FileStorage.save` → `INSERT budget_documents` → commit. No `catch`: `FileStorage.delete` do objeto (se foi gravado). Detalhes (timeouts, pontos de falha, ordem do commit) ficam para a spec.

**Pontos de integração:**
- **Banco (contrato, spec do banco da Fase 3, seções 2.3, 4 e 6):** nomes de tabelas e colunas, variáveis de sessão, constraints úteis (`ux_ptype_clinic_name`, `ux_budgets_clinic_number`, `ux_items_budget_position`, `ux_inst_budget_number`, `ux_pay_reverses`, `ck_budgets_total`, `ck_items_line`) e SQLSTATE: `23514`, `23505`, `23503`, `42501`, `28000`, `CR003`, `CR005`–`CR011`. Colunas preenchidas por trigger mapeadas como não inseríveis/atualizáveis; `installments.status` não atualizável.
- **Fase 0/1/2:** `TenantContext`/`TenantHibernateJpaDialect`, `GlobalExceptionHandler`, `DatabaseErrors`, `ProblemTypes` (`CONFLICT` 409, `BUSINESS_RULE` 422, `UNAVAILABLE` 503 já existem), `PageResponse`, `Role`, `TokenPrincipal`, `Clock` injetável (testes de "hoje"), `ClinicZones` (fuso da clínica), `PerfLimits`, `Phase*ItSupport`, `FileStorage` e `SupabaseFileStorage`/`LocalFileStorage`, `SignedUrlResponse`, `ClinicalCacheHeaders`.
- **Storage:** mesmo bucket e variáveis `CRM_STORAGE_*` dos exames; prefixo `budgets/`. O bucket privado e os limites (B5/B6 do checklist) seguem **pendência de segurança** registrada; o PDF contém o nome do paciente e não tem RLS.
- **Auditoria:** os logs de orçamento e financeiro seguem o padrão após commit dos `*AuditLogger` existentes (só identificadores).

**Segurança e privacidade:**
- O papel vem **só** do JWT validado; o `clinic_id` nunca vem do corpo ou da URL. Dado financeiro é dado pessoal sensível por contexto: RLS por clínica **e por papel** (recepção sem parcela e pagamento), reforçada pelo `SecurityConfig` (403 antes do banco).
- Nenhum campo da fase é cifrado (dinheiro precisa de soma e índice). Texto livre só em `budgets.notes` (≤ 500, vai no PDF, zerado na anonimização) e `appointments.notes` (Fase 2); `payments` não tem texto livre.
- O PDF contém o nome do paciente e **não** o CPF; o objeto só sai por URL assinada de 60 s, gerada pelo backend depois de checar tenant e papel; a `storage_key` nunca é devolvida nem logada.
- Histórico imutável: pagamento sem `UPDATE`/`DELETE`, estorno por nova linha, autoria em `created_by` e auditoria sem valores.
- O banco confia no `app.role` que o backend informa (limite aceito do papel único `crm_app`): o papel errado no JWT abre o financeiro. O risco aceito do lock consultivo da numeração (`crm_app` pode tomar o lock de qualquer clínica) permanece.
- Limites de upload do envio: PDF ≤ 5 MB; o conteúdo é gerado pelo servidor, nunca recebido do cliente (sem risco de arquivo malicioso).

**UI/UX:** não se aplica (API). O front deve tratar os `reason` do 422 (`PROCEDURE_INACTIVE`, `FUTURE_DATE`), exibir os totais como texto decimal sem converter para ponto flutuante e propor a duração do agendamento a partir de `defaultDurationMinutes`.

---

## 10. Riscos e perguntas abertas

**Riscos técnicos**
1. **Upload dentro da transação (H1) segura uma conexão do pool durante a chamada ao Supabase Storage** e o backend usa o pooler em modo transação (porta 6543). Um upload lento esgota o pool pequeno do plano gratuito. *Mitigação:* PDF pequeno e gerado em memória, timeout configurável (padrão 10 s), rate limit nas rotas `send`/`documents` (mesmo padrão do upload de exames), conexão devolvida logo após o commit. A alternativa em duas fases foi recusada pelo usuário.
2. **Compensação pode falhar** (objeto gravado e `ROLLBACK`/commit falho, seguido de falha ao apagar): sobra um objeto órfão no bucket, sem linha no banco. *Mitigação:* evento de log com identificador do orçamento; reconciliação fora de escopo (Non-goals) e risco aceito, com o `sha256` e a ordem de gravação limitando o dano.
3. **`CR011` só aparece no `COMMIT`:** o `flush` não basta e o JPA pode mascarar a exceção. *Mitigação:* `TransactionTemplate` no fluxo de parcelas, tradução no commit e teste dedicado de plano com soma errada forçada.
4. **Colunas preenchidas por trigger** (`number`, `total_amount`, carimbos, `unit_price`, `line_total`, `revision`, `status` da parcela): a entidade JPA fica desatualizada após `persist`/`update`. *Mitigação:* mapeamento somente leitura, releitura obrigatória antes de responder, `@Version` da parcela (o banco soma 1 ao mudar o status) e teste de `ddl-auto=validate`.
5. **Dois cliques no pagamento geram dois lançamentos** (sem idempotência). *Mitigação:* o estorno desfaz; o front desabilita o botão; revisar com `Idempotency-Key` na v1.1 se a prática mostrar o problema.
6. **Numeração com `max + 1` sob `READ COMMITTED`:** qualquer mudança de isolamento no `TenantJpaTransactionManager` quebra a numeração (`23505`). *Mitigação:* teste de concorrência (meta 3) e uma asserção de isolamento padrão.
7. **Uso de `READ COMMITTED` e estimativa de saldo exibida ao usuário** pode ficar defasada entre a leitura e o pagamento; a autoridade é o banco sob trava (`CR010` → 409). O front deve recarregar após 409.
8. **Ciclo de módulos e anonimização:** a coleta das chaves de Storage dos PDFs exige a interface de contribuição em `patient` (seção 2); se mal desenhada, reintroduz o ciclo. *Mitigação:* `Phase3ArchitectureTest`.
9. **Mudança de comportamento das Fases 1 e 2:** anonimização ganha `CR006`; a `Appointment` e a resposta de agendamento ganham `procedure`. *Mitigação:* a verificação da fase reexecuta as suítes das Fases 1 e 2.
10. **Bucket sem limite de 10 MB nem MIME permitidos** (checklist B5/B6, pendência herdada) e **perda do bucket** deixa `budget_documents` órfãos; o backend consegue regenerar o PDF a partir do orçamento congelado (nova revisão). Backup do bucket segue fora do escopo.
11. **Teste `23P01`/`CR004` no Supabase real** e a validação da spec 4.6 da Fase 2 não bloqueiam a fase, mas continuam sem dono.
12. **A V022–V029 já está no Supabase real**, mas o ponto 1 da seção 10 da spec do banco (lock consultivo e constraint triggers adiáveis atrás do pooler 6543) só será confirmado de fato por este backend. Se falhar, o problema é do pooler e não do código.

**Conflitos e lacunas sinalizados**
- O `AGENTS.md` da raiz está vazio; stack, papéis e fases foram lidos de `arq_inicial.md` e dos PRDs/specs. Vale preenchê-lo.
- **`FileStorage` diz "nunca dentro de transação"** e `StorageKeys` só aceita a chave de 3 UUIDs; a decisão H1 e a chave `budgets/` exigem alterar os dois (e a documentação do contrato).
- A spec do banco permite `DRAFT → APPROVED` (S6); o backend é mais restritivo por H3. Não é divergência de contrato, é regra do servidor.
- A spec do banco 6.3 fala em aprovar e gerar as parcelas "na mesma transação ou depois"; este PRD escolhe **duas chamadas** (`approve` e `installments`), por ser menos acoplado e permitir revisar o plano antes de gerar.
- O PRD do banco diz que a anonimização "marca" os PDFs e o backend "concilia" falhas de Storage; o fluxo atual de `PatientAnonymizationService` já devolve `PENDING` e é reaproveitado, sem job de conciliação.
- `PatientRules.CLINIC_ZONE` (Fase 2) segue como está; esta fase usa o fuso da clínica (`ClinicZones`) para "hoje" e períodos.

**Perguntas abertas**
- ~~Remoção de item~~ **Resolvido (2026-10-26):** só por `PUT` substitutivo, sem endpoint de item avulso.
- ~~`discard` como rota própria~~ **Resolvido (2026-10-26):** rota própria; `reject` em `DRAFT` retorna 409.
- ~~Vencimento da entrada~~ **Resolvido (2026-10-26):** vence na data da geração do plano (data local da clínica).
- ~~Busca por número do orçamento~~ **Resolvido (2026-10-26):** **sim**, filtro `number` (número exato) em `GET /budgets`.
- Exportação de dados do titular e prazo de 20 anos de guarda (herdadas das Fases 1 e 2; não mudam nesta fase).
- Armazenamento do refresh token no React (herdado, pendente da spec do frontend).

**Lançamento em etapas**
- **MVP (Fase 3):** US-P3-01 a US-P3-15.
- **v1.1:** `Idempotency-Key` em pagamento, exportação CSV dos relatórios, conciliação de objetos órfãos do Storage.
- **v2.0:** e-mail e WhatsApp do orçamento, aceite do paciente pelo link, estorno parcial, crédito do paciente, contas a pagar e fluxo de caixa com saídas, juros e multa.

**Fatiamento sugerido para o `breakdown` (referência, a confirmar após aprovação):** (1) plataforma da fase (`pom` com OpenPDF, `StorageKeys`/`FileStorage` com `budgets/`, tradução de `CR006`–`CR011`, interface de contribuição de chaves, `Phase3ArchitectureTest`, `ddl-auto=validate` das entidades novas); (2) catálogo de procedimentos e procedimento na agenda (`catalog` + `scheduling`); (3) orçamento em rascunho (criar, editar, consultar, numeração, itens); (4) envio, PDF e revisões (H1, Storage, download); (5) decisão e plano de parcelas (aprovar, recusar, descartar, gerar e refazer parcelas, ajustar vencimento); (6) pagamentos, estornos e consultas do financeiro (recebíveis, inadimplência, fluxo de caixa, faturamento, ficha); (7) integração com o núcleo clínico (anonimização com `CR006` e PDFs, auditoria das quatro tabelas); (8) verificação da fase (matriz de papéis, isolamento com filtro desligado e papel forjado, concorrência, atomicidade do envio, invariantes, desempenho, log limpo, rastreabilidade). Ordem: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8.
