# PRD Global (Banco) — Fase 3: Comercial e Financeiro

**Escopo:** modelo de dados, papéis, RLS, integridade monetária, auditoria e migrations da **Fase 3** (tipos de procedimento com preço-base, orçamento, parcelas, pagamentos e a base de consulta do fluxo de caixa), sobre as Fases 0, 1 e 2.
**Fontes:** `docs/arq_inicial.md` (módulos Orçamento & Tratamento e Financeiro, Fase 3), `docs/global/banco/Fase-{0,1,2}/{prd,spec}.md`, `docs/global/backend/Fase-{0,1,2}/`, `database/` (migrations V001–V021, bootstrap, rollback, testes) e `back-java/` (módulos existentes e o que já aponta para a Fase 3). O `AGENTS.md` da raiz está vazio.
**Função no pipeline:** `banco`. O backend não cria nem altera objetos de banco. **Não há docs de backend da Fase 3 ainda:** o contrato é a proposta do banco e deve ser conciliado quando o backend planejar a fase.

> **Nota (2026-10-26):** onde este PRD e a `spec.md` divergem, **vale a spec** (decisões S1–S16 e seção 11 da spec): número do orçamento por lock consultivo, carimbos e snapshot de preço pelo banco, parcela cancelável com líquido zero, `UPDATE (deleted_at)` do `crm_definer` em `budget_documents`, códigos `CR006`–`CR011`.

## Decisões fechadas

Herdam B1–B8 (Fase 0), P1–P5 (Fase 1) e A1–A12 (Fase 2). Novas, confirmadas em 2026-10-26 na entrevista de descoberta:

| # | Decisão | Valor |
|---|---|---|
| C1 | Integridade | **O banco reforça o essencial do dinheiro** (diferente da Fase 2, em que as transições ficaram só no backend): itens do orçamento só mudam em `DRAFT`; `total_amount` = soma dos itens − desconto; orçamento `APPROVED`/`REJECTED` é terminal; parcelas só nascem de orçamento `APPROVED` e somam o total; pagamentos são **append-only**. A ordem entre os demais status segue no backend |
| C2 | Papéis | `procedure_types`: os dois papéis leem, só `DENTIST_ADMIN` grava. `budgets` e itens: os dois leem, só `DENTIST_ADMIN` grava. `installments` e `payments`: **só `DENTIST_ADMIN`** lê e grava. `RECEPTIONIST` não vê parcela nem pagamento |
| C3 | Pagamento | Tabela `payments` separada, **pagamento parcial permitido**; parcela vira `PAID` quando o líquido pago cobre o valor; estorno é **nova linha** (`REVERSAL`), nunca `UPDATE`/`DELETE`. Atraso é **derivado** (vencida e não paga), não gravado |
| C4 | LGPD | Orçamentos, parcelas e pagamentos **permanecem** após a anonimização do paciente (obrigação contábil); textos livres são zerados; anonimizar falha com **`CR006`** se houver parcela com saldo em aberto |
| C5 | PDF do orçamento | O PDF **enviado** é guardado no **Supabase Storage** (mesmo bucket privado e variáveis `CRM_STORAGE_*` dos exames da Fase 1), com metadados em `core.budget_documents`. Só nasce depois que o orçamento sai de `DRAFT`. Decidido em 2026-10-26, a pedido do usuário |

## Padrões adotados (sem pergunta; contestar se discordar)

- **PDF do orçamento (C5):** uma linha por arquivo gerado, **append-only** como `payments`, no padrão de `patient_files` (V011): `storage_key` `<clinic_id>/<patient_id>/budgets/<uuid>` (único, validado por `CHECK`), `content_type = 'application/pdf'`, `size_bytes` de 1 a 5 MB, `sha256` (`varchar(64)`, hex minúsculo) para detectar corrupção ou troca do objeto, `revision` (`smallint`, 1, 2, … por orçamento; reenvio gera nova revisão sem apagar a anterior), `generated_at`. Só entra com o orçamento em `SENT`, `APPROVED` ou `REJECTED`: como os itens congelam ao sair de `DRAFT`, o PDF corresponde exatamente ao conteúdo enviado. O binário é do Storage; o banco guarda só o ponteiro.
- **O PDF contém o nome do paciente** (dado pessoal, ao contrário das linhas financeiras). Por isso a anonimização **apaga os PDFs**: o banco marca `deleted_at` e o backend remove os objetos depois (mesmo fluxo de `patient_files`); orçamento, parcelas e pagamentos permanecem. *(Contestável: a alternativa de reter o PDF como comprovante contábil conflita com a LGPD e fica de fora.)*
- **Leitura do PDF:** os dois papéis leem os metadados (a recepção imprime/reenvia o orçamento, coerente com C2); só `DENTIST_ADMIN` insere e marca `deleted_at`. O download é sempre pelo backend (URL assinada de curta duração ou proxy); o bucket não é público.

