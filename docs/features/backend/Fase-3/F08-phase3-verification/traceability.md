# Rastreabilidade — Fase 3 (Comercial e Financeiro)

Cada história do PRD global (`docs/global/backend/Fase-3/prd.md`) ligada à feature que a implementa e aos testes que a provam. Caminhos relativos a `back-java/src/test/java/crm_data/`. `IT` = integração contra PostgreSQL real (Testcontainers, V001–V029). Execução de referência (2026-10-26): `mvn verify` padrão, sem nenhuma exclusão, com a suíte das Fases 0–3: **1318 testes unitários/web e 438 ITs, 0 falhas** (BUILD SUCCESS). O `LoginPerformanceIT` passou nessa rodada (rajadas de 292 a 302 ms, mediana 297 ms contra o limite de 300 ms).

## Histórias

| História | Feature | Testes que provam |
|---|---|---|
| **US-P3-01** Tipos de procedimento | F02 | `phase3/ProcedureTypesIT` (criar/ler, campos do servidor ignorados, nome duplicado por caixa e por clínica, versão, filtro `active`, validação com número JSON recusado, outra clínica, RLS), `catalog/service/ProcedureTypeServiceTest`, `ProcedureTypeLookupServiceTest`, `catalog/web/ProcedureTypeWebTest` |
| **US-P3-02** Procedimento no agendamento | F02 | `ProcedureTypesIT` (resumo em criar/ler/listar/histórico, ausente = nulo, inexistente 404, inativo 422, `PUT` substitui — D1, FK composta, uma consulta para muitos agendamentos), `scheduling/service/AppointmentServiceTest`, `scheduling/web/AppointmentWebTest` |
| **US-P3-03** Criar e editar o rascunho | F03 | `phase3/BudgetsIT` (número, preços e total do banco, snapshot do preço, `PUT` substitui itens, desconto grande, versão sempre avança, `PUT` concorrente, enviado congelado), `commercial/service/BudgetServiceTest`, `BudgetDraftValidatorTest`, `commercial/web/BudgetWebTest`, `phase3/MoneyInvariantsIT` (a) |
| **US-P3-04** Consultar orçamentos | F03 | `BudgetsIT` (filtros por status, paciente e número, histórico, outra clínica, recepção só lê), `BudgetWebTest` |
| **US-P3-05** Enviar o orçamento (PDF) | F04 | `phase3/BudgetSendIT` (envio, bytes/hash/texto do PDF, versão e segundo envio 409, total zero, **falha do Storage / do `INSERT` / do commit / da compensação**, 429 e 503 com `Retry-After`), `commercial/service/BudgetDocumentServiceTest`, `BudgetPdfRendererTest`, `DocumentSlotsTest`, `DocumentRateLimiterTest`, `commercial/web/BudgetDocumentWebTest`, `DocumentRateLimitInterceptorTest`, `DocumentStorageTimeoutTest` |
| **US-P3-06** Baixar e reenviar o PDF | F04 | `BudgetSendIT` (listagem, URL assinada, removido/alheio 404, reemissão em qualquer status final, revisões simultâneas), `BudgetDocumentWebTest` |
| **US-P3-07** Aprovar e recusar | F05 | `phase3/InstallmentsIT` (só de `SENT`, rascunho 409 com mensagem própria, terminais, versão, recepção 403, aprovação × recusa simultâneas), `commercial/service/BudgetDecisionServiceTest`, `BudgetWebTest` |
| **US-P3-08** Gerar as parcelas | F05 | `InstallmentsIT` (AUTO com entrada, centavos na última, MANUAL ordenado, 400 com a diferença, plano duplicado 409, orçamento não aprovado 409, **`CR011` forçado no commit → 409 sem persistir**, dois planos simultâneos), `finance/service/InstallmentPlannerTest` (propriedade Σ == total em ≥ 200 planos), `InstallmentPlanServiceTest`, `finance/web/InstallmentWebTest`, `MoneyInvariantsIT` (b) |
| **US-P3-09** Refazer o plano e ajustar vencimento | F05 | `InstallmentsIT` (refazer cancela e reaproveita números, com líquido pago 409, `CR011` ao refazer mantém o plano antigo, vencimento só em `OPEN`, versão, inativo passa e anonimizado 409), `InstallmentServiceTest` |
| **US-P3-10** Registrar pagamento | F06 | `phase3/PaymentsIT` (parcial e quitação por trigger, acima do saldo/cancelada 409, 400 e 422 `FUTURE_DATE`, data na clínica, campos do servidor ignorados, inativo passa e anonimizado 409, **20 rodadas no último saldo**, parcelas distintas), `finance/service/PaymentServiceTest`, `finance/web/PaymentWebTest`, `Phase3PerformanceIT` (p95) |
| **US-P3-11** Estornar pagamento | F06 | `PaymentsIT` (estorno reabre a parcela e mantém o histórico, estorno de estorno e repetido 409, outra clínica 404, dois estornos simultâneos), `PaymentServiceTest` (repositório sem delete/update, entidade `@Immutable` sem setters), `PaymentWebTest` (sem `PUT`/`PATCH`/`DELETE`) |
| **US-P3-12** Consultas do financeiro | F06 | `PaymentsIT` (caixa líquido de estornos, janela e página validadas, a receber, atraso derivado de "hoje" na clínica, faturamento na virada do mês no fuso, ficha, carga de 5 mil parcelas e **100 mil linhas no banco**: p95 e `EXPLAIN` sem `Seq Scan`), `FinanceReportServiceTest`, `PatientFinancialServiceTest`, `PaymentWebTest` |
| **US-P3-13** Anonimização com histórico comercial | F07 | `phase3/CommercialAnonymizationIT` (`CR006` bloqueia sem alterar nada, `PAID`/`CANCELED`/`SENT` sem parcela passam, notes nulo e financeiro intacto, PDFs removidos e objetos apagados, falha do Storage → `PENDING`, novo orçamento/pagamento 409, **envio × anonimização em 10 rodadas**), `patient/service/PatientAnonymizationServiceTest`, `commercial/service/BudgetAnonymizationContributorTest`, `patient/service/PatientLookupServiceTest` (`lockWritable`) |
| **US-P3-14** Auditoria comercial e financeira | F07 | `CommercialAnonymizationIT.theAuditLogShowsTheFourNewTables…` (INSERT/UPDATE/ANONYMIZE sem valores, 1 UPDATE a mais por orçamento limpo, estorno e status da parcela visíveis), `unauditedTablesAreRejected…` (`procedure_types`/`budget_items` → 400, recepção 403) |
| **US-P3-15** Isolamento, papel e plataforma provados | F01, F08 | `phase3/Phase3SecurityRulesTest` (29 rotas, `SecurityConfig` real), `Phase3RoleMatrixIT` (29 rotas × {sem token, R, D}, nenhum 5xx, `no-store`), `Phase3TenantIsolationIT` (seis tabelas, filtro desligado, JPQL e SQL nativo, escrita cruzada, rotas por id 404, relatórios), `Phase3ForgedRoleIT` (papel forjado/ausente/inválido, escrita sem usuário), `Phase3SchemaValidateIT`, `Phase3ArchitectureTest`, `JsonStrictnessConfig` + `StorageConfigTest` |

