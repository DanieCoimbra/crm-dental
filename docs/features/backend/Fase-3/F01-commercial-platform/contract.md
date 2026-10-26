# Contract — F01 Plataforma da Fase 3

## 1. Resumo
- **Feature:** F01-commercial-platform
- **Versão:** 1.0.0
- **Consumidores:** F02 a F08 (backend) e o Frontend React (formato de dinheiro e dos erros). Não expõe endpoint próprio.

## 2. Entradas (interfaces internas)

```java
// crm_data.shared.money.Money (puro, sem Spring)
static BigDecimal parse(String text);            // ^\d{1,6}(\.\d{1,2})?$ ; escala 2 ; 0..999999.99
static BigDecimal parsePositive(String text);    // >= 0.01
static String     format(BigDecimal value);      // toPlainString, escala 2
static List<BigDecimal> splitDown(BigDecimal total, int n);   // n>=1; DOWN; resto na última; soma == total
BigDecimal CAP = new BigDecimal("999999.99");

// crm_data.shared.storage.StorageKeys
static String of(UUID clinicId, UUID patientId, UUID fileId);        // <c>/<p>/<u>
static String ofBudget(UUID clinicId, UUID patientId, UUID objectId); // <c>/<p>/budgets/<u>
static String require(String key);               // aceita só as duas formas; senão StorageException
static boolean belongsTo(String key, UUID clinicId);

// crm_data.clinic.service.ClinicIdentity
ClinicInfo current();   // record ClinicInfo(String name, String address, String phone)

// crm_data.patient.service.AnonymizationContributor (SPI; implementada por commercial na F07)
Collection<String> objectKeys(UUID patientId);
default int openItems(UUID patientId) { return 0; }

// Beans de Storage
FileStorage fileStorage;        // exames, READ_TIMEOUT 30 s (inalterado)
@Qualifier("documentStorage") FileStorage documentStorage;   // PDFs, READ_TIMEOUT = crm.documents.upload-timeout
```

**Propriedades (todas opcionais, com padrão):**
```yaml
crm.documents.upload-timeout: 10s
crm.documents.max-concurrent: 3
crm.documents.sends-per-minute: 6
crm.documents.transaction-timeout: 20s
```

**Regras de rota (`SecurityConfig`):** `DENTIST_ADMIN` em `POST|PUT|PATCH /procedure-types[/**]`, `POST|PUT /budgets/**`, `GET /budgets/*/installments`, `/installments/**`, `/payments/**`, `/finance/**` e `/patients/*/financial`; as demais rotas novas exigem só autenticação.

## 3. Saídas

### Formato de dinheiro na API (para todas as features)
- **Saída:** string decimal com 2 casas (`"1234.50"`); **nunca** número JSON.
- **Entrada:** string que satisfaz `^\d{1,6}(\.\d{1,2})?$`; número JSON, notação científica, sinal, vírgula ou espaço → 400.

### Erros (formato `application/problem+json` da Fase 0)
| Origem | Status | `type` | Mensagem |
|---|---|---|---|
| `CR003` | 409 | `conflict` | "Paciente anonimizado." |
| `CR005` | 409 | `conflict` | "Paciente inativo." |
| `CR006` | 409 | `conflict` | "Quite ou cancele as parcelas em aberto antes de anonimizar." |
| `CR007` | 409 | `conflict` | "Orçamento já enviado: não pode mais ser alterado." |
| `CR008` | 409 | `conflict` | "Operação inválida para o status do orçamento." |
| `CR009` | 409 | `conflict` | "Operação inválida para o estado da parcela." |
| `CR010` | 409 | `conflict` | "Pagamento inválido: valor acima do saldo ou parcela cancelada." |
| `CR011` (no commit) | 409 | `conflict` | "As parcelas não somam o total do orçamento." |
| `23505` `ux_ptype_clinic_name` | 409 | `conflict` | "Já existe um procedimento ativo com este nome." |
| `23505` `ux_pay_reverses` | 409 | `conflict` | "Este pagamento já foi estornado." |
| `23505` `ux_inst_budget_number` | 409 | `conflict` | "O plano de parcelas foi alterado. Recarregue." |
| `23514` `ck_items_line` / `ck_budgets_total` | 422 | `business-rule` | `reason: AMOUNT_OUT_OF_RANGE` |
| Outras `23505` / `23514` / `23503` | 500 | `internal` | genérica; log só com a constraint |

## 4. Regras e limites
- A mensagem de erro nunca contém valor monetário, nome, observação, chave de objeto, URL ou o `DETAIL` do driver.
- `reason` do 422 desta fase: `PROCEDURE_INACTIVE`, `FUTURE_DATE`, `AMOUNT_OUT_OF_RANGE`, `PDF_TOO_LARGE`.
- Chave de Storage: no máximo 118 caracteres (`varchar(120)` no banco); só UUID canônico minúsculo.
- As respostas das rotas `/procedure-types`, `/budgets`, `/installments`, `/payments` e `/finance` levam `Cache-Control: no-store`.
- Nenhuma feature da fase altera `shared`, `SecurityConfig` ou o handler global depois desta.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] `Money.parse` recusa `1e3`, `-1`, `1,5`, `1.234`, `1000000.00` e vazio; aceita `0`, `10`, `10.5`, `999999.99`.
- [ ] `Money.splitDown` soma sempre o total (propriedade, totais e contagens aleatórios; resto na última).
- [ ] `StorageKeys.require` aceita as duas formas e recusa `..`, UUID maiúsculo, segmento extra e prefixo estranho.
- [ ] Os dois `FileStorage` coexistem (injeção por qualificador); o dos exames mantém 30 s.
- [ ] `TransactionSystemException` com `SQLState` `CR011` → 409; sem `SQLState` conhecido → 500.
- [ ] Cada código `CR006`–`CR011` e cada `23505` listado devolvem o status e a mensagem da tabela.
- [ ] Matriz de rotas: recepção recebe 403 em todas as rotas marcadas como D; sem token, 401.
- [ ] `Phase3ItSupport` aplica V001–V029; `Phase3ArchitectureTest` e `Phase3SchemaValidateIT` verdes; suíte das Fases 1 e 2 verde.
