# PRD — F01 Plataforma da Fase 3 (comercial e financeiro)

**Derivação:** PRD global da Fase 3, seções "Decisões fechadas" (H1, H2), "Padrões adotados" (dinheiro em texto decimal, chave `budgets/`, códigos do banco → HTTP), FR-11, FR-13, FR-14 e FR-17, US-P3-15 (parte de plataforma). Spec global, seções 1.1, 2.2, 3.1, 4.1, 4.2, 4.13, 5.1, 6.1 e 7.
**Dependências externas:** Fases 0 a 2 (JWT, `TenantContext`, `GlobalExceptionHandler`, `DatabaseErrors`, `FileStorage`, `StorageKeys`, `ClinicalCacheHeaders`, `SecurityConfig`, `PatientAnonymizationService`, `Phase2ItSupport`); migrations V022–V029, já aplicadas no Supabase real. É a **base** das F02 a F08: nenhuma delas começa antes dela.

## 1. Introdução

- **Problema:** as features da fase compartilham peças que não pertencem a nenhuma delas: o tipo de dinheiro, a chave de Storage dos PDFs, a tradução dos erros do banco, as regras de rota e o ambiente de teste com V001–V029. Sem uma base única, cada feature inventaria a sua e o `CR011` do commit viraria 500.
- **Solução:** entregar a plataforma antes das features: dependência OpenPDF, `Money`, `StorageKeys` com `budgets/`, bean `documentStorage` e propriedades `crm.documents.*`, `ClinicIdentity`, a SPI `AnonymizationContributor`, a tradução de `CR006`–`CR011`, das novas `23505` e de `TransactionSystemException`, as regras de rota e `no-store`, e a infraestrutura de testes da fase.
- **Metas:**
  1. Nenhuma feature da fase precisa alterar `shared`, `SecurityConfig` ou o handler de erros (tudo já está pronto aqui).
  2. `Money` rejeita 100% dos formatos inválidos da tabela de casos (`1e3`, `-1`, `1,5`, 3 casas, acima do teto) e a divisão em centavos soma sempre o total, em teste de propriedade.
  3. Uma `TransactionSystemException` com `SQLState` `CR011` no commit vira 409, nunca 500.
  4. `Phase3ArchitectureTest` e `Phase3ItSupport` (V001–V029) verdes; a suíte das Fases 1 e 2 segue verde.

## 2. User stories

### US-001: Dinheiro com tipo único
**Descrição:** Como desenvolvedor do backend, quero um único tipo para ler e escrever valores monetários, para que nenhum ponto da API use ponto flutuante.

**Critérios de aceite:**
- [ ] `Money.parse(String)` aceita só `^\d{1,6}(\.\d{1,2})?$` e devolve `BigDecimal` com escala 2, de 0 a 999999.99; `parsePositive` exige ≥ 0,01.
- [ ] `Money.format(BigDecimal)` devolve `toPlainString` com escala 2.
- [ ] `Money.splitDown(total, n)` divide em centavos com `RoundingMode.DOWN` e a diferença na última parcela; a soma é sempre igual ao total.
- [ ] Um campo `String` que receba número JSON (`1234.5`) falha com 400 (ver ponto 6 da seção 9 da spec global).

### US-002: Chave de Storage dos PDFs e Storage de documentos
**Descrição:** Como desenvolvedor, quero gravar PDFs no mesmo bucket dos exames com uma chave própria e um timeout mais curto.

**Critérios de aceite:**
- [ ] `StorageKeys.ofBudget(clinicId, patientId, objectId)` produz `<clinic>/<patient>/budgets/<uuid>`; `require` aceita as duas formas (3 UUIDs e `…/budgets/<uuid>`) e recusa qualquer outra; `belongsTo` segue por prefixo.
- [ ] A chave de PDF tem 118 caracteres e cabe em `varchar(120)`.
- [ ] Existe um segundo bean `FileStorage` (`documentStorage`) com o mesmo bucket e chave de serviço e `READ_TIMEOUT` de `crm.documents.upload-timeout` (10 s); o bean dos exames (30 s) não muda. Em dev e teste ambos usam o `LocalFileStorage`.
- [ ] O Javadoc do `FileStorage` documenta a exceção H1: só o envio e a nova revisão de PDF chamam o Storage dentro de transação.

### US-003: Tradução de erros do banco e de regras
**Descrição:** Como cliente da API, quero receber 409/422 claros em vez de 500 quando o banco recusar uma regra de dinheiro.

**Critérios de aceite:**
- [ ] `CR003` e `CR005` (já existentes) e **`CR006`–`CR011`** viram 409 com as mensagens da tabela 5.1 da spec global.
- [ ] `23505` com `ux_ptype_clinic_name`, `ux_pay_reverses` e `ux_inst_budget_number` viram 409; `ux_budgets_clinic_number`, `ux_items_budget_position` e `ux_bdoc_*` seguem 500 com log só da constraint.
- [ ] `23514` com `ck_items_line` ou `ck_budgets_total` vira 422 `business-rule` com `reason: AMOUNT_OUT_OF_RANGE`; as demais `23514`/`23503` seguem 500.
- [ ] **Novo handler de `TransactionSystemException`** que extrai o `SQLState` da cadeia de causas e usa o mesmo `switch`; sem `SQLState` conhecido, 500 `internal`.
- [ ] Nenhuma mensagem nem log contém o `DETAIL` do driver, valor monetário ou nome.