## Requisitos funcionais

| FR | Onde |
|---|---|
| FR-1 Catálogo | `ProcedureTypesIT`, `ProcedureTypeServiceTest` |
| FR-2 Rascunho (1–50 itens, descontos, preços do banco) | `BudgetsIT`, `BudgetDraftValidatorTest`, `MoneyInvariantsIT` |
| FR-3 Máquina de estados (`SENT` → decisão) | `BudgetTransitionsTest`, `BudgetDecisionServiceTest`, `InstallmentsIT` |
| FR-4 PDF e envio atômico | `BudgetSendIT`, `BudgetPdfRendererTest`, `BudgetDocumentServiceTest` |
| FR-5 Revisões, listagem e URL assinada | `BudgetSendIT` |
| FR-6 Parcelas AUTO/MANUAL | `InstallmentPlannerTest`, `InstallmentsIT`, `MoneyInvariantsIT` |
| FR-7 Refazer o plano | `InstallmentsIT`, `InstallmentPlanServiceTest`, `MoneyInvariantsIT` |
| FR-8 Pagamentos e estornos append-only | `PaymentsIT`, `PaymentServiceTest`, `Phase3ForgedRoleIT.nobodyUpdatesOrDeletesPayments…` |
| FR-9 Consultas do financeiro | `PaymentsIT`, `FinanceReportServiceTest`, `PatientFinancialServiceTest` |
| FR-10 `procedureTypeId` no agendamento | `ProcedureTypesIT`, `AppointmentServiceTest` |
| FR-11 Tradução de `CR003`, `CR005`–`CR011`, `23505`/`23514` | `shared/error/GlobalExceptionHandlerTest`, ITs de F03–F07 |
| FR-12 Controle otimista | `BudgetsIT`, `ProcedureTypesIT`, `InstallmentsIT`, `PaymentsIT` |
| FR-13 Papel no servidor | `Phase3SecurityRulesTest`, `Phase3RoleMatrixIT`, `@PreAuthorize` testado nos web tests |
| FR-14 Sessão `app.*` em toda transação | `Phase3ForgedRoleIT`, `Phase3TenantIsolationIT` |
| FR-15 Anonimização com `CR006`, PDFs e quatro tabelas na auditoria | `CommercialAnonymizationIT`, `PatientAnonymizationServiceTest` |
| FR-16 Nenhum valor/nome/observação/chave/URL em log | `Phase3LogHygieneIT` (+ mutação), eventos com só ids nos testes de serviço |
| FR-17 `ddl-auto=validate` V001–V029 | `Phase3SchemaValidateIT` e todo IT (o contexto sobe validando) |

