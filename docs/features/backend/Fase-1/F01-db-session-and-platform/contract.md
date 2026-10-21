# Contract — F01 Sessão do banco e plataforma

## 1. Resumo
- **Feature:** F01-db-session-and-platform
- **Versão:** 1.0.0
- **Consumidores:** F02–F05 (todas as features clínicas) e o front-end React (formato de erro e `no-store`). Não expõe endpoint próprio.

## 2. Entradas

### 2.1 Do `TenantContext` (preenchido pelo JWT na Fase 0)
| Campo | Origem | Obrigatório |
|---|---|---|
| `clinicId` | claim `clinic_id` | sim (sem ele: 401) |
| `userId` | claim `sub` | não (fluxos de `TenantRunner` não têm) |
| `role` | claim `role` (`DENTIST_ADMIN` \| `RECEPTIONIST`) | não (idem) |

### 2.2 Do banco (V007–V013)
- Funções `core.current_clinic_id()`, `core.current_app_role()`, `core.current_user_id()`.
- Códigos de erro: `42501`, `28000`, `P0002`, `CR001`, `CR002`, `CR003`, `23505`, `23514`, `23503`.

## 3. Saídas

### 3.1 Variáveis de sessão (efeito no banco, por transação)
| Variável | Valor | Ausente/inválido |
|---|---|---|
| `app.clinic_id` | UUID canônico | transação não começa (401) |
| `app.role` | `DENTIST_ADMIN` ou `RECEPTIONIST`, ou `''` | `current_app_role()` = `NULL`: tabelas clínicas negam |
| `app.user_id` | UUID ou `''` | `current_user_id()` = `NULL`: gravação clínica falha (`28000`) |

Todas por `set_config(..., true)` (locais à transação), em **uma** instrução.

### 3.2 API Java interna (`shared`)
```java
UUID  TenantIdProvider.currentClinicId();        // lança BusinessException 401
Role  TenantIdProvider.currentRoleOrNull();
UUID  TenantIdProvider.currentUserIdOrNull();
Optional<String>    DatabaseErrors.sqlState(Throwable t);
Optional<String>    DatabaseErrors.constraintName(Throwable t);
Optional<LocalDate> DatabaseErrors.retentionReleaseDate(Throwable t);
```

### 3.3 Erros HTTP (formato `application/problem+json` da Fase 0)
| SQLState / origem | Status | `type` | Mensagem |
|---|---|---|---|
| `CR001` | 409 | `conflict` | "Inative o paciente antes de anonimizar." |
| `CR002` | 409 | `conflict` | "Retenção legal do prontuário vigente. Liberado em AAAA-MM-DD." (sem data se não extraível) |
| `CR003` | 409 | `conflict` | "Paciente anonimizado." |
| `P0002` | 404 | `not-found` | "Paciente não encontrado." |
| `42501` | 403 | `forbidden` | padrão |
| `28000`, `23514`, `23503` | 500 | `internal` | genérica |
| `OptimisticLockingFailureException` | 409 | `conflict` | inalterado |
| banco indisponível | 503 | `unavailable` | inalterado |

### 3.4 Cabeçalho
`Cache-Control: no-store` em toda resposta de `/api/v1/patients/**` e `/api/v1/audit-log/**`.

## 4. Regras e limites
- Somente o dialeto de transação escreve as três variáveis.
- A mensagem do driver nunca é logada nem devolvida; log de erro de banco = classe + `SQLState` + constraint.
- `23505` é convertido pelo service dono da constraint; sem conversão, vira 500.
- Papel e usuário vêm só do JWT validado.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Uma transação autenticada executa exatamente uma instrução de sessão com as três variáveis (inspeção de SQL).
- [ ] Pool de 1 conexão: nenhuma variável vaza entre transações de tenants e papéis diferentes.
- [ ] `app.role = RECEPTIONIST` forjado + filtro desligado: leitura de prontuário/exames/auditoria devolve 0 linhas.
- [ ] `app.role` ausente/inválido: `patients` devolve 0 linhas e a gravação falha.
- [ ] Gravação clínica sem `app.user_id` falha com `28000` e a API responde 500 `internal`.
- [ ] Cada linha da tabela 3.3 é coberta por um teste do handler.
- [ ] Violação de CPF duplicado não deixa o CPF no log.
- [ ] Respostas das rotas clínicas trazem `no-store`.
- [ ] `mvn verify` da Fase 0 segue verde (testes de tenant atualizados).