- **Convenções** das Fases 0 a 2: `snake_case`, plural, `uuid` gerado pela aplicação, `varchar(n)` + `CHECK`, `timestamptz`, `updated_at` por trigger, `version bigint`, FKs compostas `(x_id, clinic_id)` `ON DELETE RESTRICT`, `created_by` como FK composta para `users` com `created_by = app.user_id` no `INSERT`, política `SELECT TO crm_backup USING (true)` em toda tabela com `clinic_id`, funções de sessão como `(SELECT core.current_app_role())`, `OR` de igualdades em vez de `IN` em predicados e políticas.
- **Schema `core`** (mantém A1). A Fase 2 pediu para "reavaliar" um schema `billing`/`finance`; a decisão é **não separar**: as FKs compostas, as políticas, as funções de sessão e a auditoria já estão em `core`, e separar custaria `GRANT USAGE`, `search_path` e FKs entre schemas sem ganho para um monólito modular com banco único.
- **Dinheiro:** `numeric(12,2)` em BRL, sem `float`; valores ≥ 0 e teto de R$ 999.999,99 por campo (`CHECK`). Moeda única; sem conversão.
- **Congelamento de preço (snapshot):** o item do orçamento grava `description` e `unit_price` copiados do procedimento no momento da criação. Mudar o preço-base depois **não** altera orçamentos existentes, e o PDF de um orçamento enviado é sempre reprodutível.
- **Procedimento nunca é apagado:** `procedure_types` usa `active`, não `DELETE` (agendamentos e itens apontam para ele). Nome único por clínica entre os ativos, sem diferenciar caixa.
- **Duração padrão e cor** moram em `procedure_types` (`default_duration_minutes`, múltiplo de 5 entre 5 e 720; `color` no formato `#RRGGBB`). O banco **não** preenche a duração do agendamento: o backend lê e propõe.
- **FK de `appointments.procedure_type_id`** entra agora (era nulo e sem FK na Fase 2, spec S12): FK composta `(procedure_type_id, clinic_id)` `ON DELETE RESTRICT`, índice parcial `WHERE procedure_type_id IS NOT NULL`. Linhas existentes têm o campo nulo (o backend da Fase 2 nunca o mapeou), então a migration é aditiva.
- **Numeração do orçamento:** `number` inteiro sequencial **por clínica** (`UNIQUE (clinic_id, number)`), atribuído pelo banco sob serialização por clínica; **podem existir buracos** (rollback de transação). Não é número fiscal. *(Contestável.)*
- **Status do orçamento (lista fixa):** `DRAFT`, `SENT`, `APPROVED`, `REJECTED` (rascunho, enviado, aprovado, recusado). Sem cancelamento nem expiração no banco (ver Non-goals). `sent_at` e `decided_at` guardam as datas; o `CHECK` as amarra ao status.
- **Status da parcela:** `OPEN`, `PAID`, `CANCELED`. `PAID` é consequência dos pagamentos (C3), mantido de forma consistente pelo banco. `CANCELED` só em parcela `OPEN` **sem pagamento**, só por `DENTIST_ADMIN`; serve para refazer o plano de parcelas, e a soma da regra de total conta só as não canceladas.
- **Entrada** é a parcela nº 1 com vencimento no dia; não existe conceito separado.
- **Arredondamento:** o backend divide o total em parcelas e a última absorve os centavos; o banco só confere que a soma fecha.
- **Pagamento:** `paid_on` é `date` (data civil do recebimento, calculada pelo backend no fuso da clínica); `created_at` registra o instante do lançamento. Forma: `CASH`, `PIX`, `DEBIT_CARD`, `CREDIT_CARD`, `BANK_TRANSFER`, `OTHER`. **Sem texto livre** em `payments` (nada a limpar na anonimização, e a tabela pode ser puramente append-only). Não pode exceder o saldo da parcela; estorno é total (um por pagamento), e a parcela volta a `OPEN`.
- **Textos livres** só em `budgets.notes` (até 500 caracteres, aparece no PDF; zerado na anonimização) e `appointments.notes` (Fase 2). Descrição do item é cópia do nome do procedimento, não dado pessoal.
- **Paciente anonimizado** não recebe orçamento novo (`CR003`) e **inativo** também não (`CR005`), com o mesmo padrão de trigger da Fase 2. Cobrança de parcela e pagamento de quem foi inativado seguem permitidos (a dívida continua).
- **Relatórios:** *entradas do mês* e *fluxo de caixa* = pagamentos líquidos por `paid_on` (regime de caixa); *faturamento do período* = soma dos orçamentos `APPROVED` por `decided_at` (regime de competência); *inadimplência* = parcelas `OPEN` com `due_date` anterior ao "hoje" que o backend passa. O banco entrega **índices**, não views nem tabelas de resumo (ver Non-goals). *(Definição de faturamento contestável.)*
- **Auditoria:** `budgets`, `installments` e `payments` entram no `audit_log` (sem valores, só identificadores, como `appointments`); `procedure_types` e itens **não** (configuração e dado derivado do orçamento, cujo cabeçalho é auditado). *(Contestável.)*
- **Sem seed de procedimentos:** a clínica cadastra os seus (restauração, canal etc.). Seed seria preço inventado.
- **Pendências herdadas da Fase 2** que a Fase 3 deve fechar ou carregar: spec 4.6 (pré-checagem de conflito por intervalo, `&&` de `tstzrange` não usa índice sob RLS), teste 84 (`ddl-auto=validate` do backend da Fase 2, hoje possível) e teste de conflito `23P01` no Supabase real.

---

## 1. Resumo executivo

**Problema:** o dentista solo precisa cadastrar o que cobra, montar e enviar orçamentos, e acompanhar o que o paciente já pagou e o que está vencido, sem que um erro de arredondamento, uma edição depois do envio ou um pagamento lançado duas vezes deixe o caixa inconsistente, e sem que a recepção veja valores de parcela e pagamento.

