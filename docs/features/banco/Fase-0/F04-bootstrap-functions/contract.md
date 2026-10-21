# Contract — F04 Funções de Login, Refresh e Limpeza

## 1. Resumo
- **Feature:** F04-bootstrap-functions
- **Versão:** 1.0.0
- **Consumidores:** backend `F04-authentication` (login, refresh, logout), `db-maintenance.yml` (limpeza).

## 2. Entradas

| Função | Parâmetro | Regra |
|---|---|---|
| `core.find_user_for_login(p_email varchar)` | e-mail | Qualquer caixa; comparado em minúsculas |
| `core.find_refresh_token(p_hash varchar)` | SHA-256 hex | 64 caracteres hexadecimais minúsculos |
| `core.purge_expired_refresh_tokens()` | — | — |

Chamadas **sem** `app.clinic_id` definido. Somente por `crm_app`.

## 3. Saídas

### `find_user_for_login`
```text
(id uuid, clinic_id uuid, password_hash varchar, role varchar, active boolean)  -- 0 ou 1 linha
```
### `find_refresh_token`
```text
(id uuid, clinic_id uuid, user_id uuid, family_id uuid,
 expires_at timestamptz, used_at timestamptz, revoked_at timestamptz)           -- 0 ou 1 linha
```
### `purge_expired_refresh_tokens`
`integer`: número de linhas apagadas.

### Erros
Nenhum erro de negócio: "não encontrado" = 0 linhas. Falhas inesperadas viram `SQLSTATE` do PostgreSQL (o backend responde 500 `internal`).

## 4. Regras e limites
- Retorno mínimo: nunca nome, dados da clínica ou de outros usuários.
- `EXECUTE` somente para `crm_app`; nunca `PUBLIC`.
- Dono `crm_definer`, `SECURITY DEFINER`, `search_path = pg_catalog, pg_temp` (mais restritivo que o previsto: `core` fora do caminho, objetos sempre qualificados, `pg_temp` por último), `STRICT` nas funções de busca (argumento `NULL` devolve 0 linhas), sem SQL dinâmico.
- `purge_expired_refresh_tokens` só apaga `expires_at < now() - interval '30 days'`.
- Nenhuma outra função pré-tenant pode ser criada sem revisão de segurança.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 13: login retorna 1 linha com e-mail em qualquer caixa e 0 para inexistente, sem tenant definido.
- [ ] Teste 14: `find_refresh_token` retorna a linha pelo hash sem tenant definido.
- [ ] Teste 15: a limpeza apaga só tokens vencidos há mais de 30 dias e retorna a contagem.
- [ ] Teste 16: `search_path` fixo e `EXECUTE` só para `crm_app`; as únicas funções `SECURITY DEFINER` de `core` são as três do contrato; `crm_definer` sem `CREATE` em `core`, sem login e com privilégios mínimos (6 colunas de `users`; `SELECT`/`DELETE` em `refresh_tokens`; nada em `clinics`).
- [ ] Médias em 1.000 chamadas com 100 mil usuários ≤ 5 ms (`tests/performance_indexes.sql`).
- [ ] `crm_app` consulta `core.users` diretamente sem tenant e recebe 0 linhas.
- [ ] `crm_app` não consegue `SET ROLE crm_definer`.
- [ ] `EXPLAIN` das duas buscas mostra `Index Scan` em índice único.
