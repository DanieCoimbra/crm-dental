# Contract — F02 Contexto de Tenant (backend)

## 1. Resumo
- **Feature:** F02-tenant-context
- **Versão:** 1.1.0
- **Consumidores:** F04, F05 e todos os módulos das fases 1–4. Contrato interno, sem endpoints HTTP.
- **Provedor do outro lado:** função **banco** (`postgres-dba-architect`).

## 2. Entradas

### 2.1 Do banco (nomes definitivos; ver `docs/features/banco/F02-tenant-and-rls-foundation/contract.md`)
| Item | Requisito |
|---|---|
| Papel de aplicação `crm_app` (`DB_APP_USER`) | `NOBYPASSRLS`, não dono, sem DDL |
| Variável de sessão | `app.clinic_id`, definida via `set_config(..., true)` |
| Função de tenant | `core.current_clinic_id() → uuid`, `NULL` quando ausente |
| Tabelas de negócio | `clinic_id uuid NOT NULL` indexado, RLS `ENABLE` + `FORCE`, política com `USING` e `WITH CHECK` |

### 2.2 Do token (F04)
Claims `clinic_id` (uuid), `sub` (uuid), `role` (`DENTIST_ADMIN` | `RECEPTIONIST`).

### 2.3 Dos módulos de negócio
Entidades de tabelas de negócio estendem `TenantAwareEntity`. Para uma tabela nova, o módulo pede ao banco a migration correspondente em `database/migrations` (o backend não cria tabelas); a migration deve seguir a receita da F02 do banco, incluindo a política de leitura do `crm_backup`.

## 3. Saídas

### Java (`crm_data.shared.tenant`)
| Elemento | Contrato |
|---|---|
| `TenantContext.clinicId()` | `UUID`; lança `BusinessException(unauthorized)` se ausente |
| `TenantContext.userId()` / `role()` | `UUID` / enum de papel |
| `TenantAwareEntity` | Campo `clinicId` imutável, preenchido pelo `TenantIdProvider` |
| `TenantJpaTransactionManager` + `TenantHibernateJpaDialect` | Em toda transação JPA (`@Transactional`, repositórios Spring Data, `TransactionTemplate`): sem tenant, 401 antes de tocar o banco; com tenant, define `app.clinic_id` e ativa o filtro |
| `TenantRunner.run(clinicId, bloco)` | Executa o bloco sob um tenant explícito (tarefas fora de requisição) |

### Erros
- Sem tenant: 401 `unauthorized`.
- Violação de RLS inesperada: 500 `internal` (formato da F01), sem detalhe SQL.

## 4. Regras e limites
- O `clinic_id` só pode vir do token; é proibido aceitá-lo em DTO, query ou path.
- `set_config` só com `is_local = true` e dentro de transação.
- Consultas a tabelas de negócio apenas por JPA/JPQL.
- O backend nunca conecta com o papel dono nem executa DDL.
- Pool Hikari máximo 10 conexões.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Contra os scripts do banco (Testcontainers), com o filtro Hibernate desligado, a clínica A nunca lê linhas da B.
- [ ] `INSERT` com `clinic_id` diferente do contexto falha.
- [ ] Sem tenant: operação de negócio retorna 401 e nada chega ao banco.
- [ ] Pool com 1 conexão: o tenant da requisição 1 não aparece na requisição 2.
- [ ] `ddl-auto=validate` passa contra o schema do banco.
- [ ] Teste de arquitetura falha se uma entidade de negócio não herdar `TenantAwareEntity`.