**Solução:** seis tabelas novas em `core` (`procedure_types`, `budgets`, `budget_items`, `budget_documents`, `installments`, `payments`), uma FK aditiva em `appointments`, RLS por clínica e por papel, **invariantes monetárias no próprio banco** (total, soma das parcelas, saldo, pagamento append-only), auditoria de orçamento, parcela e pagamento, e ajuste da anonimização.

**Metas e métricas:**
1. Sob `app.clinic_id = A`, 0 linhas das clínicas B nas 6 tabelas novas, para `SELECT`, `UPDATE` e `INSERT` (testes de isolamento).
2. Com `app.role = RECEPTIONIST`: 0 linhas visíveis em `installments` e `payments`; 0 gravações possíveis em `procedure_types`, `budgets` e `budget_items`; leitura de `procedure_types`, `budgets` e `budget_items` permitida. Com `app.role` ausente ou inválido, tudo negado.
3. **Invariantes de dinheiro (teste de propriedade):** em qualquer sequência válida de operações, (a) `budgets.total_amount` = Σ (itens com desconto de item) − desconto do orçamento; (b) Σ parcelas não canceladas = `total_amount` do orçamento `APPROVED`; (c) 0 parcelas com pagamento líquido > valor; (d) `status = 'PAID'` ⇔ líquido ≥ valor. Qualquer tentativa de violar falha com erro mapeável, sem alterar dados.
4. Concorrência: dois pagamentos simultâneos sobre o último saldo da mesma parcela: exatamente um passa e o outro falha; em parcelas diferentes, ambos passam.
5. 100% dos `INSERT/UPDATE` em `budgets`, `installments` e `payments` geram 1 linha em `audit_log`, sem valores; `crm_app` sem `UPDATE`/`DELETE` em `payments` e sem `DELETE` em qualquer tabela da fase.
6. Desempenho com **5 mil parcelas e 5 mil pagamentos por clínica** e **100 mil de cada no banco**, sem `Seq Scan`: parcelas vencidas ≤ 10 ms, soma de recebimentos do mês ≤ 10 ms, lista de orçamentos do paciente ≤ 5 ms.
7. Migrations da Fase 3 aditivas, aplicam do zero sobre V001–V021, com rollback documentado; o `pg_dump` do `crm_backup` traz as 6 tabelas novas e a restauração fecha as contagens.

---

## 2. User stories

### US-301: Tipos de procedimento
**Descrição:** Como dentista, quero cadastrar os procedimentos que realizo, com preço-base, duração e cor, para usar na agenda e no orçamento.

**Critérios de aceite:**
- [ ] `core.procedure_types`: `name` (`varchar(80)`), `base_price` (`numeric(12,2)`, ≥ 0), `default_duration_minutes` (`smallint`, múltiplo de 5, 5 a 720), `color` (`char(7)`, `#RRGGBB`), `active` (default `true`), `created_by`, `created_at`, `updated_at`, `version`; `UNIQUE (id, clinic_id)` (alvo das FKs compostas).
- [ ] Nome único por clínica entre os ativos, sem diferenciar caixa (índice único parcial em `lower(name)`); o mesmo nome em outra clínica passa; um nome inativo pode ser reutilizado.
- [ ] RLS `ENABLE` + `FORCE`; `SELECT` para os dois papéis; `INSERT` e `UPDATE` só `DENTIST_ADMIN`; sem `DELETE`.
- [ ] `clinic_id`, `id`, `created_by` e `created_at` imutáveis (trigger); `updated_at` por trigger.
- [ ] Preço negativo, duração fora da faixa, cor mal formada e nome em branco falham com `23514`.

### US-302: Procedimento no agendamento
**Descrição:** Como dentista, quero que o agendamento aponte para o procedimento a realizar.

**Critérios de aceite:**
- [ ] FK composta `(procedure_type_id, clinic_id)` → `procedure_types (id, clinic_id)` `ON DELETE RESTRICT` em `appointments`; migration aditiva, sem reescrever linhas, com `NOT VALID` + `VALIDATE` se o volume pedir.
- [ ] Índice parcial `(clinic_id, procedure_type_id) WHERE procedure_type_id IS NOT NULL`.
- [ ] Teste: procedimento de outra clínica falha com `23503`; nulo passa; a política de `UPDATE` da recepção e a máquina da Fase 2 continuam como estão (os testes 59–85 seguem verdes).

### US-303: Orçamento
**Descrição:** Como dentista, quero montar um orçamento para um paciente com itens e desconto, e enviá-lo, sem que ele mude depois de enviado.

