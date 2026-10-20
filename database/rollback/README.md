# Rollback

Reversões são **manuais** e executadas por decisão humana: o Flyway Community não reverte migrations.

> Depois de reverter, apague do histórico do Flyway as versões revertidas (`DELETE FROM core.flyway_schema_history WHERE version::int >= N;`, como `crm_owner`); senão o Flyway continua achando que elas estão aplicadas. Ver `database/README.md`.

| Arquivo | Reverte |
|---|---|
| `U001__core_privileges.sql` | `V001__core_privileges.sql` |
| `U002__core_tenant_function.sql` | `V002__core_tenant_function.sql` |
| `U003_U004_U005__core_tables.sql` | `V003`, `V004` e `V005` (apaga os dados das tabelas) |

## Bootstrap (`bootstrap/00_roles_and_schema.sql`)

Não é uma migration. Para desfazer em um banco **descartável** (nunca em produção com dados):

```sql
DROP SCHEMA core CASCADE;
DROP OWNED BY crm_app;  DROP OWNED BY crm_backup;
DROP OWNED BY crm_definer;  DROP OWNED BY crm_owner;
DROP ROLE crm_app;  DROP ROLE crm_backup;  DROP ROLE crm_owner;  DROP ROLE crm_definer;
```

Para apenas **rotacionar senhas**, reexecute o bootstrap com novas variáveis.
