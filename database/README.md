# Banco de dados (PostgreSQL / Supabase)

> **CI/CD adiado (2026-10-21):** os workflows de `.github/workflows/` foram removidos do repositório por decisão do projeto e voltam no fim. Onde este documento cita workflows, ambientes ou checks do GitHub, é o desenho previsto; no momento nada disso executa. Os scripts em `database/scripts` e `database/tests` seguem válidos e rodam localmente. Os workflows removidos estão no commit `c823a47`.

Tudo do banco mora aqui: papéis, schema `core`, migrations, testes e rollback. A API (backend) **nunca** altera o schema.

```
database/
├─ bootstrap/00_roles_and_schema.sql   papéis e schema core (1 vez, como postgres; idempotente)
├─ migrations/V<NNN>__<descricao>.sql  migrations do Flyway (imutáveis depois de aplicadas)
├─ rollback/U<NNN>__*.sql              reversões MANUAIS (o Flyway Community não reverte)
├─ scripts/                            scram_verifier.sh, flyway.sh, pg_uri_to_flyway_env.sh
├─ tests/                              testes SQL/shell + imagem do harness (Dockerfile)
├─ flyway.conf                         configuração do Flyway, sem credenciais
└─ docker-compose.yml                  PostgreSQL descartável e o serviço `tests`
```

## Rodar os testes localmente

```bash
export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
docker compose -f database/docker-compose.yml --profile test run --rm --build tests   # ~40 s
docker compose -f database/docker-compose.yml down -v
```

É o mesmo comando do `db-pr.yml`. O harness cria os papéis de stub do Supabase, roda o bootstrap, aplica as
migrations com o **Flyway real** (como `crm_owner`), confere `validate` e os casos negativos, roda todos os `tests/*.sql`
e por último a cadeia de rollback (destrutiva; só em banco descartável).

## Criar uma migration

1. Próximo número livre: `V007__descricao_em_snake_case.sql` (3 dígitos, dois sublinhados).
2. Uma mudança coesa por arquivo, transacional, **pequena e aditiva**. Não há homologação: o PR é o único ensaio.
3. Tabela de negócio: siga a receita da F02 (`clinic_id NOT NULL` indexado, RLS `ENABLE` + `FORCE`, políticas por
   operação com `USING`/`WITH CHECK`, política de `SELECT` do `crm_backup`, grants mínimos, triggers). O teste
   `catalog_rls_pattern.sql` reprova o PR se algo faltar.
4. Escreva `rollback/U007__*.sql` (ou uma nota) e, se criar objetos novos, os testes SQL correspondentes.
5. Abra o PR. O check `db-validate` precisa ficar verde.
6. **Nunca edite uma migration já aplicada**: o `flyway validate` falha (checksum). Correção = nova versão.
7. Mudança destrutiva em passos: adicionar → migrar os dados → trocar o uso → remover (cada passo é um deploy).
8. `CREATE INDEX CONCURRENTLY` não roda em transação: coloque-o em migration própria com
   `-- flyway:executeInTransaction=false` no topo.

## Rollback manual

O rollback é decisão humana, executada como `crm_owner`, na ordem inversa (`U006` … `U001`). Depois de reverter, corrija o
histórico do Flyway apagando as linhas das versões revertidas, senão o Flyway acha que elas continuam aplicadas:

```sql
DELETE FROM core.flyway_schema_history WHERE version::int >= 6;   -- ajuste para as versões revertidas
```

## Workflows (`.github/workflows/`)

| Workflow | Quando | O que faz | Ambiente |
|---|---|---|---|
| `db-pr.yml` (check `db-validate`) | todo PR (o job só roda o harness se o PR toca `database/**`; senão fica verde em segundos) | Harness completo em PostgreSQL descartável | nenhum, sem segredos |
| `db-deploy.yml` | push na `main` em `database/migrations/**` + manual | `flyway info` → `validate` → `migrate` → `info` no Supabase, como `crm_owner`, pelo pooler *session* | `production` (aprovação) |
| `db-maintenance.yml` | a cada 3 dias + manual | `SELECT 1` (keep-alive) e `core.purge_expired_refresh_tokens()` como `crm_app` | `maintenance` (sem aprovação) |
| `db-backup.yml` | diário 05:17 UTC + manual | `pg_dump` como `crm_backup` → `age` (chave pública) → artefato privado, 30 dias | `backup` (sem revisor) |
| `db-restore-check.yml` | mensal (dia 1) + manual | Baixa o último backup, decifra, restaura em PostgreSQL descartável e confere contagens e catálogo | `restore-test` (revisor) |
| `db-bootstrap.yml` | manual | Bootstrap de papéis e schema como `postgres` (1 vez; reexecutar rotaciona senhas) | `production` (aprovação) |