**Critérios de aceite:**
- [ ] `core.budgets`: `number` (por clínica, atribuído pelo banco), `patient_id` (FK composta, `RESTRICT`), `appointment_id` (nulo; FK composta `(appointment_id, patient_id, clinic_id)`, atendimento de origem), `status` (lista acima, default `DRAFT`), `discount_amount` (`numeric(12,2)`, ≥ 0, default 0), `total_amount` (`numeric(12,2)`, ≥ 0), `valid_until` (`date`, nulo, informativo), `notes` (nulo, até 500), `sent_at`, `decided_at`, `created_by`, `created_at`, `updated_at`, `version`; `UNIQUE (clinic_id, number)` e `UNIQUE (id, patient_id, clinic_id)`.
- [ ] `core.budget_items`: `budget_id` (FK composta `(budget_id, clinic_id)`, `RESTRICT`), `procedure_type_id` (FK composta, `RESTRICT`), `description` (`varchar(120)`), `quantity` (`smallint`, 1 a 999), `unit_price` (`numeric(12,2)`, ≥ 0), `discount_amount` (`numeric(12,2)`, ≥ 0, default 0; desconto em valor sobre a linha), `line_total` (coluna gerada `quantity * unit_price - discount_amount`, com `CHECK (line_total >= 0)`: desconto de item maior que a linha falha com `23514`), `position` (ordem na lista; `UNIQUE (budget_id, position)`). Máximo de 50 itens por orçamento (trigger).
- [ ] `total_amount` é mantido pelo banco: Σ `line_total` − `budgets.discount_amount` (desconto **do orçamento**, aplicado sobre a soma já com os descontos dos itens), nunca negativo (desconto maior que a soma falha com `23514`). Descontos em valor, não em percentual: o backend converte. O backend não grava o total.
- [ ] Itens só podem ser inseridos, alterados ou removidos com o orçamento em `DRAFT`; depois, falha. `discount_amount`, `patient_id`, `appointment_id` e `number` congelam ao sair de `DRAFT`. Remover item de rascunho é o **único** `DELETE` permitido ao `crm_app` na fase (ver US-308).
- [ ] `status`: `APPROVED` e `REJECTED` são terminais (qualquer `UPDATE` de coluna de negócio falha); nenhum retorno a `DRAFT`; `sent_at` preenchido a partir de `SENT`, `decided_at` só em `APPROVED`/`REJECTED`, validado por `CHECK`. Enviar orçamento sem itens ou com total zero falha.
- [ ] Trigger de paciente (padrão da Fase 2): `INSERT` para anonimizado → `CR003`; para inativo → `CR005`.
- [ ] RLS: `SELECT` para os dois papéis; `INSERT` e `UPDATE` só `DENTIST_ADMIN`, com `created_by = app.user_id`; mesma regra para os itens. Número: dois `INSERT` simultâneos na mesma clínica recebem números distintos.

### US-304: Parcelas
**Descrição:** Como dentista, quero gerar as parcelas de um orçamento aprovado para acompanhar o que o paciente deve.

**Critérios de aceite:**
- [ ] `core.installments`: `budget_id` e `patient_id` (FK composta `(budget_id, patient_id, clinic_id)` → `budgets`), `number` (`smallint`, ≥ 1, `UNIQUE (budget_id, number)`), `amount` (`numeric(12,2)`, > 0), `due_date` (`date`), `status` (`OPEN`, `PAID`, `CANCELED`; default `OPEN`), `created_by`, `created_at`, `updated_at`, `version`; `UNIQUE (id, clinic_id)`.
- [ ] `INSERT` só se o orçamento estiver `APPROVED`; ao fim da transação a soma das parcelas **não canceladas** do orçamento deve ser **igual** ao `total_amount` (constraint trigger adiável), ou 0 parcelas ativas. Soma diferente falha no `COMMIT`.
- [ ] `amount` e `budget_id` congelam depois de criados; `due_date` pode mudar enquanto `OPEN` (renegociar data); `CANCELED` só de `OPEN` sem pagamento.
- [ ] Máximo de 60 parcelas por orçamento; `due_date` ≥ 2000-01-01.
- [ ] RLS: só `DENTIST_ADMIN` lê, grava e cancela; `RECEPTIONIST` enxerga 0 linhas; sem `DELETE`.

### US-305: Pagamentos e estorno
**Descrição:** Como dentista, quero registrar o que o paciente pagou em cada parcela, inclusive parcialmente, e estornar um lançamento errado sem perder o histórico.

**Critérios de aceite:**
- [ ] `core.payments`: `installment_id` (FK composta `(installment_id, clinic_id)`, `RESTRICT`), `kind` (`PAYMENT` ou `REVERSAL`), `amount` (`numeric(12,2)`, > 0), `method` (lista da seção de padrões), `paid_on` (`date`), `reverses_payment_id` (nulo; obrigatório se `REVERSAL`, nulo se `PAYMENT`; FK composta; `UNIQUE` parcial: um estorno por pagamento), `created_by`, `created_at`. Sem `updated_at` e sem `version`: a linha não muda.
- [ ] `crm_app` com **só** `SELECT` e `INSERT` em `payments`; nenhuma política de `UPDATE` ou `DELETE`; trigger também nega `UPDATE`/`DELETE` ao `crm_owner` fora de migration.
- [ ] Líquido pago da parcela (Σ `PAYMENT` − Σ `REVERSAL`) nunca passa de `amount` e nunca fica negativo; lançar mais que o saldo falha. O estorno repete o valor e a parcela do pagamento original, e não se estorna um estorno.
- [ ] Pagamento em parcela `CANCELED` falha. A parcela vira `PAID` quando o líquido atinge o `amount` e volta a `OPEN` quando um estorno o reduz; o `status` não pode ser gravado fora disso (`PAID` sem líquido suficiente ou `OPEN` com líquido cheio falha).
- [ ] Serialização por parcela: pagamentos concorrentes na mesma parcela são ordenados por trava de linha da parcela; o segundo vê o saldo já atualizado.
- [ ] RLS: só `DENTIST_ADMIN` lê e insere, com `created_by = app.user_id`.

### US-306: Consultas do financeiro
**Descrição:** Como dentista, quero ver o que está vencido, o que entrou no mês e o que faturei, com resposta imediata.

