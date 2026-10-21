# Spec — F02 Contexto de Tenant (backend)

Complementa `docs/global/backend/spec.md` (seções 2.1, 2.2 e 4.6). O schema, as políticas RLS e os papéis são da função banco.

## 1. Visão técnica
- **Feature:** F02 Contexto de Tenant.
- **Stack:** Hibernate 7 (`@FilterDef`/`@Filter`), Spring AOP / `TransactionSynchronization`, Spring Security (claims do JWT), HikariCP.
- **Abordagem:** `TenantContext` por requisição alimenta (a) `set_config` na transação e (b) o filtro Hibernate; o banco é a barreira final.

## 2. Dados e estado
- **Banco:** nenhuma tabela e nenhuma migration.
- **Estado em memória:** `TenantContext` (ThreadLocal) com `clinicId`, `userId`, `role`; limpo ao fim da requisição.
- **Configuração:** datasource com `DB_APP_USER` (`crm_app`) via Supavisor *transaction* (porta 6543); `hibernate.default_schema=core`; `ddl-auto=validate` (sem Flyway no projeto); URL com `prepareThreshold=0` se houver pooler em modo *transaction*.

## 3. Componentes (`shared/tenant`)
| Componente | Responsabilidade |
|---|---|
| `TenantContext` | Guarda `clinicId`, `userId`, `role`; `clinicId()` lança `BusinessException(unauthorized)` se ausente |
| `TenantContextFilter` | Após a validação do JWT (F04), preenche o contexto a partir das claims |
| `TenantJpaTransactionManager` + `TenantHibernateJpaDialect` | Gancho no início de cada transação JPA (cobre também repositórios Spring Data e `TransactionTemplate`, o que um aspecto sobre `@Transactional` não alcança): `set_config('app.clinic_id', :id, true)` e habilita `tenantFilter`; sem contexto, aborta antes do banco |
| `TenantAwareEntity` | Superclasse com `clinic_id` imutável e `@Filter` |
| `TenantIdProvider` | Único ponto que entrega o `clinic_id` às entidades |
| `TenantRunner` | Executa um bloco sob um tenant explícito (tarefas fora de requisição) |
| `TenantPersistenceConfig` | Troca o gerenciador de transações pelo que aplica o tenant. Datasource do papel de aplicação e Hikari (máx. 10) vêm de `spring.datasource.*`; `prepareThreshold=0` em `application-prod.yml` |

## 4. Lógica
1. Requisição autenticada → `TenantContextFilter` extrai `clinic_id` do JWT.
2. Service `@Transactional` abre transação → aspecto executa `set_config(..., true)` e habilita o filtro.
3. Repositórios leem/gravam; o Hibernate acrescenta `clinic_id = ?`; o banco aplica a RLS.
4. Fim da transação → o valor local expira com ela; o `TenantContext` é limpo.
- **Rotas públicas** (registro, login, refresh) não têm contexto prévio: registro define o tenant com o UUID gerado (F04); login e refresh usam as funções de bootstrap do banco.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Operação de negócio sem tenant | 401 `unauthorized` antes de abrir o acesso ao banco |
| Violação de RLS inesperada | 500 `internal`, logada com `X-Request-Id`, sem detalhe SQL |
| `app.clinic_id` vazando no pool | Só `set_config(..., true)` dentro de transação; teste com pool de 1 conexão |
| Entidade com `clinic_id` divergente do contexto | `WITH CHECK` do banco bloqueia; o `TenantIdProvider` impede no aplicativo |
| SQL nativo sem o filtro | RLS ainda isola; revisão exige JPA/JPQL |
| Nome da variável/função divergente do banco | Teste de integração falha na CI |

## 6. Segurança e desempenho
- Conexão sempre com o papel de aplicação (sem `BYPASSRLS`, não dono), definido pelo banco.
- Sobrecarga de `set_config` por transação esperada < 1 ms; medir em teste.
- Pool Hikari máximo 10 conexões.