### US-004: Rotas, papéis e cache
**Descrição:** Como responsável pela segurança, quero as regras de rota da fase em um só lugar, antes de qualquer feature.

**Critérios de aceite:**
- [ ] `SecurityConfig` aplica a matriz da seção 3 do PRD global: escrita de procedimento, orçamento, plano, pagamento, relatório e ficha só `DENTIST_ADMIN`; as leituras de `procedure-types`, `budgets`, `budgets/*/documents` e `patients/*/budgets` ficam em `authenticated()`.
- [ ] `ClinicalCacheHeaders` passa a incluir `/api/v1/procedure-types`, `/budgets`, `/installments`, `/payments` e `/finance` (`/patients` já está coberto).
- [ ] `Phase3Routes` lista as rotas novas para a matriz papel × endpoint (401/403/2xx) reutilizada por F02 a F08.

### US-005: Pontos de extensão entre módulos
**Descrição:** Como desenvolvedor, quero as interfaces públicas que evitam ciclo entre módulos.

**Critérios de aceite:**
- [ ] `ClinicIdentity.current()` devolve nome, endereço e telefone da clínica do token (lê `clinics`, sem expor a entidade).
- [ ] `AnonymizationContributor` (em `patient.service`) com `objectKeys(patientId)` e `openItems(patientId)` (padrão `0`); o `PatientAnonymizationService` ainda não os usa (F07), mas a SPI existe.
- [ ] `CrmProperties` ganha `Documents(uploadTimeout 10 s, maxConcurrent 3, sendsPerMinute 6, transactionTimeout 20 s)` com padrões.

### US-006: Base de testes da fase
**Descrição:** Como desenvolvedor, quero um ambiente de teste que já aplique V001–V029 e vigie a arquitetura.

**Critérios de aceite:**
- [ ] `Phase3ItSupport` aponta para `database/bootstrap` e `database/migrations` **V001–V029** e reaproveita o contêiner e o contexto Spring.
- [ ] `Phase3ArchitectureTest` impõe: sem ciclo entre módulos, `finance → commercial` só por `BudgetLookup`, `scheduling → catalog` só por `ProcedureTypeLookup`, `patient` sem importar `commercial`/`finance`, e as classes puras (`Money`) sem Spring; começa verde com os módulos ainda vazios.
- [ ] `Phase3SchemaValidateIT` roda `ddl-auto=validate` e ganha os casos das entidades a cada feature.

## 3. Requisitos funcionais

- **FR-1:** Adicionar `com.github.librepdf:openpdf` (série 2.x) ao `pom.xml`; versão exata fixada no primeiro build.
- **FR-2:** `Money` em `shared.money`, sem dependência de Spring.
- **FR-3:** `StorageKeys.ofBudget` e `require` com as duas formas; `SupabaseFileStorage.deleteAll` e `LocalFileStorage` aceitam ambas por `require`.
- **FR-4:** Bean `documentStorage` em `StorageConfig` e propriedades `crm.documents.*`; sem variável de ambiente obrigatória.
- **FR-5:** `GlobalExceptionHandler` e `ConflictMessages` conforme US-003, mais `TransactionSystemException`.
- **FR-6:** `SecurityConfig` e `ClinicalCacheHeaders` conforme US-004.
- **FR-7:** `ClinicIdentity` (interface em `clinic.service` e implementação) e `AnonymizationContributor`.
- **FR-8:** `Phase3ItSupport`, `Phase3ArchitectureTest`, `Phase3SchemaValidateIT` e `Phase3Routes` como esqueletos.

## 4. Fora do escopo

- Qualquer endpoint, entidade ou service de negócio (F02 a F07).
- Geração de PDF (F04) e uso efetivo da SPI na anonimização (F07).
- Mudanças no `FileStorage` dos exames além da documentação do contrato.
- Migration ou alteração de banco.

## 5. Considerações técnicas e riscos

- O handler atual não trata `TransactionSystemException`: sem esta feature o `CR011` (que só aparece no commit) viraria 500 (ponto 4 da seção 9 da spec).
- `StorageKeys.require` é a trava de segurança do prefixo da clínica; relaxá-la para a forma `budgets/` não pode abrir o caminho para `..` ou segmentos livres: as duas formas são **regex fechadas** de UUIDs canônicos.
- A ordem das regras do `SecurityConfig` importa (mais específico antes do mais geral); validar o casamento no primeiro build.
- A versão do OpenPDF e o pacote (`com.lowagie.text`) precisam ser conferidos no primeiro build.