**Critérios de aceite:**
- [ ] Índice `installments (clinic_id, due_date, id) WHERE status = 'OPEN'` atende contas a receber e inadimplência (o backend passa `hoje`).
- [ ] Índice `installments (clinic_id, patient_id, due_date, id)` atende a ficha do paciente e a FK composta; `installments (budget_id)` coberto pela chave composta e pelo `UNIQUE (budget_id, number)`.
- [ ] Índice `payments (clinic_id, paid_on, id)` atende entradas do mês e fluxo de caixa; `payments (installment_id)` atende saldo da parcela e a FK; `payments (clinic_id, reverses_payment_id)` parcial.
- [ ] Índice `budgets (clinic_id, decided_at) WHERE status = 'APPROVED'` atende o faturamento por período; `budgets (clinic_id, patient_id, created_at DESC, id DESC)` atende a lista do paciente; `budgets (clinic_id, status, created_at DESC)` atende a lista por status.
- [ ] `EXPLAIN` documentado para cada consulta crítica, com as metas da seção 1 e sem `Seq Scan`.

### US-307: Auditoria da fase
**Descrição:** Como dentista, quero saber quem criou ou alterou orçamentos, parcelas e pagamentos.

**Critérios de aceite:**
- [ ] `core.audit_log.table_name` aceita também `'budgets'`, `'installments'`, `'payments'` e `'budget_documents'` (`CHECK` ampliado em migration própria); trigger `AFTER INSERT OR UPDATE OR DELETE` em cada uma via `core.write_audit()` (sem valores; falha com `28000` sem `app.user_id`).
- [ ] `procedure_types` e `budget_items` **não** entram na auditoria.
- [ ] Falha de auditoria reverte a operação inteira (como na Fase 2).
- [ ] A atualização automática do `status` da parcela por pagamento também gera sua linha de auditoria.

### US-308: Menor privilégio e exclusão
**Descrição:** Como titular, quero que a aplicação não consiga apagar nem reescrever o histórico financeiro.

**Critérios de aceite:**
- [ ] `crm_app` sem `DELETE`, `TRUNCATE`, `DROP` e `ALTER` em todas as tabelas novas, **exceto** `DELETE` em `budget_items` com orçamento `DRAFT` (a política exige o `DRAFT` do pai); sem `UPDATE` em `payments`.
- [ ] Nenhum `GRANT ALL`; `EXECUTE` só nas funções necessárias; funções novas com `search_path` fixo e `REVOKE EXECUTE ... FROM PUBLIC`.
- [ ] Estorno e cancelamento de parcela são a única forma de "desfazer" dinheiro; nenhuma tabela financeira aceita `DELETE` por `crm_app`.

### US-309: Anonimização do paciente com histórico comercial
**Descrição:** Como dentista, quero anonimizar um paciente sem perder o histórico contábil e sem deixar dado pessoal em texto livre.

**Critérios de aceite:**
- [ ] `core.anonymize_patient` passa a **zerar `budgets.notes`** do paciente e **manter** orçamentos, itens, parcelas e pagamentos (nada neles identifica a pessoa além do `patient_id`).
- [ ] Falha com **`CR006`** se o paciente tiver parcela `OPEN` (saldo em aberto), mensagem orientando quitar ou cancelar as parcelas antes; os códigos `CR001`–`CR005` e a retenção de 20 anos da Fase 1 não mudam; orçamentos e financeiro **não** entram no cálculo da retenção clínica.
- [ ] A função segue `SECURITY DEFINER`, `search_path` fixo, sem SQL dinâmico; a política do `crm_definer` em `budgets` é escopada ao tenant e limitada a `SELECT` e `UPDATE (notes, version)`; em `installments`, só `SELECT`.
- [ ] Marca `deleted_at` nos `budget_documents` do paciente (US-311), pois o PDF traz o nome; o backend apaga os objetos do Storage depois do sucesso e concilia as falhas.
- [ ] Teste: após anonimizar, nenhum `budgets.notes` do paciente mantém valor; `number`, `total_amount`, `status`, parcelas e pagamentos permanecem; cada orçamento limpo gera 1 linha `UPDATE` no `audit_log`, mais o `ANONYMIZE`.
- [ ] Anonimizar com orçamento `DRAFT` ou `SENT` sem parcelas passa (não é saldo em aberto); o backend avisa.

### US-311: PDF do orçamento no Storage
**Descrição:** Como dentista, quero guardar o PDF que enviei ao paciente, para provar depois exatamente o que foi oferecido.