### Ambientes e segredos do GitHub

| Ambiente | Revisor obrigatório | Segredos |
|---|---|---|
| `production` | **sim** | `SUPABASE_OWNER_URL`, `SUPABASE_OWNER_PASSWORD`, `SUPABASE_ADMIN_URL`, `CRM_OWNER_PASSWORD`, `CRM_APP_PASSWORD`, `CRM_BACKUP_PASSWORD` |
| `maintenance` | não (job agendado não pode esperar aprovação) | `SUPABASE_APP_URL`, `SUPABASE_APP_PASSWORD` |
| `backup` | não (idem) | `SUPABASE_BACKUP_URL` (usuário `crm_backup[.<ref>]`), `SUPABASE_BACKUP_PASSWORD`, `BACKUP_AGE_PUBLIC_KEY` |
| `restore-test` | **sim** (a chave privada decifra todos os backups) | `BACKUP_AGE_PRIVATE_KEY` |

As URLs são **URIs sem senha**: `postgresql://crm_owner.<project-ref>@<host-do-pooler>:5432/postgres?sslmode=require`.
A senha vai no segredo `*_PASSWORD`. TLS (`sslmode=require` ou mais estrito) é obrigatório; sem ele os workflows falham.

### Configuração única no GitHub (não é versionável)

1. *Settings → Environments*: criar `production` (marcar **Required reviewers** e, em **Deployment branches and tags**, limitar a `main`) e `maintenance` (sem revisor) e cadastrar os segredos. O `db-deploy` também recusa rodar fora da `main` e recusa uma URL cujo usuário não seja `crm_owner` (ou `crm_owner.<ref>`).
2. *Settings → Branches → Branch protection rule* para `main`: **Require status checks to pass** com o check
   `db-validate`, e **Require a pull request before merging**. O `db-pr.yml` roda em todo PR justamente para esse check obrigatório nunca ficar "esperando" em PRs que não tocam o banco. (Se também exigir `backend-verify`, faça o mesmo no `backend-ci.yml`: ele filtra por `paths`.)
3. Rodar `db-bootstrap` uma vez, depois mesclar uma migration para disparar o primeiro `db-deploy`.

## Backup e restauração

1. Gere o par de chaves uma vez, fora do repositório: `age-keygen -o chave.txt`. A linha `# public key: age1...` vai para `BACKUP_AGE_PUBLIC_KEY` (ambiente `backup`); a linha `AGE-SECRET-KEY-...` vai **só** para `BACKUP_AGE_PRIVATE_KEY` (ambiente `restore-test`). Guarde uma cópia da privada num cofre: sem ela os backups são ilegíveis.
2. O `db-backup` falha (sem gravar arquivo) se faltar segredo, a chave não for `age1...`, a senha tiver < 24 caracteres, a URI trouxer senha ou não exigir TLS, o `pg_dump` for mais antigo que o servidor (ajuste `PG_MAJOR` no workflow), alguma tabela com RLS não tiver política `*_backup_sel` ou o arquivo ficar abaixo de `BACKUP_MIN_BYTES`.
3. **Recuperação de desastre** (o dump não traz papéis nem GRANTs): num PostgreSQL novo, rode o bootstrap → `scripts/flyway.sh migrate` como `crm_owner` → decifre com `age -d -i chave.txt -o pacote.tar crm-core-*.dump.age`, extraia o `core.dump` do tar → `pg_restore --data-only --disable-triggers --no-owner -d <banco> core.dump`. O `db-restore-check` ensaia exatamente esse caminho todo mês.
4. Ensaio local de ponta a ponta: `bash database/tests/backup_rehearsal.sh` (precisa de Docker).

## Notas

- O Flyway guarda o histórico em `core.flyway_schema_history` (dono `crm_owner`, sem nenhum `GRANT`). O `db-deploy` valida com
  `-ignoreMigrationPatterns=*:pending,*:future`: antes do `migrate` há migrations pendentes por definição, e sem esse padrão o
  `validate` falharia sempre. Checksum alterado, nome inválido e versão fora de ordem continuam reprovando.
- O Supabase gratuito pausa após ~7 dias sem atividade; o `db-maintenance` roda a cada 3 dias. O GitHub desativa workflows agendados
  depois de 60 dias sem atividade no repositório: reative em *Actions*.
- Se o runner do GitHub não alcançar o pooler (IPv4/IPv6), rode o `db-deploy` manualmente de uma máquina com acesso.
