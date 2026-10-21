# Contract — F02 Base de Tenant e RLS

## 1. Resumo
- **Feature:** F02-tenant-and-rls-foundation
- **Versão:** 1.0.0
- **Consumidores:** F03, F04, F06 (banco); backend (define a variável); todas as tabelas das fases 1–4.

## 2. Entradas

### 2.1 Da aplicação (backend)
A cada transação autenticada: `SELECT set_config('app.clinic_id', '<uuid>', true)` (equivale a `SET LOCAL`). Valor sempre UUID válido, vindo do token, nunca do cliente.

### 2.2 Das migrations seguintes
Cada tabela de negócio segue a receita abaixo.

| # | Item obrigatório | Verificação |
|---|---|---|
| 1 | `clinic_id` NOT NULL, FK, índice | Catálogo |
| 2 | RLS `ENABLE` + `FORCE` | Catálogo |
| 3 | Políticas por operação com `core.current_clinic_id()` | Catálogo |
| 4 | Política `SELECT` para `crm_backup` | Catálogo |
| 5 | Grants mínimos a `crm_app`; `SELECT` a `crm_backup` | Teste de permissões |
| 6 | Triggers `set_updated_at` e `forbid_immutable_columns` | Teste da tabela |

## 3. Saídas

| Objeto | Assinatura | Contrato |
|---|---|---|
| `core.current_clinic_id()` | `() → uuid` | UUID do tenant ou `NULL`; erro se o valor não for UUID |
| `core.set_updated_at()` | trigger | Atualiza `updated_at` |
| `core.forbid_immutable_columns()` | trigger, argumentos `text` | Erro `23514` se alguma coluna listada mudar; erro `42703` se o nome listado não existir na tabela |

`EXECUTE` de `current_clinic_id()` somente para `crm_app`. As funções de trigger não são chamadas diretamente.

> **Emenda (Fase 1, V007):** `crm_definer` também recebe `EXECUTE` em `current_clinic_id()`, para as políticas escopadas ao tenant das funções `SECURITY DEFINER` da Fase 1 (`write_audit`, `anonymize_patient`). Ver `docs/features/banco/Fase-1/F01-session-foundation/contract.md`.

## 4. Regras e limites
- Sem `app.clinic_id`: nenhuma linha visível e nenhuma gravação (falha fechada).
- Nenhuma política pode usar `USING (true)` para `crm_app`.
- `USING (true)` só é permitido para `crm_backup` (somente `SELECT`) e `crm_definer` (F04).
- A função de tenant nunca é `IMMUTABLE`.
- `app.user_id` e `app.role` são reservadas; não implementar políticas sobre elas antes da Fase 1.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 4: sem `app.clinic_id`, `SELECT` retorna 0 linhas e `INSERT` falha.
- [ ] Teste 5: após `COMMIT`, a variável volta vazia na mesma conexão (`set_config` local).
- [ ] Valor não UUID em `app.clinic_id` aborta a transação.
- [ ] `core.current_clinic_id()` não é executável por `PUBLIC`.
- [ ] Trigger `forbid_immutable_columns` rejeita a mudança de coluna listada e aceita as demais.
- [ ] Teste de catálogo falha quando uma tabela com `clinic_id` não tem RLS `FORCE` ou política do `crm_backup`; também acusa tabela sem índice iniciando por `clinic_id`, com `USING (true)` para `crm_app`/`PUBLIC`, ou com `DELETE`/`TRUNCATE` concedido sem política (`tests/catalog_rls_pattern.sql`, com auto-teste).