**Critérios de aceite:**
- [ ] `core.budget_documents`: `budget_id` (FK composta `(budget_id, patient_id, clinic_id)` → `budgets`, `RESTRICT`), `patient_id`, `revision` (`smallint` ≥ 1; `UNIQUE (budget_id, revision)`), `storage_key` (`varchar(120)`, único, `CHECK` no formato `<clinic_id>/<patient_id>/budgets/<uuid>`), `content_type` (`'application/pdf'`), `size_bytes` (1 a 5242880), `sha256` (`varchar(64)`, hex minúsculo), `generated_at` (`timestamptz`), `deleted_at` (nulo), `created_by`, `created_at`.
- [ ] `INSERT` só com o orçamento em `SENT`, `APPROVED` ou `REJECTED` (nunca `DRAFT`); falha com erro mapeável.
- [ ] Imutáveis: todas as colunas, **exceto** `deleted_at`, que só passa de nulo para um instante (sem volta). Sem `DELETE`; `created_by = app.user_id` no `INSERT`.
- [ ] RLS `ENABLE` + `FORCE`: `SELECT` para os dois papéis; `INSERT` e `UPDATE` só `DENTIST_ADMIN`; política `SELECT TO crm_backup`.
- [ ] A listagem das revisões ativas de um orçamento usa índice `(budget_id, revision DESC) WHERE deleted_at IS NULL`, sem `Seq Scan`.
- [ ] Entra no `audit_log` (`table_name = 'budget_documents'`), sem valores.
- [ ] A anonimização do paciente marca `deleted_at` em todos os PDFs dele e devolve ao backend as `storage_key` para apagar os objetos (padrão da Fase 1, seção de `patient_files`); `crm_definer` com `SELECT` e `UPDATE (deleted_at, version)` escopado ao tenant.
- [ ] Teste: `storage_key` de outra clínica ou paciente falha; PDF para orçamento `DRAFT` falha; `UPDATE` de `sha256` ou `storage_key` falha (`23514`); nova revisão de orçamento já enviado passa.

### US-310: Backup, migrations e testes da fase
**Descrição:** Como responsável pelo banco, quero que as tabelas novas entrem no backup, na cadeia de rollback e nos testes existentes.

**Critérios de aceite:**
- [ ] Cada tabela nova tem política `SELECT TO crm_backup USING (true)`; `pg_dump` como `crm_backup` traz as 6 tabelas (os objetos do Storage **não** entram no dump); a restauração fecha as contagens (o `restore_check` conhece as tabelas novas e a ordem de carga das FKs).
- [ ] `database/tests/` ganha testes de isolamento, papel, constraints, invariantes de dinheiro, concorrência, auditoria, anonimização e desempenho; `run_all.sh` passa do zero e a cadeia de rollback reverte e reaplica.
- [ ] Migrations numeradas a partir de `V022`, cada uma com `-- Reverter:` apontando para `U0NN` em `database/rollback/`; `MIGRATIONS_EXPECTED` passa de 21 para o novo total; `catalog_rls_pattern.sql` e `schema_dictionary.sql` ampliados.
- [ ] Testes 59–85 da Fase 2 e os da Fase 1 continuam verdes; o teste 84 (`ddl-auto=validate`) roda contra o schema atual.

---

## 3. Requisitos funcionais

- **FR-1:** Criar em `core` as tabelas `procedure_types`, `budgets`, `budget_items`, `installments` e `payments`, e alterar `appointments` (FK de `procedure_type_id`), conforme o dicionário da spec.
- **FR-2:** Toda tabela com `clinic_id` tem FK, índice com `clinic_id` à frente, RLS `ENABLE` + `FORCE`, uma política por operação e política `SELECT` do `crm_backup`; sem `app.clinic_id`, `app.role` ou `app.user_id` válidos, as tabelas negam (falha fechada).
- **FR-3:** Valores monetários em `numeric(12,2)`, `CHECK` de faixa; datas civis em `date`, instantes em `timestamptz`.
- **FR-4:** `budgets.total_amount` é mantido pelo banco a partir dos itens (cada um com seu desconto) e do desconto do orçamento; `budget_items.line_total` é coluna gerada; itens só mudam com o orçamento em `DRAFT`.
- **FR-5:** Orçamento `APPROVED`/`REJECTED` é terminal; não volta a `DRAFT`; `sent_at`/`decided_at` coerentes com o status por `CHECK`.
- **FR-6:** `budgets.number` é sequencial por clínica, atribuído pelo banco, sem repetição sob concorrência.
- **FR-7:** Parcelas só nascem de orçamento `APPROVED`; a soma das parcelas não canceladas é igual ao `total_amount` ao fim da transação.
- **FR-8:** `payments` é append-only; estorno é linha `REVERSAL`; o líquido nunca excede o valor da parcela nem fica negativo; `installments.status` é sempre consistente com o líquido pago.
- **FR-9:** Pagamentos concorrentes na mesma parcela são serializados; não há pagamento a maior por corrida.
- **FR-10:** Papéis conforme C2: `RECEPTIONIST` sem acesso a `installments` e `payments`, só leitura em `procedure_types`, `budgets` e `budget_items`.
- **FR-11:** `crm_app` sem `DELETE` nas tabelas da fase (exceto itens de orçamento `DRAFT`) e sem `UPDATE` em `payments`; sem `GRANT ALL`; sem `BYPASSRLS`.
- **FR-12:** `appointments.procedure_type_id` ganha FK composta e índice parcial, sem reescrever linhas.
- **FR-13:** `audit_log` passa a aceitar `budgets`, `installments` e `payments`; os três geram auditoria sem valores.
- **FR-14:** `anonymize_patient` zera `budgets.notes`, mantém o financeiro e falha com `CR006` se houver parcela `OPEN` do paciente.
- **FR-15:** Paciente anonimizado (`CR003`) ou inativo (`CR005`) não recebe orçamento novo; parcelas e pagamentos de paciente inativado seguem permitidos.
- **FR-16:** Índices para as consultas críticas (vencidas, entradas do mês, faturamento, lista por paciente e por status) com as metas de desempenho da seção 1.
- **FR-16a:** `budget_documents` guarda os metadados do PDF no Storage (C5), append-only exceto `deleted_at`, só para orçamento fora de `DRAFT`; a anonimização marca os PDFs como removidos.
- **FR-17:** Publicar o contrato para o backend da Fase 3: nomes, tipos, erros (códigos `CR*` novos e `SQLSTATE` padrão), o que o banco impõe e o que fica no backend (ordem `SENT → APPROVED`, geração e arredondamento de parcelas, PDF, definição de "hoje").
- **FR-18:** Migrations aditivas, numeradas a partir de `V022`, com `-- Reverter:` apontando para `U0NN`; rollback de tabelas com dados é por restauração do `pg_dump`.