## Metas do PRD global (seção 1)

| Meta | Resultado | Onde |
|---|---|---|
| 1. Matriz papel × endpoint 100% | 29 rotas × {sem token, R, D}; nenhum 5xx; `no-store` em todas | `Phase3SecurityRulesTest`, `Phase3RoleMatrixIT` |
| 2. Invariantes de dinheiro (a)–(d) | **200 sequências**, semente `20261026`: 184 com plano, 212 pagamentos, 47 refazeres; operações inválidas respondem 4xx sem alterar nada | `MoneyInvariantsIT` |
| 3. Concorrência, 20 rodadas, sem 500 | Verde, com as rodadas reais por corrida: **20** pagamento no último saldo (`PaymentsIT`), numeração e `PUT` com a mesma versão (`BudgetsIT`), numeração, plano e edição × envio (`Phase3ConcurrencyIT`); 10 dois envios da mesma versão (`BudgetSendIT`) e envio × anonimização (`CommercialAnonymizationIT`); 5 planos simultâneos (`InstallmentsIT`); 2 revisões simultâneas (`BudgetSendIT`); 1 aprovação × recusa (`InstallmentsIT`) e 1 estorno repetido (`PaymentsIT`). Nenhum 500 | `PaymentsIT`, `Phase3ConcurrencyIT`, `BudgetsIT`, `BudgetSendIT`, `InstallmentsIT`, `CommercialAnonymizationIT` |
| 4. Envio atômico | Verde em falha de `save`, `INSERT`, commit e compensação; sem linha e sem objeto órfão | `BudgetSendIT` |
| 5. Filtro desligado + papel forjado | Verde: a RLS sozinha segura | `Phase3TenantIsolationIT`, `Phase3ForgedRoleIT` |
| 6. Desempenho (5 mil parcelas/pagamentos na clínica, 100 mil no banco) | p95 medidos em 2026-10-26 (limites entre parênteses): a receber **69 ms**, inadimplência **69 ms**, fluxo de caixa **47 ms**, faturamento **57 ms**, ficha **47 ms** (100 ms); pagamento **24 ms** (100 ms); leitura de orçamento **13 ms** (100 ms); orçamento de 20 itens: criar **112 ms**, editar **198 ms** (250 ms, a edição usa 79% do limite); envio com PDF **91 ms** (1,5 s); leitura com 3 envios lentos **20 ms** (200 ms); planos sem `Seq Scan` | `PaymentsIT`, `Phase3PerformanceIT`, `Phase3PoolIT` |
| 7. `ddl-auto=validate` e suíte verde | Verde | `Phase3SchemaValidateIT`, suíte completa |
| 8. Nenhum dado sensível em log/erro | Verde; o detector é validado por mutação (abaixo) | `Phase3LogHygieneIT` |
| 9. Cobertura ≥ 80% nos serviços | **catalog.service 100%**, **commercial.service 98,0%**, **finance.service 98,6%**, **scheduling.service 99,7%**, **patient.service 98,9%**, **record.service 97,8%** (linhas, JaCoCo 0.8.13 por linha de comando); `finance.web` 100%, `commercial.web` 100%, `catalog.web` 100% | `./mvnw org.jacoco:jacoco-maven-plugin:0.8.13:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.13:report -Dit.test='!LoginPerformanceIT'` |

## Os 12 critérios de saída da F08

