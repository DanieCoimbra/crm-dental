# Spec — F08 Verificação da Fase 3

Complementa `docs/global/backend/Fase-3/spec.md` (seções 6, 8, 9 e 11).

## 1. Visão técnica
- **Feature:** F08 Verificação da fase.
- **Stack:** JUnit 5, Spring Boot Test, `@WebMvcTest` + `spring-security-test`, Testcontainers PostgreSQL (V001–V029), MockMvc, `JdbcTemplate`, leitura de fontes (padrão `Phase*ArchitectureTest`), `PerfLimits`.
- **Abordagem:** só testes e documentação de evidência; reutiliza `Phase3ItSupport`, `Phase3Routes` e os `FileStorage` de teste da F01. Nenhum código de produção muda.

## 2. Dados
- **Ambiente:** o contêiner aplica `database/bootstrap` e `database/migrations` V001–V029; cada teste cria suas clínicas (`TenantFixtures`) e não depende de dados de outro.
- **Semente de desempenho:** 5 mil parcelas e 5 mil pagamentos por clínica, inseridos por `JdbcTemplate` em lote como dono do schema (a RLS `FORCE` exige as variáveis de sessão ou o papel certo), mais 100 mil de cada no banco quando o teste exigir.
- **Test doubles:** `FaultyFileStorage` (decora o `LocalFileStorage`; falha em `save`, `delete` ou com latência configurável) e um `LogCapture` (appender em memória) para a higiene de log.

## 3. Componentes de teste
| Classe | Cobre |
|---|---|
| `Phase3RoleMatrixWebTest` | Matriz 401/403/2xx de todas as rotas (`Phase3Routes`), DTOs que ignoram campos proibidos, `no-store`, formato `ProblemDetail`, `Retry-After` |
| `Phase3RoleMatrixIT` | A mesma matriz contra o banco real |
| `Phase3TenantIsolationIT` | Duas clínicas, filtro desligado, SQL nativo, 0 linhas da outra nas seis tabelas e nos relatórios |
| `Phase3ForgedRoleIT` | `app.role = RECEPTIONIST` forjado, papel ausente e inválido |
| `MoneyInvariantsIT` | Propriedade (a)–(d) em ≥ 200 sequências com semente fixa e registrada |
| `Phase3ConcurrencyIT` | Pagamento, numeração, plano, revisão, item × envio |
| `BudgetSendAtomicityIT` | Falha em `save`, `delete`, `INSERT` e commit |
| `InstallmentCommitIT` | `CR011` no commit (`TransactionSystemException`) |
| `AnonymizationCommercialIT`, `AuditCommercialIT` | F07 ponta a ponta |
| `Phase3PerformanceIT`, `Phase3PoolIT` | p95, planos sem `Seq Scan`, pool com envios lentos |
| `Phase3LogHygieneIT` | Nenhum valor, nome, observação, chave ou URL em log/`toString`/erro; mutação |
| `Phase3ArchitectureTest` | Sem ciclo; `finance → commercial` só por `BudgetLookup`; `scheduling → catalog` só por `ProcedureTypeLookup`; `patient` sem `commercial`/`finance`; entidades sem `@Data`/`@ToString`/`@EqualsAndHashCode`; classes puras sem Spring |
| `Phase3SchemaValidateIT` | `ddl-auto=validate` com todas as entidades |

## 4. Lógica
**Invariantes (propriedade):** gerador com semente registrada produz sequências de operações válidas e inválidas (criar, editar, enviar, aprovar, planejar AUTO/MANUAL, pagar parcial e total, estornar, refazer); após cada sequência, consultas por `JdbcTemplate` conferem (a) total do orçamento, (b) soma das parcelas ativas, (c) líquido ≤ valor, (d) `PAID` ⇔ líquido ≥ valor; operações inválidas devem retornar 4xx sem alterar contagens.

**Concorrência:** `ExecutorService` com `CountDownLatch` dispara as duas requisições juntas (MockMvc em threads, sem `@Transactional` de teste); repete 20 vezes; afirma status e estado final; zero 500.

**Atomicidade do envio:** para cada ponto de falha injetado, afirma `status = DRAFT`, `count(budget_documents) = 0` e o `FileStorage` sem a chave (ou o evento `StorageCompensationFailed` quando a compensação falha); no commit incerto, afirma que o objeto **não** é apagado quando a linha existe.

**Higiene de log:** appender captura os logs de todos os testes da fase; afirma ausência de padrões (valores monetários semeados, nome, `notes`, `storage_key`, URL assinada); a mutação injeta um valor em um log e o teste deve falhar.

**Desempenho:** aquecimento + N requisições com 4 threads; p95 comparado a `PerfLimits`; `EXPLAIN` das consultas dos relatórios afirma ausência de `Seq Scan`.

**Rastreabilidade:** ao fim da implementação, `traceability.md` liga US/FR/metas a classes e métodos (no padrão da Fase 2).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Teste de concorrência instável | Aumentar repetições antes de relaxar a asserção; nunca aceitar 500 |
| Semente grande lenta | Inserção em lote por SQL; reuso do contêiner |
| `FaultyFileStorage` mascarando erro real | Só decora o `LocalFileStorage`; a falha é explícita e configurada por teste |
| Ponto 5 da spec (pooler 6543) | Não coberto por IT; registrado como smoke manual no Supabase real |
| Defeito achado | Vira correção na feature dona (F01–F07); esta feature não altera produção |
| Suíte das Fases 1 e 2 vermelha | Corrigir o teste que assumia o comportamento antigo (inativar/anonimizar), não o código novo |

## 6. Segurança e desempenho
- Os testes provam as três barreiras (filtro, RLS, papel no banco) e que o `42501` num `INSERT`/`UPDATE` seria defeito do backend.
- Metas de desempenho: relatórios e ficha p95 ≤ 100 ms; pagamento p95 ≤ 100 ms; orçamento de 20 itens p95 ≤ 250 ms; envio p95 ≤ 1,5 s; leitura com 3 envios lentos p95 ≤ 200 ms.
- Nenhum teste imprime valor, nome ou chave real; as fixtures usam dados fictícios (projeto de portfólio).
