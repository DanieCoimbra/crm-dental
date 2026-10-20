# Spec — F03 Tabelas do Core

Complementa `docs/global/banco/spec.md` (seções 2.1 a 2.6 e 3). O dicionário completo está na seção 2.3 do spec global; aqui ficam a ordem das migrations e a lógica.

## 1. Visão técnica
- **Feature:** F03 Tabelas do Core.
- **Stack:** PostgreSQL DDL, Flyway (`V003`, `V004`, `V005`), RLS.
- **Abordagem:** uma migration por tabela, cada uma completa (tabela, índices, triggers, RLS, grants), em ordem de dependência.

## 2. Dados

### Migrations (como `crm_owner`)
| Arquivo | Conteúdo |
|---|---|
| `V003__core_clinics.sql` | `core.clinics`; trigger `set_updated_at`; `forbid_immutable_columns('id','created_at')`; RLS e políticas; grants |
| `V004__core_users.sql` | `core.users`; constraints e índices; triggers; RLS e políticas; grants |
| `V005__core_refresh_tokens.sql` | `core.refresh_tokens`; FK composta; índices; trigger de imutabilidade; RLS; grants |

### Constraints-chave (ilustrativo)
```sql
-- users
CONSTRAINT ck_users_email_lower   CHECK (email = lower(email)),
CONSTRAINT ck_users_role          CHECK (role IN ('DENTIST_ADMIN','RECEPTIONIST')),
CONSTRAINT ck_users_dentist_active CHECK (role <> 'DENTIST_ADMIN' OR active)
-- índices
CREATE UNIQUE INDEX ux_users_email        ON core.users (email);
CREATE UNIQUE INDEX ux_users_one_dentist  ON core.users (clinic_id) WHERE role = 'DENTIST_ADMIN';
CREATE UNIQUE INDEX ux_users_id_clinic    ON core.users (id, clinic_id);
CREATE INDEX        ix_users_clinic_name  ON core.users (clinic_id, name);
CREATE INDEX        ix_users_created_by   ON core.users (created_by) WHERE created_by IS NOT NULL;  -- índice na FK created_by
-- refresh_tokens
CONSTRAINT fk_refresh_user_clinic FOREIGN KEY (user_id, clinic_id)
  REFERENCES core.users (id, clinic_id) ON DELETE CASCADE,
CONSTRAINT ck_refresh_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
```

### Triggers por tabela
| Tabela | `set_updated_at` | `forbid_immutable_columns` |
|---|---|---|
| `clinics` | sim | `id`, `created_at` |
| `users` | sim | `id`, `clinic_id`, `role`, `created_at` |
| `refresh_tokens` | não (sem `updated_at`) | `id`, `clinic_id`, `user_id`, `family_id`, `token_hash`, `created_at` |

### Grants
`crm_app`: `SELECT, INSERT, UPDATE` em cada tabela. `crm_backup`: `SELECT`. Sem `DELETE` a ninguém (limpeza só pela função da F04). Sem `TRUNCATE`, `REFERENCES`, `TRIGGER`.

## 3. Componentes
As três migrations (`V003`, `V004`, `V005`), `rollback/U003_U004_U005__core_tables.sql` e os testes `tests/schema_dictionary.sql` (dicionário, tipos, índices e FKs), `tests/tenant_isolation.sql` (1, 2, 3, 6, 22 e backup), `tests/constraints.sql` (7 a 12, FKs e `ON DELETE`, como administrador para isolar constraints da RLS), `tests/permissions_tables.sql` (18, matriz de privilégios e políticas) e `tests/performance_indexes.sql` (planos com 100 mil usuários).

## 4. Lógica
- **Registro:** com `app.clinic_id = X`: `INSERT clinics (id=X)` → `INSERT users (clinic_id=X)` → `INSERT refresh_tokens`. As FKs são verificadas pelo PostgreSQL sem passar pela RLS.
- **Unicidade de e-mail:** o índice único é global e ignora a RLS: a violação (`23505`) existe mesmo quando o e-mail pertence a outra clínica.
- **Revogação de sessões:** `UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ?` ou `family_id = ?`, dentro do tenant.
- **Rotação atômica:** `UPDATE ... SET used_at = now() WHERE id = ? AND used_at IS NULL` (o backend usa o número de linhas afetadas).

## 5. Erros e bordas
| Cenário | SQLSTATE | Tratamento do backend |
|---|---|---|
| E-mail duplicado | `23505` (`ux_users_email`) | 409 |
| Segundo dentista | `23505` (`ux_users_one_dentist`) | 422 |
| E-mail com maiúsculas | `23514` | Bug do backend (normaliza antes) |
| Desativar o dentista | `23514` (`ck_users_dentist_active`) | 422 (o backend valida antes) |
| Alterar coluna imutável | `23514` (trigger) | 500 `internal` |
| INSERT fora do tenant | `42501` (RLS) | 500 `internal` |
| `token_hash` duplicado ou fora do formato | `23505` / `23514` | 500 `internal` |
| Token de usuário de outra clínica | `23503` | 500 `internal` |
| Edição concorrente | — (usa `version` do JPA) | 409 |

## 6. Segurança e desempenho
- RLS `FORCE` em todas; `crm_owner` também vê 0 linhas (teste 22).
- `password_hash` guarda apenas hash; nunca é retornado por funções.
- Login e refresh: `Index Scan` em índice único, ≤ 5 ms com 100 mil usuários (`EXPLAIN` no teste).
- Listagem de usuários atendida por `ix_users_clinic_name`.
- Volume esperado: dezenas de linhas por clínica; sem particionamento.