## 4. Non-goals (fora de escopo)

- **Geração** do PDF (é do backend). O banco guarda só os **metadados** do arquivo; o binário fica no Supabase Storage (C5), nunca em coluna `bytea`. Sem assinatura digital, sem envio por e-mail/WhatsApp e sem versionamento de layout.
- Máquina completa de status entre `DRAFT`, `SENT`, `APPROVED` e `REJECTED` (ex.: bloquear `DRAFT → APPROVED` direto): o banco só impõe terminalidade e congelamento (C1).
- Cancelamento, expiração automática (`valid_until` é só informativo), versionamento e duplicação de orçamento.
- Juros, multa, mora, correção, desconto por parcela, renegociação e **parcelamento no cartão com operadora** (taxas, antecipação); conciliação bancária e boleto/PIX com integração externa.
- Nota fiscal, recibo fiscal e qualquer numeração fiscal.
- Estorno parcial; pagamento maior que o saldo (crédito do paciente); troca de forma de pagamento sem estorno.
- Contas a pagar, despesas, estoque e **fluxo de caixa com saídas** (a fase só tem receitas, como no `arq_inicial.md`).
- Views, views materializadas, tabelas de resumo e `security_invoker` (Fase 4 avalia para o dashboard); os relatórios são consultas do backend sobre os índices.
- Odontograma, dente/face por item e plano de tratamento clínico.
- Preço por convênio, tabela de preços múltiplas e comissão.
- Multimoeda.
- Seed de procedimentos padrão.
- Auditoria de **leitura** e de valores antigo/novo (como na Fase 2).
- Particionamento e réplicas.

## 5. Requisitos de IA

Não se aplica.

## 6. Especificações técnicas e considerações de design

**Arquitetura (dados):**
```
React ─▶ API (JWT: clinic_id, role, user_id) ─▶ transação: set_config(app.clinic_id, app.role, app.user_id, local)
                                                    │
 core.procedure_types ◀─ FK composta ─ core.appointments.procedure_type_id (nulo)
        ▲ (RESTRICT)
 core.budget_items ─▶ core.budgets ──(APPROVED)──▶ core.installments ──▶ core.payments
        (só em DRAFT)   │  total = Σ itens − desconto      │  Σ ativas = total        │ append-only
                        │                                   │  status ⇔ líquido pago   │ estorno = REVERSAL
                        └──── trigger ─▶ core.audit_log (budgets, installments, payments; sem valores)
```

**Pontos de integração:**
- Backend (novo módulo, a planejar): entidades JPA com `ddl-auto=validate`; define as três variáveis de sessão em toda transação; gera as parcelas (divisão e arredondamento), conduz `DRAFT → SENT → APPROVED/REJECTED`, gera o PDF, calcula "hoje" no fuso da clínica e traduz os erros do banco em 4xx.
- Módulo `scheduling`: passa a mapear `procedure_type_id` (hoje comentado como "não mapeada" em `Appointment.java`).
- Módulo `patient`: `anonymize_patient` ganha `CR006`; o backend traduz para 409; antes de anonimizar lista as `storage_key` dos PDFs e, depois do sucesso, apaga os objetos.
- Backend do orçamento: ao enviar, gera o PDF, calcula o `sha256`, grava no Storage (`CRM_STORAGE_*`) e só então insere a linha em `budget_documents`; se o `INSERT` falhar, apaga o objeto. O inverso (linha sem objeto) é evitado pela ordem.
- Operação: `scripts/apply_migrations.sh`, `database/tests/run_all.sh`, backup e restauração já existentes (`restore_check` ampliado).

**UI/UX:** não se aplica (banco).

**Segurança e privacidade:**
- Dado financeiro de saúde é dado pessoal sensível por contexto: fica sob RLS por clínica **e por papel** (recepção sem parcela e pagamento), reforçado no banco (decisão C2, checklist 3).
- Sem texto livre em `payments`; `budgets.notes` e `appointments.notes` são o único texto livre da fase, limitados, com aviso no contrato e zerados na anonimização.
- Histórico imutável: pagamento sem `UPDATE`/`DELETE`, estorno por nova linha, auditoria de quem lançou; o `audit_log` não guarda valores.
- Menor privilégio: sem `GRANT ALL`, sem `BYPASSRLS`, `crm_app` não é dono de tabela; `crm_definer` só vê o tenant da sessão e só altera `budgets.notes`.
- Limite aceito (risco 1 da Fase 1): o banco confia no `app.role` que o backend informa; o papel errado no JWT abre o financeiro.
- Segredos e criptografia: nenhum campo da fase é cifrado (dinheiro precisa de soma e índice; identificação do paciente é só o `patient_id`).

## 7. Riscos e perguntas abertas

