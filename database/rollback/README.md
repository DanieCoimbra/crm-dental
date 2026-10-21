# Rollback

Reversões são **manuais** e executadas por decisão humana: não há ferramenta de migração que reverta.

> Não há histórico de versões no banco: depois de reverter, reaplique com `bash database/scripts/apply_migrations.sh <versão_inicial>`. Ver `database/README.md`.

| Arquivo | Reverte |
|---|---|
| `U001__core_privileges.sql` | `V001__core_privileges.sql` |
| `U002__core_tenant_function.sql` | `V002__core_tenant_function.sql` |
| `U003_U004_U005__core_tables.sql` | `V003`, `V004` e `V005` (apaga os dados das tabelas) |
| `U006__core_bootstrap_functions.sql` | `V006__core_bootstrap_functions.sql` |
| `U007__core_session_functions.sql` | `V007__core_session_functions.sql` (Fase 1; reverter antes as V008–V013) |
| `U008__core_patients.sql` | `V008` (apaga os dados de `patients`; reverter antes V009–V013) |
| `U009__core_medical_histories.sql` | `V009` (apaga os dados) |
| `U010__core_clinical_notes.sql` | `V010` (apaga os dados) |
| `U011__core_patient_files.sql` | `V011` (apaga os metadados; objetos do Storage ficam órfãos) |
| `U012__core_audit_log.sql` | `V012` (apaga a trilha; reverter depois de U013 e antes de U008–U011) |
| `U013__core_patient_anonymization.sql` | `V013` (anonimizações já feitas não voltam) |

Ordem de reversão da Fase 1: `U013 → U012 → U011 → U010 → U009 → U008 → U007`.

`bootstrap/20_extensions.sql` (Fase 1) também não é migration: para desfazer num banco **descartável**, `DROP EXTENSION pg_trgm;` como administrador (só depois de remover o índice `ix_patients_name_trgm`).

## Bootstrap (`bootstrap/00_roles_and_schema.sql`)

Não é uma migration. Para desfazer em um banco **descartável** (nunca em produção com dados):

```sql
DROP SCHEMA core CASCADE;
DROP OWNED BY crm_app;  DROP OWNED BY crm_backup;
DROP OWNED BY crm_definer;  DROP OWNED BY crm_owner;
DROP ROLE crm_app;  DROP ROLE crm_backup;  DROP ROLE crm_owner;  DROP ROLE crm_definer;
```

Para apenas **rotacionar senhas**, reexecute o bootstrap com novas variáveis.
