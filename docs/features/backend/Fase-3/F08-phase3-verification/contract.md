# Contract — F08 Verificação da Fase 3

## 1. Resumo
- **Feature:** F08-phase3-verification
- **Versão:** 1.0.0
- **Consumidores:** o responsável pelo projeto e o gate de qualidade da fase (`qa-feature-legacy`). Não expõe endpoint; o "contrato" é o conjunto de garantias que a suíte comprova.

## 2. Entradas

- **Código sob teste:** as features F01 a F07 implementadas.
- **Ambiente:** Docker ligado (Testcontainers); PostgreSQL com `database/bootstrap` e `database/migrations` **V001–V029**; perfil `test`; `LocalFileStorage` (com `FaultyFileStorage` onde indicado).
- **Parâmetros:** `PerfLimits` (limite dobrado com `CI`, sobrescrevível por `-Dperf.limit-ms`); semente do teste de propriedade registrada no relatório.

## 3. Saídas

- **Resultado:** `mvn verify` verde em máquina limpa, com a suíte das Fases 1 e 2.
- **Evidências:** relatório de cobertura (≥ 80% nas classes de serviço de `catalog`, `commercial` e `finance` e nas alteradas de `scheduling`, `patient` e `record`); matriz de papéis; semente e resultado das propriedades; p95 e `EXPLAIN` sem `Seq Scan`; resultado da mutação de log.
- **Documentos:** `traceability.md` (ao fim da implementação) e o registro do smoke manual no Supabase real.
- **Falha:** qualquer vermelho reprova a fase; defeito volta para a feature dona.

## 4. Regras e limites (critérios de saída da fase)

| # | Garantia | Evidência |
|---|---|---|
| 1 | Matriz papel × endpoint 100% (401/403/2xx), rotas novas e alteradas | `Phase3RoleMatrixWebTest`, `Phase3RoleMatrixIT` |
| 2 | Isolamento com filtro Hibernate desligado: 0 linhas de outra clínica nas 6 tabelas e nos relatórios | `Phase3TenantIsolationIT` |
| 3 | Papel forjado: recepção sem acesso a parcela/pagamento e sem escrita comercial; papel ausente/inválido nega tudo | `Phase3ForgedRoleIT` |
| 4 | Invariantes (a)–(d) em ≥ 200 sequências; operação inválida não altera dados | `MoneyInvariantsIT` |
| 5 | 2 pagamentos no último saldo: 1×201 e 1×409 (20×); numeração distinta (20×); planos e revisões simultâneos sem 500; item × envio sem estado misto | `Phase3ConcurrencyIT` |
| 6 | Envio atômico: `DRAFT`, sem linha, sem objeto órfão, em falha de `save`, `delete`, `INSERT` e commit | `BudgetSendAtomicityIT` |
| 7 | `CR011` no commit → 409, nada persistido | `InstallmentCommitIT` |
| 8 | Anonimização e auditoria ponta a ponta (F07) | `AnonymizationCommercialIT`, `AuditCommercialIT` |
| 9 | p95 ≤ 100 ms nos relatórios e na lista do paciente; pagamento ≤ 100 ms; orçamento de 20 itens ≤ 250 ms; envio ≤ 1,5 s; leitura com 3 envios lentos ≤ 200 ms; sem `Seq Scan` | `Phase3PerformanceIT`, `Phase3PoolIT` |
| 10 | Nenhum valor, nome, observação, chave ou URL em log, `toString` ou erro (por mutação) | `Phase3LogHygieneIT` |
| 11 | Sem ciclo; interfaces públicas como únicas pontes; entidades sem Lombok perigoso; classes puras sem Spring | `Phase3ArchitectureTest` |
| 12 | `ddl-auto=validate` contra V001–V029 com todas as entidades (fecha o teste 120 do banco e o 84 da Fase 2) | `Phase3SchemaValidateIT` |

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Os 12 itens da tabela da seção 4 estão verdes numa execução limpa de `mvn verify`.
- [ ] Nenhum 500 em qualquer teste de concorrência (20 repetições cada).
- [ ] A mutação de log reprova o teste (prova que a asserção detecta vazamento).
- [ ] Cobertura ≥ 80% nas classes de serviço indicadas.
- [ ] US-P3-01…15, FR-1…17 e metas 1…9 do PRD global têm um teste rastreado.
- [ ] Os pontos 1 a 14 da seção 9 da spec global estão resolvidos ou encaminhados; o ponto 5 tem smoke registrado no Supabase real.
- [ ] Nenhum arquivo de `src/main` ou de `database/` foi alterado por esta feature.