**Riscos técnicos**
1. **Complexidade dos triggers monetários.** Total do orçamento, soma das parcelas e status por pagamento são três mecanismos que se cruzam com RLS e `FORCE`. Mitigação: funções `SECURITY INVOKER` (o papel já tem o grant), testes de propriedade e de concorrência, uma migration por mecanismo.
2. **Constraint trigger adiável** (soma das parcelas) só falha no `COMMIT`: o erro chega tarde ao backend e o JPA pode mascará-lo. Documentar no contrato e testar o caminho de `flush` + `commit`.
3. **Serialização do `number` do orçamento** por contador por clínica cria um ponto de contenção entre `INSERT` simultâneos na mesma clínica; o volume (um dentista) torna isso irrelevante. Buracos na numeração são esperados.
4. **Trigger atualizando `installments.status` sob RLS:** o `UPDATE` automático roda com o papel do chamador (`DENTIST_ADMIN`); se a política de `UPDATE` mudar, o pagamento quebra. Teste dedicado e, se necessário, função `SECURITY DEFINER` mínima (decisão da spec).
5. **`payments` append-only e restauração:** o `restore_check` carrega dados com `--data-only` e hoje faz `TRUNCATE` de `holidays`; os triggers de proibição de `UPDATE`/`DELETE` não podem bloquear o `TRUNCATE` e a carga do `pg_restore`. Validar.
6. **Índice parcial com `status` e RLS:** a lição da Fase 2 (predicados com `IN` reinterpretados pelo `pg_dump`; `&&` de `tstzrange` sem índice sob RLS) vale: usar `OR`/`<>` nos predicados e conferir o plano com RLS ativa.
7. **Dinheiro sem homologação** (CI/CD adiado): migrations pequenas, aditivas e reversíveis; ensaio local e no Supabase real com o harness antes de aplicar.
8. **Pendências herdadas da Fase 2** (spec 4.6, teste 84, conflito `23P01` no real) não bloqueiam a Fase 3, mas precisam de dono (carregadas como critério da US-310).
9. **Storage fora do `pg_dump`:** perder o bucket deixa `budget_documents` órfãos (mesmo risco aceito da Fase 1); o `sha256` permite detectar objeto trocado ou corrompido, e o backend pode regenerar o PDF a partir do orçamento congelado (a nova revisão conserva a anterior). Backup do bucket segue fora do escopo.
10. **PDF com dado pessoal fora do banco:** o objeto não tem RLS, só o bucket privado e o acesso por URL assinada de curta duração pelo backend; a chave contém `clinic_id` e `patient_id`, mas é opaca. Risco de vazamento depende da configuração do bucket (pendência de segurança já registrada).
11. **Backend da Fase 3 não existe:** nomes, erros e a divisão "banco impõe × backend decide" podem mudar quando o backend planejar; conciliar antes de implementar.

**Perguntas abertas** — *respondidas em 2026-10-26: todos os padrões propostos foram aceitos (faturamento por competência e entradas por caixa; `number` com buracos; `valid_until` informativo; limites 50/60/999/R$ 999.999,99; `appointment_id` opcional; anonimizar com `SENT` sem parcelas não bloqueia; procedimento inativo é regra do backend; parcela `PAID` só cancela após estorno; mesmo bucket com prefixo `budgets/`; revisão ativa = a de maior número; PDFs apagados na anonimização), exceto o desconto, que vale nos dois níveis. Lista mantida abaixo como registro.*

(padrão proposto entre parênteses)
- *Faturamento do período* = orçamentos aprovados por `decided_at` (competência) e *entradas* = recebimentos por `paid_on` (caixa)? (Sim; se o produto quiser só caixa, o índice de `budgets` sai.)
- `number` do orçamento por clínica, com buracos permitidos? (Sim.)
- `valid_until` informativo, sem efeito no banco? (Sim.)
- PDF do orçamento no **mesmo bucket** dos exames (prefixo `budgets/`) ou em bucket próprio? (Mesmo bucket; bucket próprio só se quiser política de retenção diferente.)
- Reenvio gera sempre **nova revisão** (histórico preservado) e a revisão ativa é a de maior número? (Sim.)
- Apagar os PDFs na anonimização, mantendo o financeiro? (Sim; reter o PDF com o nome do paciente conflita com a LGPD.)
- ~~Desconto só no orçamento?~~ **Respondido: nos dois níveis** (item e orçamento), em valor.
- Limites de 50 itens, 60 parcelas, 999 unidades e R$ 999.999,99? (Sim; ajustáveis.)
- Orçamento sem `appointment_id` pode existir (paciente sem consulta marcada)? (Sim, o vínculo é opcional.)
- Anonimizar com orçamento `SENT` sem parcelas (aguardando resposta) deve bloquear? (Não bloqueia; o backend avisa.)
- Inativar procedimento com orçamentos `DRAFT` abertos: o banco ou o backend impede itens novos de procedimento inativo? (Backend; o banco só garante a FK.)
- Parcela `PAID` pode ser cancelada se for estornada primeiro? (Sim: estorna, volta a `OPEN`, cancela.)

**Lançamento em etapas**
- **MVP (Fase 3):** US-301 a US-311.
- **v1.1:** views de relatório com `security_invoker = true` e dashboard (Fase 4); `started_at`/`finished_at` do atendimento se a S8 for revista.
- **v2.0:** estorno parcial, crédito do paciente, contas a pagar e fluxo de caixa com saídas, múltiplos profissionais (muda o `EXCLUDE`), trigger de transição de status da agenda.
