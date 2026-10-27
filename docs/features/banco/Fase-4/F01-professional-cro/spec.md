# Spec — F01 CRO do profissional (Fase 4)

## 1. Visão técnica
- **Feature:** CRO e UF em `core.users`.
- **Stack:** PostgreSQL, `plpgsql`, `CHECK`, trigger. Sem extensão.
- **Abordagem:** `V031__core_users_cro.sql`, como `crm_owner`, uma transação. Aditiva.

## 2. Objetos

```sql
ALTER TABLE core.users ADD COLUMN cro_number varchar(15);
ALTER TABLE core.users ADD COLUMN cro_state  varchar(2);
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_pair  CHECK ((cro_number IS NULL) = (cro_state IS NULL));
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_role  CHECK (cro_number IS NULL OR role = 'DENTIST_ADMIN');
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_fmt   CHECK (cro_number IS NULL OR cro_number ~ '^[0-9]{1,15}$');
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_state CHECK (cro_state IS NULL OR cro_state IN
  ('AC','AL','AP','AM','BA','CE','DF','ES','GO','MA','MT','MS','MG','PA','PB','PR','PE','PI','RJ','RN','RS','RO','RR','SC','SP','SE','TO'));
```

`core.guard_user_cro()`: `BEFORE UPDATE` por linha, invoker, `SET search_path = pg_catalog, pg_temp`, `REVOKE EXECUTE ... FROM PUBLIC`. Trigger `trg_users_guard_cro`. Sem `GRANT` novo.

## 3. Componentes
| Objeto | Tipo |
|---|---|
| `users.cro_number`, `users.cro_state` | colunas |
| `ck_users_cro_*` (4) | constraints |
| `core.guard_user_cro()` / `trg_users_guard_cro` | função / trigger |

## 4. Lógica
1. No `UPDATE`, se `NEW.cro_number IS DISTINCT FROM OLD.cro_number OR NEW.cro_state IS DISTINCT FROM OLD.cro_state`:
2. se `core.current_user_id() IS DISTINCT FROM NEW.id` → `RAISE EXCEPTION ... USING ERRCODE = '42501'`.
3. Senão `RETURN NEW`. A ordem entre os triggers de `users` (`set_updated_at`, `forbid_immutable`) não importa.

## 5. Erros e bordas
| Cenário | Resultado |
|---|---|
| UF fora da lista, número com letras ou 16 dígitos | `23514` |
| Só um dos campos | `23514` |
| Recepcionista com CRO | `23514` |
| Outro usuário altera o CRO | `42501` |
| `UPDATE` sem sessão (`app.user_id` ausente) tocando o CRO | `42501` |
| Dois dentistas com o mesmo CRO | permitido |

## 6. Segurança e desempenho
- Sem alteração de RLS (`users_sel/ins/upd` seguem por `clinic_id`). Sem índice novo (consulta por `id`).
- `ADD COLUMN` nulo sem default: sem reescrita da tabela. `ADD CONSTRAINT` valida poucas linhas.
- Rollback `U031`: remove trigger, função, `CHECK`s e colunas e **apaga o CRO já informado**. Destrutiva por convenção do projeto (como a `U025`): o dono não enxerga linhas de `users` sob RLS `FORCE`, então não consegue conferir se há CRO gravado; com dados, restaurar do `pg_dump`.
