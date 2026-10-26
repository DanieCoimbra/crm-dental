# Spec — F01 Plataforma da Fase 3

Complementa `docs/global/backend/Fase-3/spec.md` (seções 1.1, 2.2, 3.1, 4.1, 4.2, 4.13, 5.1, 6.1, 7 e 8).

## 1. Visão técnica
- **Feature:** F01 Plataforma da fase (base compartilhada).
- **Stack:** Java 21, Spring Boot 4.1.1, Spring Security 7, Spring Web MVC, `RestClient` (Supabase Storage), OpenPDF (só a dependência), ArchUnit/leitura de fontes (padrão `Phase*ArchitectureTest`), Testcontainers.
- **Abordagem:** nenhuma regra de negócio; só o que várias features reutilizam. Sem migrations: o banco já está em V029.

## 2. Dados
- **Banco:** nenhuma mudança.
- **Configuração:** `crm.documents.*` (spec global, seção 7); `CrmProperties.Documents` com padrões; sem variável de ambiente nova.
- **Estado em memória:** nenhum nesta feature (`DocumentSlots` e `DocumentRateLimiter` nascem na F04).

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `Money` (`shared.money`) | `parse`, `parsePositive`, `format`, `splitDown`; constantes `CAP = 999999.99`, `SCALE = 2`; lança `IllegalArgumentException` (traduzida em 400 pelos DTOs/validadores) |
| `StorageKeys` (alterado) | `of`, `ofBudget`, `require` (duas regex fechadas), `belongsTo` |
| `StorageConfig` (alterado) | Bean `fileStorage` (exames, 30 s) e bean `documentStorage` (`@Qualifier("documentStorage")`, 10 s); mesmo bucket e chave |
| `CrmProperties` (alterado) | Record `Documents(uploadTimeout, maxConcurrent, sendsPerMinute, transactionTimeout)` com padrões |
| `GlobalExceptionHandler` (alterado) | `switch` de `SQLState` ampliado; `uniqueViolation` ampliado; `@ExceptionHandler(TransactionSystemException.class)` |
| `ConflictMessages` (alterado) | Constantes das mensagens 409 novas |
| `SecurityConfig` (alterado) | Regras por rota da seção 6.1 da spec global |
| `ClinicalCacheHeaders` (alterado) | Prefixos novos |
| `ClinicIdentity` (`clinic.service`) | `ClinicInfo current()`; `record ClinicInfo(String name, String address, String phone)`; implementação lê a `Clinic` do tenant |
| `AnonymizationContributor` (`patient.service`) | `Collection<String> objectKeys(UUID patientId)`; `default int openItems(UUID patientId) { return 0; }` |
| `Phase3ItSupport`, `Phase3ArchitectureTest`, `Phase3SchemaValidateIT`, `Phase3Routes` | Infraestrutura de teste |

## 4. Lógica
**`Money.parse`:** recusa `null`, vazio, notação científica, sinal, vírgula, espaço e mais de 2 casas; `new BigDecimal(text).setScale(2)` (sem arredondar: a regex já garante ≤ 2 casas); valor > teto → erro. **`splitDown(total, n)`:** `base = total.divide(n, 2, DOWN)`; as `n − 1` primeiras valem `base`; a última, `total − base × (n − 1)`.

**`StorageKeys.require`:** `^U/U/U$` ou `^U/U/budgets/U$`, com `U` = UUID canônico minúsculo; qualquer outra entrada → `StorageException("Chave de armazenamento inválida.")`.

**Handler de erros:** `handleDatabase` ganha `CR006`–`CR011` → 409 com `ConflictMessages`; `handleTransactionSystem` percorre a cadeia de causas com `DatabaseErrors.sqlState` e reutiliza o mesmo `switch` (sem `SQLState` conhecido → 500 `internal`, log de classe e `SQLState` só). `uniqueViolation` ganha `ux_ptype_clinic_name`, `ux_pay_reverses` e `ux_inst_budget_number`. `23514` com `ck_items_line`/`ck_budgets_total` → 422 com `reason: AMOUNT_OUT_OF_RANGE` (via `BusinessException` com `reason`).

**Rotas (`SecurityConfig`):** na ordem, mais específico antes do mais geral: `POST|PUT|PATCH` de `/procedure-types[/**]` → D; `POST /budgets`, `POST /budgets/**`, `PUT /budgets/**` → D; `GET /budgets/*/installments` → D; `/installments/**`, `/payments/**`, `/finance/**`, `/patients/*/financial` → D; leituras restantes em `authenticated()`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `Money.parse("1e3")`, `"-1"`, `"1,5"`, `"1.234"`, `"1000000.00"`, `""` | Erro de validação (400 no DTO) |
| `StorageKeys.require` com `..`, segmento extra, UUID maiúsculo ou prefixo de outra forma | `StorageException` |
| `TransactionSystemException` sem `SQLState` | 500 `internal`, log de classe e `SQLState` |
| `CR011` no commit | 409 "As parcelas não somam o total do orçamento." |
| `documentStorage` sem `crm.storage.url` (dev/teste) | `LocalFileStorage` |
| Propriedade `crm.documents.*` ausente | Padrões do record |

## 6. Segurança e desempenho
- `StorageKeys` continua a trava de isolamento de objetos por clínica: regex fechadas, sem concatenação de texto do cliente.
- Nenhum log ganha dado novo; o handler não registra mensagem nem `DETAIL` do driver.
- `Money` e `StorageKeys` são funções puras; sem custo mensurável. Sem impacto de desempenho nas rotas existentes.