| # | Garantia | Prova | Observação |
|---|---|---|---|
| 1 | Matriz papel × endpoint | `Phase3SecurityRulesTest`, `Phase3RoleMatrixIT` | A versão sem banco é o teste de regras da `SecurityConfig`; os web tests de cada feature cobrem o corpo e os campos proibidos |
| 2 | Isolamento com filtro desligado | `Phase3TenantIsolationIT` | |
| 3 | Papel forjado/ausente/inválido | `Phase3ForgedRoleIT` | |
| 4 | Invariantes em ≥ 200 sequências | `MoneyInvariantsIT` | |
| 5 | Concorrência | `Phase3ConcurrencyIT` + ITs de feature | Rodadas reais por corrida na Meta 3 (20 nos casos exigidos pelo contrato: último saldo, numeração, plano) |
| 6 | Envio atômico | `BudgetSendIT` | Cobre `save`, `INSERT`, commit e compensação; nome da spec: `BudgetSendAtomicityIT` |
| 7 | `CR011` no commit | `InstallmentsIT` | Nome da spec: `InstallmentCommitIT` |
| 8 | Anonimização e auditoria | `CommercialAnonymizationIT` | Nome da spec: `AnonymizationCommercialIT` + `AuditCommercialIT` |
| 9 | Desempenho e pool | `PaymentsIT`, `Phase3PerformanceIT`, `Phase3PoolIT` | |
| 10 | Higiene de log por mutação | `Phase3LogHygieneIT` | Ver a mutação abaixo |
| 11 | Arquitetura | `Phase3ArchitectureTest` | |
| 12 | `ddl-auto=validate` | `Phase3SchemaValidateIT` | |

## Validação da higiene de log por mutação (2026-10-26)

1. **Detector:** `Phase3LogHygieneIT.theDetectorSeesALeakInjectedIntoALog` injeta valor, nome e um `throwable` em logs e exige que o detector os acuse (3 achados).
2. **Mutação do código de produção:** foi injetado em `PaymentService.register` um `log.info("… valor={}", amount)`. O teste `noSecretReachesTheLogsOrTheErrorResponsesInAnyPhase3Flow` **falhou** acusando `321.09` no logger `crm_data.finance.audit`. A mutação foi revertida e o teste voltou a passar (2/2).
3. **Sentinelas:** nome do paciente, observação do orçamento, preço, total de linha, desconto, valor de pagamento, chaves de objeto, URL assinada.

## Pontos 1 a 14 da seção 9 da spec global

| # | Ponto | Situação |
|---|---|---|
| 1 | Colunas omitidas do `INSERT` | Resolvido: `BudgetsIT` (número, preços, total e carimbos vêm do banco) |
| 2 | `flush` + releitura após trigger | Resolvido: padrão `flush` + `clear` + `findById`; `BudgetsIT` |
| 3 | Ordem do `flush` | Resolvido: `BudgetsIT.aBigPreviousDiscount…`, `InstallmentsIT` (refazer) |
| 4 | Exceção do `CR011` no commit | Resolvido: `TransactionSystemException` tratada no `GlobalExceptionHandler`; `InstallmentsIT` |
| 5 | Pooler 6543 do Supabase | **Resolvido pelo smoke de 2026-10-26** (ver a seção abaixo): 24/24 verificações no Supabase real |
| 6 | Jackson 3 e dinheiro | Resolvido: `JsonStrictnessConfig`; `ProcedureTypesIT`, `PaymentWebTest`, `InstallmentWebTest` |
| 7 | OpenPDF | Resolvido: `BudgetPdfRendererTest`, `BudgetSendIT` (texto e hash do PDF) |
| 8 | `PESSIMISTIC_WRITE` sob RLS e filtro | Resolvido: `BudgetsIT` (`PUT` concorrente), `InstallmentsIT`; `Phase3SqlShapeIT.theBudgetLockShowsInTheSql…` (a trava aparece no SQL e uma operação recusada não deixa linha de auditoria) |
| 9 | Pool durante o envio | Resolvido: `Phase3PoolIT` (3 envios lentos, leitura p95 ≤ 200 ms) |
| 10 | `StorageKeys` e o Supabase | Resolvido: `StorageKeysTest`, `CommercialAnonymizationIT` e o smoke real (a chave `<clínica>/<paciente>/budgets/<uuid>` foi aceita pelo Storage, a URL assinada baixou um PDF e `deleteAll` apagou os objetos na anonimização) |
| 11 | Matchers do Spring Security 7 | Resolvido: `Phase3SecurityRulesTest`, `Phase3RoleMatrixIT` |
| 12 | `Persistable` e `@Immutable` | Resolvido: `Phase3SqlShapeIT.immutableEntitiesArePersisted…` (sem `SELECT` por chave antes do `INSERT` de `BudgetItem`, `BudgetDocument` e `Payment`; nenhum `UPDATE`/`DELETE` de pagamento; `Installment` não é carregada no pagamento), `PaymentServiceTest` |
| 13 | Timeout e `release()` do `DocumentSlots` | Resolvido: `BudgetDocumentServiceTest` (timeout 20 s), `BudgetSendIT` (vagas voltam a 3 após falha de commit) |
| 14 | Bucket real (10 MB, MIME, prefixo `budgets/`) | **Encaminhado**: pendência de segurança do Supabase, sem dono nesta fase |

