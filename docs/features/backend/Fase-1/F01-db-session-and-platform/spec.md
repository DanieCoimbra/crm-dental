# Spec — F01 Sessão do banco e plataforma

Complementa `docs/global/backend/Fase-1/spec.md` (seções 3.1, 4.1, 5.1, 6.1 e 7). Sem código de aplicação: assinaturas e SQL ilustrativos.

## 1. Visão técnica
- **Feature:** F01 Sessão do banco e plataforma.
- **Stack:** Spring Data JPA/Hibernate 7, Spring Transaction, Spring MVC (`@RestControllerAdvice`), Spring Security headers, SLF4J.
- **Abordagem:** altera classes de `shared` da Fase 0 (`tenant`, `error`, `config`/`web`). Sem migrations nem dependências novas.

## 2. Dados
- **Banco:** nenhuma alteração. Usa as funções `core.current_clinic_id()`, `core.current_app_role()` e `core.current_user_id()` (V007).
- **Estado em memória:** nenhum além do `TenantContext` existente.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `TenantHibernateJpaDialect` (alterado) | Após `beginTransaction`, executa `SELECT set_config('app.clinic_id', ?, true), set_config('app.role', ?, true), set_config('app.user_id', ?, true)` com 3 parâmetros ligados; depois habilita o filtro de tenant (como hoje) |
| `TenantIdProvider` (alterado) | `currentClinicId()` (lança 401), `currentRoleOrNull()` e `currentUserIdOrNull()` (não lançam) |
| `TenantContext` (leitura) | Acesso de pacote ao *snapshot* para o provider, sem expor setters novos |
| `DatabaseErrors` (novo, `shared/error`) | `Optional<String> sqlState(Throwable)`, `Optional<String> constraintName(Throwable)`, `Optional<LocalDate> retentionReleaseDate(Throwable)` (regex sobre `Detail: liberado em AAAA-MM-DD`) |
| `ConstraintViolations` (alterado) | Constantes `PATIENT_CPF = "ux_patients_clinic_cpf"` e `HISTORY_PATIENT = "ux_histories_patient"` |
| `GlobalExceptionHandler` (alterado) | `@ExceptionHandler` para `DataAccessException` e `jakarta.persistence.PersistenceException` por `SQLState` (tabela 5) |
| `ClinicalCacheHeaders` (novo, `shared/web`) | Aplica `Cache-Control: no-store` aos caminhos `/api/v1/patients/**` e `/api/v1/audit-log/**` |
| `application*.yml` (alterado) | `spring.jpa.show-sql=false`; níveis dos loggers `org.hibernate.SQL` e `org.hibernate.orm.jdbc.bind` sem parâmetros em todos os perfis |

## 4. Lógica
1. **Sessão:** papel → `Role.name()` ou `''`; usuário → `UUID.toString()` ou `''`; tenant continua obrigatório. Uma única instrução por transação (nenhuma ida extra).
2. **Tradução:** o handler localiza o `SQLState` percorrendo as causas. `CR001`/`CR003` → 409; `CR002` → 409 com a data (ou a frase sem data); `P0002` → 404; `42501` → 403; `28000`, `23514`, `23503` → 500 com `log.error` de classe, `SQLState` e constraint. `23505` **não** é tratado aqui: cada service converte pela constraint (F02, F03) e, se chegar ao handler, vira 500 sem logar a mensagem.
3. **Log seguro:** nenhum `log.*(..., ex)` com a exceção inteira para erros de banco; somente campos extraídos por `DatabaseErrors`.
4. **Cache:** filtro ou `HeaderWriter` por *matcher* de caminho; não altera rotas de auth.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Transação sem tenant | 401 antes de abrir conexão (inalterado) |
| Fluxo `TenantRunner` sem papel/usuário | Variáveis `''`; tabelas clínicas negam, tabelas da Fase 0 funcionam como antes |
| `DETAIL` de `CR002` fora do formato | Resposta 409 sem data |
| Exceção sem `SQLException` na cadeia | Cai no handler genérico (500) |
| Duas transações no mesmo pool de 1 conexão | Valores locais expiram no commit/rollback; sem vazamento |

## 6. Segurança e desempenho
- Valores sempre ligados como parâmetros (nenhuma concatenação).
- A instrução única mantém o custo de uma ida ao banco por transação.
- Nenhum dado pessoal em log; só identificadores técnicos.
