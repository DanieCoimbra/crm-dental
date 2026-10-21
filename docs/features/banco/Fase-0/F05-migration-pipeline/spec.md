# Spec — F05 Pipeline de Migrations e CI/CD

> **Flyway abandonado (2026-10-21):** o projeto não usa mais o Flyway. As migrations são aplicadas por `database/scripts/apply_migrations.sh` (psql, uma transação por arquivo), **sem histórico nem checksum no banco**; `flyway.conf`, `flyway.sh`, `pg_uri_to_flyway_env.sh`, `core.flyway_schema_history` e os testes 23/24 (histórico e `validate`) deixaram de existir. Onde este documento cita Flyway, é o desenho original da Fase 0; vale o que está em `database/README.md`.

> **CI/CD adiado (2026-10-21):** os workflows de `.github/workflows/` foram removidos do repositório por decisão do projeto e voltam no fim. Onde este documento cita workflows, ambientes ou checks do GitHub, é o desenho previsto; no momento nada disso executa. Os scripts em `database/scripts` e `database/tests` seguem válidos e rodam localmente. Os workflows removidos estão no commit `c823a47`.

Complementa `docs/global/banco/spec.md` (seções 5, 6 e 12).

## 1. Visão técnica
- **Feature:** F05 Pipeline de Migrations e CI/CD.
- **Stack:** GitHub Actions, Flyway CLI (imagem `flyway/flyway`), `postgres:16` (serviço de teste), `psql`.
- **Abordagem:** três workflows e uma estrutura de pastas versionada; testes em SQL puro.

## 2. Estrutura e configuração

```
database/
├─ bootstrap/00_roles_and_schema.sql     (F01)
├─ migrations/V001..V006                  (F01–F04)
├─ rollback/U001..U006                    (notas ou SQL manual)
├─ tests/                                 (testes numerados da spec global, seção 12)
└─ flyway.conf
.github/workflows/ db-bootstrap.yml  db-pr.yml  db-deploy.yml  db-maintenance.yml  (+ db-backup.yml, db-restore-check.yml da F06)
```

`flyway.conf` (sem credenciais): `locations=filesystem:database/migrations`, `defaultSchema=core`, `schemas=core`, `table=flyway_schema_history`, `cleanDisabled=true`, `outOfOrder=false`, `validateMigrationNaming=true`, `baselineOnMigrate=false`. URL, usuário e senha vêm de variáveis do ambiente.

Mapa de testes por arquivo: `roles_and_privileges.sql` (17, 19–21), `tenant_basics.sql` (4, 5), `tenant_isolation.sql` (1–3, 6), `schema_dictionary.sql` (dicionário da F03), `constraints.sql` (7–12), `permissions_tables.sql` (18), `performance_indexes.sql` (planos de índice), `tenant_isolation.sql` também cobre o 22, `bootstrap_functions.sql` (13–16), `catalog_rls_pattern.sql` (receita da F02), `migrations_integrity.sql` (19 e 23: histórico real do Flyway íntegro e invisível ao `crm_app`), `flyway_checks.sh` (23 e 24 com o Flyway real, mais as convenções), `backup_role.sql` (26, 27).

## 3. Workflows

| Workflow | Gatilho | Passos |
|---|---|---|
| `db-pr.yml` | `pull_request` (todo PR; sem filtro `paths`, porque `db-validate` é check obrigatório e um check obrigatório que não é disparado trava o merge; o job detecta se o PR toca `database/**` e, se não, termina verde sem rodar o harness) | Executa o harness `docker compose --profile test run tests` (imagem `database/tests/Dockerfile`: psql + Flyway) contra um `postgres:16` descartável: 1. stubs `anon`, `authenticated`, `service_role`; 2. bootstrap com senhas geradas na hora; 3. `flyway migrate`; 4. `flyway_checks.sh` (validate, checksum alterado, nome inválido, fora de ordem, `clean` recusado, migration que falha, conversor de URI); 5. `tests/*.sql`; 6. cadeia de rollback. Job `db-validate` |
| `db-deploy.yml` | `push` na `main` com `paths: database/migrations/**` | Ambiente `production` (aprovação); `concurrency: db-deploy`; roda só na `main` (`if: github.ref == refs/heads/main`) e recusa URL com usuário diferente de `crm_owner`/`crm_owner.<ref>`; `flyway info` → `validate` → `migrate` → `info`; pooler *session*, usuário `crm_owner.<ref>`. O `validate` pré-deploy usa `-ignoreMigrationPatterns=*:pending,*:future` (antes do `migrate` há migrations pendentes por definição; sem isso o primeiro deploy sempre falharia) |
| `db-maintenance.yml` | `schedule: cron "0 6 */3 * *"` + `workflow_dispatch` | Ambiente `maintenance` (sem revisor; só os segredos do `crm_app`); `psql` como `crm_app`: `SELECT 1;` e `SELECT core.purge_expired_refresh_tokens();` |

Segredos: `SUPABASE_OWNER_URL`, `SUPABASE_OWNER_PASSWORD` (deploy, ambiente `production`); `SUPABASE_APP_URL`, `SUPABASE_APP_PASSWORD` (manutenção, ambiente `maintenance`). Nenhum no PR. As URLs são URIs sem senha; `database/scripts/pg_uri_to_flyway_env.sh` as converte para o JDBC do Flyway e recusa senha embutida, ausência de TLS e caracteres de injeção.

## 4. Lógica

- **PR:** sem acesso à produção; falha em qualquer passo reprova o PR (proteção de branch exige o check).
- **Deploy:** só roda se o PR correspondente passou. `validate` antes de `migrate` impede reaplicar scripts editados.
- **Convenção de migration:** uma mudança coesa por arquivo, transacional; `CREATE INDEX CONCURRENTLY` fora de transação (exige `executeInTransaction=false` por script); alterações destrutivas em passos (adicionar → migrar → trocar → remover).
- **Rollback:** nota/SQL manual em `rollback/`, executado por decisão humana; o Flyway Community não reverte.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Migration falha em produção | Transação desfaz; workflow falha; sem alterar a API |
| Migration editada após aplicada | `validate` falha; deploy interrompido |
| Dois merges seguidos | `concurrency` serializa |
| Runner sem rota para o banco | Usar pooler *session*; se falhar, rodar via `workflow_dispatch` local alternativa |
| Projeto Supabase pausado | Reativar pelo painel; só então rodar o deploy |
| Repositório inativo 60 dias | Workflows agendados desativados; reativar manualmente |
| Segredo ausente | Workflow falha cedo, sem imprimir valores |
| Teste SQL com asserção falsa | `RAISE EXCEPTION` + `ON_ERROR_STOP` reprova |

## 6. Segurança e desempenho
- `permissions: contents: read`; actions fixadas por versão/hash.
- Ambiente `production` com revisor obrigatório (deploy e bootstrap); `maintenance` sem revisor e com segredos de menor privilégio; segredos mascarados; sem `set -x`.
- PR roda em ≤ 5 minutos (banco pequeno).
- Deploy não bloqueia a API: migrations aditivas e rápidas.
