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

| `U014__core_clinic_timezone.sql` | `V014` (Fase 2; remove `clinics.timezone` e o trigger de validação) |
| `U015__core_appointments.sql` | `V015` (apaga os agendamentos; reverter antes U016–U021) |
| `U016__core_business_hours.sql` | `V016` (apaga o horário comercial) |
| `U017__core_holidays.sql` | `V017` (apaga `holidays` e `clinic_holidays`) |
| `U018__core_holidays_seed_2026_2030.sql` | `V018` (remove os feriados de 2026-01-01 a 2030-12-31, inclusive os adicionados depois nesse intervalo) |
| `U019__core_notes_appointment_link.sql` | `V019` (apaga o vínculo `appointment_id` das notas; as notas permanecem) |
| `U020__core_appointments_audit.sql` | `V020` (remove o trigger e restaura `ck_audit_table`; falha se já houver linhas de auditoria de `appointments`) |
| `U021__core_appointments_anonymization.sql` | `V021` (restaura `core.anonymize_patient` da V013; anonimizações já feitas não voltam) |

| `U022__core_procedure_types.sql` | `V022` (Fase 3; apaga o catálogo de procedimentos; reverter antes U023 e U024) |
| `U023__core_appointments_procedure_fk.sql` | `V023` (remove a FK e o índice; a coluna e os valores permanecem; sempre segura) |
| `U024__core_budgets.sql` | `V024` (apaga orçamentos e itens; reverter antes U025, U026, U028 e U029) |
| `U025__core_budget_documents.sql` | `V025` (apaga os metadados dos PDFs; os objetos do Storage ficam órfãos) |
| `U026__core_installments.sql` | `V026` (apaga as parcelas; reverter antes U027) |
| `U027__core_payments.sql` | `V027` (apaga os pagamentos: só seguro antes de haver dados; o `DROP TABLE` é DDL do dono e não passa pelos triggers de somente inserção) |
| `U028__core_commercial_audit.sql` | `V028` (remove os 4 triggers e restaura `ck_audit_table`; **falha, de forma atômica, se já houver linhas de auditoria das tabelas novas**: o log é só de inserção, então se restaura do `pg_dump`) |
| `U029__core_commercial_anonymization.sql` | `V029` (restaura `core.anonymize_patient` da V021 e remove os grants e políticas do `crm_definer` da Fase 3; notas e PDFs já tratados não voltam) |

Ordem de reversão da Fase 3: `U029 → U028 → U027 → U026 → U025 → U024 → U023 → U022`. A cadeia completa é `U029 → U001`; `rollback_chain.sh` reverte e reaplica, e `migrations_phase3.sh` prova a cadeia da Fase 3 e a recusa da U028 com auditoria gravada.

Ordem de reversão da Fase 2: `U021 → U020 → U019 → U018 → U017 → U016 → U015 → U014` (a cadeia completa é `U021 → U001`; `rollback_chain.sh` reverte e reaplica).

Ordem de reversão da Fase 1: `U013 → U012 → U011 → U010 → U009 → U008 → U007`.

`bootstrap/20_extensions.sql` (Fase 1 e Fase 2) também não é migration: para desfazer num banco **descartável**, como administrador, `DROP EXTENSION btree_gist;` (só depois de reverter a V015, que depende dela) e `DROP EXTENSION pg_trgm;` (só depois de remover o índice `ix_patients_name_trgm`).

## Bootstrap (`bootstrap/00_roles_and_schema.sql`)

Não é uma migration. Para desfazer em um banco **descartável** (nunca em produção com dados):

```sql
DROP SCHEMA core CASCADE;
DROP OWNED BY crm_app;  DROP OWNED BY crm_backup;
DROP OWNED BY crm_definer;  DROP OWNED BY crm_owner;
DROP ROLE crm_app;  DROP ROLE crm_backup;  DROP ROLE crm_owner;  DROP ROLE crm_definer;
```

Para apenas **rotacionar senhas**, reexecute o bootstrap com novas variáveis.
