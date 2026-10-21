# Contract — F01 Fundação de Sessão e Extensão (Fase 1)

## 1. Resumo
- **Feature:** F01-session-foundation (Fase 1)
- **Versão:** 1.0.0
- **Consumidores:** políticas e triggers das features F02–F06; backend (aspecto de tenant que define as variáveis).

## 2. Entradas (variáveis de sessão definidas pelo backend)

| Variável | Valores | Obrigatória |
|---|---|---|
| `app.clinic_id` | uuid | Sim, em toda rota autenticada (Fase 0) |
| `app.role` | `DENTIST_ADMIN` ou `RECEPTIONIST` (exato, maiúsculas) | Sim, em toda rota autenticada a partir da Fase 1 |
| `app.user_id` | uuid do usuário autenticado | Sim, para qualquer gravação clínica |

Todas por `set_config(nome, valor, true)` na **mesma transação** da operação.

## 3. Saídas

| Função | Retorno | Quando `NULL` |
|---|---|---|
| `core.current_app_role()` | `varchar` | Ausente, vazio ou fora da lista |
| `core.current_user_id()` | `uuid` | Ausente ou vazio |
| `core.clinical_retention_period()` | `interval` (`20 years`) | Nunca |

### Erros
`22P02` se `app.clinic_id` ou `app.user_id` não for UUID.

## 4. Regras e limites
- `EXECUTE` das três funções e de `current_clinic_id()` para `crm_app` e `crm_definer`; nunca `PUBLIC`.
- `search_path = pg_catalog`; sem `SECURITY DEFINER`.
- Extensão `pg_trgm` no schema `extensions`; `crm_owner` com `USAGE`; `crm_app` sem acesso ao schema.
- O prazo de 20 anos só se altera por nova migration (nunca por configuração).

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 35: `app.role` ausente, vazio ou inválido → `current_app_role()` é `NULL`.
- [ ] `current_user_id()` devolve o uuid definido e `NULL` sem a variável; valor inválido → `22P02`.
- [ ] Teste 53 (parte): as funções têm `search_path` fixo e `EXECUTE` apenas para `crm_app` e `crm_definer`.
- [ ] `clinical_retention_period()` = 20 anos e `IMMUTABLE`.
- [ ] `pg_trgm` instalada; `crm_owner` cria um índice `gin_trgm_ops`; `crm_app` sem `USAGE` em `extensions`.
- [ ] `20_extensions.sql` roda duas vezes sem erro.
