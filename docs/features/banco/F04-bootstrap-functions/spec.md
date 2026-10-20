# Spec — F04 Funções de Login, Refresh e Limpeza

Complementa `docs/global/banco/spec.md` (seções 1.1, 3.3 e 4).

## 1. Visão técnica
- **Feature:** F04 Funções de bootstrap.
- **Stack:** PL/pgSQL / SQL, `SECURITY DEFINER`, Flyway (`V006`).
- **Abordagem:** funções pertencentes a `crm_definer`, que acessa as tabelas por políticas RLS dedicadas (sem `BYPASSRLS`).

## 2. Objetos (migration `V006__core_bootstrap_functions.sql`, como `crm_owner`)

### 2.1 Privilégios e políticas do `crm_definer`
```sql
GRANT SELECT (id, clinic_id, email, password_hash, role, active) ON core.users TO crm_definer;
GRANT SELECT, DELETE ON core.refresh_tokens TO crm_definer;

CREATE POLICY users_definer_sel ON core.users
  FOR SELECT TO crm_definer USING (true);
CREATE POLICY refresh_definer_sel ON core.refresh_tokens
  FOR SELECT TO crm_definer USING (true);
CREATE POLICY refresh_definer_del ON core.refresh_tokens
  FOR DELETE TO crm_definer USING (expires_at < now() - interval '30 days');
```
`USING (true)` aqui é seguro porque o papel não tem login, só é alcançável pelas funções e as colunas concedidas são mínimas.

### 2.2 Funções (ilustrativo)
```sql
CREATE FUNCTION core.find_user_for_login(p_email varchar)
RETURNS TABLE (id uuid, clinic_id uuid, password_hash varchar, role varchar, active boolean)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
  SELECT u.id, u.clinic_id, u.password_hash, u.role, u.active
  FROM core.users u WHERE u.email = lower(p_email)
$$;
ALTER FUNCTION core.find_user_for_login(varchar) OWNER TO crm_definer;
REVOKE EXECUTE ON FUNCTION core.find_user_for_login(varchar) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION core.find_user_for_login(varchar) TO crm_app;
```
| Função | Retorno | Observações |
|---|---|---|
| `core.find_user_for_login(p_email varchar)` | `id, clinic_id, password_hash, role, active` | `u.email = lower(p_email)` para usar o índice único |
| `core.find_refresh_token(p_hash varchar)` | `id, clinic_id, user_id, family_id, expires_at, used_at, revoked_at` | `WHERE token_hash = p_hash` |
| `core.purge_expired_refresh_tokens()` | `integer` | `DELETE ... WHERE expires_at < now() - interval '30 days'` + contagem (`GET DIAGNOSTICS`); `VOLATILE` |

## 3. Componentes
`V006`, `tests/bootstrap_functions.sql`, e o uso pelo backend (`AuthLookupRepository`) e pelo `db-maintenance.yml`.

## 4. Lógica
1. Backend chama `SELECT * FROM core.find_user_for_login(:email)` sem `app.clinic_id`.
2. A função roda como `crm_definer`; a política `users_definer_sel` permite a leitura; o `WHERE` limita a uma linha.
3. O backend valida a senha e emite o token com o `clinic_id` retornado.
4. Rotas seguintes definem `app.clinic_id` e usam o caminho normal (RLS por tenant).
5. Limpeza: `db-maintenance.yml` chama `SELECT core.purge_expired_refresh_tokens()` como `crm_app`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| E-mail inexistente | Função retorna 0 linhas; o backend responde 401 genérico |
| E-mail com maiúsculas | `lower()` normaliza |
| Hash inexistente | 0 linhas |
| Nenhum token vencido | Função retorna 0 |
| `crm_app` tenta ler `users` sem tenant | 0 linhas (só a função enxerga) |
| `PUBLIC` tenta executar | Negado por `REVOKE` |
| `search_path` manipulado pelo chamador | Ignorado: a função fixa `pg_catalog, pg_temp` e qualifica `core.*`; tabela temporária homônima não sombreia `core.users` (teste) |

## 6. Segurança e desempenho
- Sem SQL dinâmico; parâmetros tipados; retorno mínimo; `STABLE` onde só lê.
- O `crm_definer` não faz login, não é dono de tabelas e tem políticas só de leitura (e `DELETE` restrito por data).
- `Index Scan` em `ux_users_email` e `ux_refresh_hash`; ≤ 5 ms com 100 mil usuários.

## 7. Notas de implementação (V006)
- A migration roda em **uma transação** (Flyway; o harness local usa `psql --single-transaction`): a concessão temporária `GRANT CREATE ON SCHEMA core TO crm_definer` (exigida pelo `ALTER FUNCTION ... OWNER TO`) é revogada no fim e nunca fica visível.
- `crm_owner` é `NOINHERIT`: ser membro de `crm_definer` permite **assumir** o papel (suficiente para trocar o dono), mas não herda os privilégios de dono. Por isso `REVOKE/GRANT EXECUTE` são feitos **antes** do `ALTER ... OWNER`, quando `crm_owner` ainda é o dono; o PostgreSQL transfere as entradas de ACL junto com o dono.
- `purge_expired_refresh_tokens()` filtra por 30 dias na função **e** na política de `DELETE` do `crm_definer` (defesa em profundidade).