## Achados da fase que viram trabalho para outras funções

- **Banco:** `core.budget_documents_before_insert` (V025) não checa paciente anonimizado; a corrida de envio × anonimização deixava um PDF vivo. Mitigado no backend (`PatientLookup.lockWritable`, F07). Recomendada uma V030 com a checagem no banco.
- **Testes instáveis pré-existentes (Fases 0 e 1):** `ClinicUsersIT` (~1 em 3). `LoginPerformanceIT` passou a medir 5 rajadas de 4 logins e comparar a mediana dos p95 por rajada com os mesmos 300 ms do contrato da Fase 0 (decisão do usuário em 2026-10-26). Com a CPU da máquina a ~57% por outros programas (um jogo aberto), as medianas ficaram em 303–319 ms e o teste reprovou 3 de 3: o login com BCrypt custo 12 está no limite do contrato neste computador. Na rodada completa e limpa de `mvn verify` ele passou (mediana 297 ms), mas com folga de só 3 ms: se a falha persistir ociosa, a meta de 300 ms (ou o custo do BCrypt) é decisão do arquiteto, não do teste.

## Smoke no Supabase real (2026-10-26)

Autorizado pelo usuário. O backend (`prod`, `crm_app` pelo pooler transaction 6543) subiu com `ddl-auto=validate` contra as V001–V029 reais e o roteiro `smoke_phase3.py` (só pela API, sem credenciais do banco) rodou na clínica fictícia `teste-smoke-f3-1793054089`. **24/24 verificações passaram:**

| Verificação | Resultado |
|---|---|
| Numeração de orçamento com 6 criações simultâneas (lock consultivo atrás do pooler) | 6 × 201, números 1–6 distintos e sequenciais |
| Total calculado pelo banco (2 × 250 + 900 − 600) | `800.00` |
| Envio com upload no Storage real dentro da transação; URL assinada | `SENT` com PDF; a URL baixou um arquivo que começa com `%PDF-`; reemissão gerou a revisão 2 |
| Plano AUTO com entrada (constraint trigger adiado `CR011` conferido no commit, atrás do pooler) | 201, soma `800.00`; segundo plano 409; plano manual com soma errada 400 |
| Dois pagamentos simultâneos no último saldo (trava da parcela + trigger de status) | um 201 e um 409; parcela `PAID`; estorno reabre (`OPEN`); segundo estorno 409 |
| Relatórios, fluxo de caixa, faturamento e ficha | todos 200 |
| Anonimização | com parcela em aberto 409 (`CR006`); depois de quitar, 200 com `storageCleanup = COMPLETED` (PDFs apagados do Storage real) |
| Auditoria de pagamentos | 200 |

**Limpeza do banco: pendente, é sua.** O `.env` não tem a senha do dono e `payments` nega `DELETE` até ao dono; rode `cleanup_smoke_phase3.sql` (nesta pasta) no SQL Editor do Supabase como `postgres`. Ele só toca clínicas `teste-smoke-f3-%` (aborta se o escopo tiver 0 ou mais de 10). O Storage não precisa de limpeza (a anonimização apagou os PDFs). Para repetir o smoke: subir o backend com o `.env` e rodar `python -I smoke_phase3.py`.

**Credencial do smoke:** a conta criada em 2026-10-26 (`smoke-f3-…@teste.local`) usou uma senha fixa que estava no script; ela só deixa de existir quando a limpeza acima for executada. A partir de agora o `smoke_phase3.py` gera uma senha nova a cada execução (`secrets.token_urlsafe`), que nunca é impressa nem versionada.
