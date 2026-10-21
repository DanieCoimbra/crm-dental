# Spec — F06 Backup e Restauração

> **Flyway abandonado (2026-10-21):** o projeto não usa mais o Flyway. As migrations são aplicadas por `database/scripts/apply_migrations.sh` (psql, uma transação por arquivo), **sem histórico nem checksum no banco**; `flyway.conf`, `flyway.sh`, `pg_uri_to_flyway_env.sh`, `core.flyway_schema_history` e os testes 23/24 (histórico e `validate`) deixaram de existir. Onde este documento cita Flyway, é o desenho original da Fase 0; vale o que está em `database/README.md`.

> **CI/CD adiado (2026-10-21):** os workflows de `.github/workflows/` foram removidos do repositório por decisão do projeto e voltam no fim. Onde este documento cita workflows, ambientes ou checks do GitHub, é o desenho previsto; no momento nada disso executa. Os scripts em `database/scripts` e `database/tests` seguem válidos e rodam localmente. Os workflows removidos estão no commit `c823a47`.

Complementa `docs/global/banco/spec.md` (seções 1.1, 3.3, 6 e 12).

## 1. Visão técnica
- **Feature:** F06 Backup e Restauração.
- **Stack:** GitHub Actions, `pg_dump`/`pg_restore`/`psql` (cliente PostgreSQL 16), `age`, `postgres:16` (contêiner de restauração).
- **Abordagem:** dois workflows; o papel `crm_backup` lê pelo mesmo mecanismo de política nomeada do `crm_definer`.

## 2. Dados e objetos

### Banco
- Papel `crm_backup` (F01) com `USAGE` em `core`.
- `GRANT SELECT` em cada tabela de negócio e `CREATE POLICY <prefixo>_backup_sel ON ... FOR SELECT TO crm_backup USING (true)` (ex.: `users_backup_sel`, `refresh_backup_sel`) (aplicado por F03 nas três tabelas e por toda tabela futura).
- Sem acesso à `flyway_schema_history` (não faz parte do dump necessário).

### Segredos e ambientes
| Ambiente | Secrets |
|---|---|
| `backup` (sem revisor: job agendado não espera aprovação) | `SUPABASE_BACKUP_URL`, `SUPABASE_BACKUP_PASSWORD`, `BACKUP_AGE_PUBLIC_KEY` |
| `restore-test` | `BACKUP_AGE_PRIVATE_KEY` |

## 3. Workflows

### `db-backup.yml` (diário, ex.: `cron "17 5 * * *"`, e `workflow_dispatch`)
1. Instalar cliente PostgreSQL 16 e `age`.
2. `scripts/backup.sh`: valida segredos antes de gravar, confere a política de leitura em toda tabela com RLS, registra contagens (mín|máx, antes/depois) e o catálogo, e roda `pg_dump -Fc --schema=core --exclude-table=core.flyway_schema_history --enable-row-security --no-owner --no-acl`.
3. Empacota (`tar`: `core.dump`, `counts.txt`, `schema.txt`) e criptografa: `age -r "$BACKUP_AGE_PUBLIC_KEY" -o crm-core-<timestamp>.dump.age`; o diretório temporário em claro é apagado.
4. `actions/upload-artifact` com `retention-days: 30`, `if-no-files-found: error`.
5. Verificar tamanho mínimo do arquivo (evita backup vazio silencioso).

### `db-restore-check.yml` (mensal + manual)
1. Baixar o último artefato `crm-core-*.dump.age` (API de artefatos do GitHub).
2. Descriptografar com a chave privada do ambiente `restore-test`.
3. Subir `postgres:16`; rodar `database/bootstrap` com senhas de teste (cria os papéis); criar `anon`, `authenticated`, `service_role` de stub.
4. `scripts/restore_check.sh` em dois modos: `full` (`pg_restore --no-owner --no-acl --role=crm_owner`, sem a entrada do schema) e `data` (runbook de desastre: `flyway migrate` → `pg_restore --data-only --disable-triggers`; a função de login funciona como `crm_app`).
5. Conferir: contagem de linhas por tabela > 0 e igual ao esperado do último backup (valor gravado como metadado do artefato), RLS ativa nas três tabelas, constraints presentes.

## 4. Lógica
- Com `--enable-row-security`, o `pg_dump` aplica as políticas ao papel que lê; a política `USING (true)` do `crm_backup` entrega todas as linhas.
- Sem essa opção o `pg_dump` desliga a RLS e o PostgreSQL falha por falta de privilégio; por isso é obrigatória.
- A contagem de linhas é registrada no backup (arquivo `counts.txt` empacotado junto, antes da criptografia) para a verificação mensal.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Tabela nova sem política de leitura do `crm_backup` | Dump incompleto ou falho; teste 26 reprova o PR antes |
| Dump vazio ou menor que o mínimo | Workflow falha |
| Pooler *session* não aceita `pg_dump` | Plano B: conexão direta via runner com IPv6 ou IPv4 add-on (a avaliar) |
| Chave `age` ausente | Workflow falha antes de gravar qualquer arquivo |
| Artefato expirado (>30 dias) | A restauração mensal usa o mais recente; se não houver, falha |
| Restauração com contagem divergente | Workflow reprova e abre alerta |
| Projeto pausado | Backup falha; reativar o projeto e rodar manualmente |
| Repositório inativo 60 dias | Agendamentos desativados; reativar |

## 6. Segurança e desempenho
- O dump nunca sai em claro do runner; arquivos temporários são removidos.
- `crm_backup` somente leitura, sem `BYPASSRLS`.
- Chave privada em ambiente separado com revisor; segredos mascarados.
- Backup do banco do plano gratuito (≈ 500 MB no máximo) conclui em poucos minutos.
