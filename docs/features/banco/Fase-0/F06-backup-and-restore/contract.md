# Contract — F06 Backup e Restauração

> **Flyway abandonado (2026-10-21):** o projeto não usa mais o Flyway. As migrations são aplicadas por `database/scripts/apply_migrations.sh` (psql, uma transação por arquivo), **sem histórico nem checksum no banco**; `flyway.conf`, `flyway.sh`, `pg_uri_to_flyway_env.sh`, `core.flyway_schema_history` e os testes 23/24 (histórico e `validate`) deixaram de existir. Onde este documento cita Flyway, é o desenho original da Fase 0; vale o que está em `database/README.md`.

> **CI/CD adiado (2026-10-21):** os workflows de `.github/workflows/` foram removidos do repositório por decisão do projeto e voltam no fim. Onde este documento cita workflows, ambientes ou checks do GitHub, é o desenho previsto; no momento nada disso executa. Os scripts em `database/scripts` e `database/tests` seguem válidos e rodam localmente. Os workflows removidos estão no commit `c823a47`.

## 1. Resumo
- **Feature:** F06-backup-and-restore
- **Versão:** 1.0.0
- **Consumidores:** operação (portfólio), toda tabela nova de negócio (obrigação de política de leitura), workflows do GitHub.

## 2. Entradas

### 2.1 Secrets
| Ambiente | Secret | Regra |
|---|---|---|
| `backup` (sem revisor: job agendado não espera aprovação) | `SUPABASE_BACKUP_URL` | URI sem senha, usuário `crm_backup` ou `crm_backup.<ref>`, pooler *session*, TLS |
| `backup` | `SUPABASE_BACKUP_PASSWORD` | ≥ 24 caracteres |
| `backup` | `BACKUP_AGE_PUBLIC_KEY` | Chave pública `age` (`age1...`) |
| `restore-test` | `BACKUP_AGE_PRIVATE_KEY` | Chave privada correspondente |

### 2.2 Obrigação para tabelas novas
Toda tabela de negócio deve ter `GRANT SELECT ... TO crm_backup` e uma política `FOR SELECT TO crm_backup USING (true)` nomeada `<prefixo>_backup_sel` (ex.: `users_backup_sel`, `refresh_backup_sel`). O `backup.sh` recusa gerar o backup se alguma tabela com RLS não tiver essa política (o dump sairia incompleto, sem erro).

## 3. Saídas

| Item | Contrato |
|---|---|
| Artefato do backup | `crm-core-<UTC yyyymmddThhmmss>.dump.age`: um `tar` com `core.dump` (formato custom do `pg_dump`), `counts.txt` (por tabela, `tabela\|min\|max`, medido antes e depois do dump) e `schema.txt` (catálogo), criptografado com `age`; `retention-days: 30`; privado |
| Tamanho mínimo | `BACKUP_MIN_BYTES` (padrão 10240); abaixo disso o backup é descartado e o workflow falha |
| Frequência | Diária (backup) e mensal (verificação de restauração) |
| Resultado da verificação | Status verde/vermelho do workflow, com as contagens comparadas |

O dump contém tabelas, dados, índices, constraints e políticas RLS de `core`; **não** contém papéis, donos nem privilégios (`--no-owner --no-acl`) e **exclui** `core.flyway_schema_history` (`--exclude-table`), que o `crm_backup` não lê: o histórico é recriado pelo `flyway migrate`.

## 4. Regras e limites
- Retenção: 30 dias; sem armazenamento externo pago.
- Perda máxima aceita: 24 h (portfólio, dados fictícios).
- `crm_backup`: somente `SELECT` e `USAGE`; sem `BYPASSRLS`.
- Nunca armazenar o dump em claro; nunca registrar o conteúdo em log.
- A restauração exige executar o bootstrap (papéis) antes. Como o dump não traz GRANTs nem donos, a recuperação de desastre é: bootstrap → `flyway migrate` → `pg_restore --data-only --disable-triggers`.
- A verificação mensal roda dois modos (`RESTORE_MODES`): `full` (o dump é íntegro: restaura tudo, contagens e constraints por nome) e `data` (runbook de desastre, com catálogo idêntico e a função de login funcionando como `crm_app`). Roda em servidor separado e recusa hosts `supabase`/`pooler`.
- Chave privada `age` só no ambiente `restore-test`.

## 5. Eventos emitidos
Nenhum (status dos workflows).

## 6. Critérios de aceite (integração)
- [ ] Teste 26: como `crm_backup`, `pg_dump --enable-row-security` traz as linhas das clínicas A e B; tabela de negócio sem política de leitura do `crm_backup` reprova o teste.
- [ ] Teste 27: `crm_backup` não consegue `INSERT`, `UPDATE`, `DELETE` nem DDL.
- [ ] Teste 28: o dump restaura em PostgreSQL limpo (após o bootstrap) com as mesmas contagens.
- [ ] O artefato publicado é ilegível sem a chave privada.
- [ ] Backup vazio, menor que o mínimo ou sem chave faz o workflow falhar, e nenhum arquivo é gravado.
- [ ] A verificação mensal falha se qualquer contagem divergir.
