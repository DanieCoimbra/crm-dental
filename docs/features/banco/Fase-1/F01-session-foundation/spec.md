# Spec — F01 Fundação de Sessão e Extensão (Fase 1)

Complementa `docs/global/banco/Fase-1/spec.md` (seções 1.1, 2.5 e 5.2).

## 1. Visão técnica
- **Feature:** funções de sessão, parâmetro de retenção e extensão `pg_trgm`.
- **Stack:** SQL, migration (`V007`), `psql` no bootstrap.
- **Abordagem:** três funções `SQL` leves e `STABLE`, sem `SECURITY DEFINER`; extensão instalada fora da migration, como administrador.

## 2. Objetos

### 2.1 `V007__core_session_functions.sql` (como `crm_owner`)
```sql
CREATE FUNCTION core.current_app_role() RETURNS varchar
  LANGUAGE sql STABLE PARALLEL SAFE SET search_path = pg_catalog
AS $$ SELECT CASE WHEN current_setting('app.role', true) IN ('DENTIST_ADMIN','RECEPTIONIST')
                  THEN current_setting('app.role', true) END $$;

CREATE FUNCTION core.current_user_id() RETURNS uuid
  LANGUAGE sql STABLE PARALLEL SAFE SET search_path = pg_catalog
AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

CREATE FUNCTION core.clinical_retention_period() RETURNS interval
  LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path = pg_catalog
AS $$ SELECT interval '20 years' $$;

REVOKE EXECUTE ON FUNCTION core.current_app_role(), core.current_user_id(),
                           core.clinical_retention_period() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION core.current_app_role(), core.current_user_id(),
                           core.clinical_retention_period() TO crm_app, crm_definer;
GRANT  EXECUTE ON FUNCTION core.current_clinic_id() TO crm_definer;
```

### 2.2 `bootstrap/20_extensions.sql` (como `postgres`, idempotente)
```sql
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA extensions;
GRANT USAGE ON SCHEMA extensions TO crm_owner;
```
Em bancos descartáveis o schema `extensions` é criado pelo script; no Supabase ele já existe.

## 3. Componentes
`V007`, `bootstrap/20_extensions.sql`, ajuste em `tests/bootstrap_checks.sh` e `tests/support/supabase_stubs.sql` (se precisar do schema `extensions`), testes em `tests/session_functions.sql`.

## 4. Lógica
1. O backend abre a transação e executa `set_config('app.clinic_id'|'app.role'|'app.user_id', valor, true)`.
2. Políticas e triggers chamam as funções; ausência ou valor inválido vira `NULL` e a política nega.
3. Valor de `app.user_id` que não é UUID derruba a transação (`22P02`), como `app.clinic_id`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `app.role = 'ADMIN'` ou `'dentist_admin'` (caixa diferente) | `NULL` (comparação exata) |
| `app.role` vazio | `NULL` |
| `set_config` fora de transação | Valor some ao fim do comando; `NULL` (falha fechada) |
| `app.user_id = 'abc'` | `22P02`, transação aborta |
| Bootstrap sem permissão para criar a extensão | Script falha; plano B documentado |
| Bootstrap repetido | Sem erro (idempotente) |

## 6. Segurança e desempenho
- Funções não são `SECURITY DEFINER` e não leem tabelas; sem `PUBLIC`.
- `STABLE`: avaliadas uma vez por consulta.
- `crm_app` sem `USAGE` em `extensions`: o planejador escolhe o índice trigram pelo operador, sem resolver nomes.
