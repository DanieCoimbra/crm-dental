# Banco de dados (PostgreSQL / Supabase)

> **CI/CD adiado (2026-10-21):** os workflows de `.github/workflows/` foram removidos do repositório por decisão do projeto e voltam no fim. Onde este documento cita workflows, ambientes ou checks do GitHub, é o desenho previsto; no momento nada disso executa. Os scripts em `database/scripts` e `database/tests` seguem válidos e rodam localmente. Os workflows removidos estão no commit `c823a47`.

Tudo do banco mora aqui: papéis, schema `core`, migrations, testes e rollback. A API (backend) **nunca** altera o schema.

```
database/
├─ bootstrap/00_roles_and_schema.sql   papéis e schema core (1 vez, como postgres; idempotente)
├─ bootstrap/20_extensions.sql         pg_trgm (Fase 1) e btree_gist (Fase 2) no schema extensions (depois do 00; idempotente); a Fase 3 não adiciona extensão
├─ migrations/V<NNN>__<descricao>.sql  migrations aplicadas por psql (não edite depois de aplicadas)
├─ rollback/U<NNN>__*.sql              reversões MANUAIS (não há ferramenta de migração que reverta)
├─ scripts/                            scram_verifier.sh, apply_migrations.sh, backup.sh, restore_check.sh
├─ tests/                              testes SQL/shell + imagem do harness (Dockerfile)
└─ docker-compose.yml                  PostgreSQL descartável e o serviço `tests`
```

## Rodar os testes localmente

```bash
export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
docker compose -f database/docker-compose.yml --profile test run --rm --build tests   # ~40 s
docker compose -f database/docker-compose.yml down -v
```

É o mesmo comando do `db-pr.yml`. O harness cria os papéis de stub do Supabase, roda o bootstrap, aplica as
migrations com `scripts/apply_migrations.sh` (como `crm_owner`), roda todos os `tests/*.sql`
e por último a cadeia de rollback (destrutiva; só em banco descartável).

## Fase 3: comercial e financeiro (V022–V029)

Seis tabelas novas em `core` (`procedure_types`, `budgets`, `budget_items`, `budget_documents`, `installments`, `payments`) e a FK de `appointments.procedure_type_id`. **Nada manual no Supabase:** sem extensão nova; o PDF do orçamento vai no mesmo bucket privado dos exames (`CRM_STORAGE_*`, prefixo `budgets/`) e o banco guarda só os metadados.

| Migration | Conteúdo |
|---|---|
| `V022` / `V023` | catálogo de procedimentos; FK composta do agendamento |
| `V024` | orçamento e itens (número por clínica, total calculado pelo banco, snapshot de preço, congelamento, status e carimbos) |
| `V025` | metadados do PDF no Storage (revisões, `sha256`, `deleted_at`) |
| `V026` | parcelas (soma = total conferida no `COMMIT` por constraint trigger adiável) |
| `V027` | pagamentos append-only, estorno e status automático da parcela |
| `V028` / `V029` | auditoria das 4 tabelas; anonimização (`CR006`, notas zeradas, PDFs marcados) |

Regras que o backend precisa respeitar (detalhes em `docs/features/banco/Fase-3/*/contract.md`): transação em `READ COMMITTED`; **não enviar** `number`, `total_amount`, `sent_at`, `decided_at`, `description`, `unit_price` do item nem `revision` (o banco preenche; reler a linha depois); o desconto do orçamento entra por `UPDATE` depois dos itens; a falha `CR011` (parcelas não somam o total) só aparece no `COMMIT`.

Testes novos (todos rodam em `tests/run_all.sh`): `procedure_types`, `budgets`, `budget_items`, `budget_status`, `installments`, `payments`, `payments_append_only`, `budget_documents`, `money_invariants` (propriedade, 1.600 operações com semente fixa), `audit_commercial`, `anonymize_commercial`, `phase3_isolation`, `phase3_permissions`, `phase3_performance` (200 mil linhas financeiras), `commercial_concurrency.sh` (duas conexões) e `migrations_phase3.sh` (reversões e função de anonimização restaurada). A suíte completa leva cerca de 3 minutos.

## Fase 4: documentos e dashboard (V031–V035)

Uma tabela nova em `core` (`clinical_documents`), duas colunas em `users` e um índice. **Nada manual no Supabase:** sem extensão nova; o PDF do documento **não é guardado** (o backend o gera sob demanda a partir da linha).

| Migration | Conteúdo |
|---|---|
| `V031` | `users.cro_number` e `users.cro_state` (os dois ou nenhum, só `DENTIST_ADMIN`); só o próprio usuário altera o seu CRO (trigger `guard_user_cro`) |
| `V032` | `clinical_documents`: atestado, receita e encaminhamento imutáveis, texto cifrado pela aplicação, número por clínica e tipo atribuído pelo banco, anulação, RLS por clínica **e** por papel (só `DENTIST_ADMIN`) |
| `V033` | auditoria dos documentos (`INSERT`, `VOID`, `ANONYMIZE`) e `core.record_document_download(uuid)` (`DOWNLOAD`) |
| `V034` | `anonymize_patient` passa a zerar `content`, `rest_days`, `target_specialty` e `void_reason` dos documentos do paciente (a linha permanece; não bloqueia a anonimização) |
| `V035` | `ix_appt_completed_patient` (taxa de retorno do dashboard); sem view nem tabela de resumo |

Regras que o backend precisa respeitar (detalhes em `docs/features/banco/Fase-4/*/contract.md`): `READ COMMITTED`; **não enviar** `number`, `voided_*` nem `anonymized_at` no `INSERT` (reler a linha para obter o `number`); o `UPDATE` de anulação só toca `voided_at`, `voided_by`, `void_reason` e `version` (`@DynamicUpdate` ou demais colunas `updatable=false`); `content` é o Base64 do envelope AES-GCM (até 22.000 caracteres); `sha256` é calculado pelo backend sobre `v1|tipo|paciente|issued_on|rest_days|especialidade|content`; chamar `record_document_download` na mesma transação que gera o PDF (download **e** impressão); novo erro `CR012` (emissor sem CRO e UF). O corte do KPI "faturamento do mês" por papel é do backend (a recepção lê `budgets`).

Testes novos (todos rodam em `tests/run_all.sh`, `MIGRATIONS_EXPECTED=35`): `users_cro` (125, 126), `clinical_documents` (127, 128), `clinical_documents_immutable` (129), `documents_numbering` (130), `documents_concurrency.sh` (131, duas conexões), `audit_documents` (132–134), `anonymize_documents` (135, 136), `phase4_performance` (137, 100 mil documentos e agendamentos), `phase4_isolation` (138), `phase4_permissions` (139) e `migrations_phase4.sh` (140, reversões e função de anonimização restaurada). O teste 143 (`ddl-auto=validate` da Fase 4) depende do backend e está **pendente**.

## V030 (auditoria de segurança e desempenho de 2026-10-27)

`V030` tira o ruído de `audit_log` do recálculo do total por item em `budgets` (o `UPDATE` só é auditado quando o conteúdo, o status ou a `version` mudam), faz `refresh_budget_total` exigir `app.user_id` (`28000`) e leva a guarda `CR003` de paciente anonimizado para `budget_documents`. Reversão: `U030`. Testes 122, 123 e 124.

## Criar uma migration

1. Próximo número livre: `V007__descricao_em_snake_case.sql` (3 dígitos, dois sublinhados).
2. Uma mudança coesa por arquivo, transacional, **pequena e aditiva**. Não há homologação: o PR é o único ensaio.
3. Tabela de negócio: siga a receita da F02 (`clinic_id NOT NULL` indexado, RLS `ENABLE` + `FORCE`, políticas por
   operação com `USING`/`WITH CHECK`, política de `SELECT` do `crm_backup`, grants mínimos, triggers). O teste
   `catalog_rls_pattern.sql` reprova o PR se algo faltar.
4. Escreva `rollback/U007__*.sql` (ou uma nota) e, se criar objetos novos, os testes SQL correspondentes.
5. Abra o PR. O check `db-validate` precisa ficar verde.
6. **Nunca edite uma migration já aplicada**: sem histórico no banco, nada avisa da divergência entre o arquivo e o que rodou. Correção = nova versão.
7. Mudança destrutiva em passos: adicionar → migrar os dados → trocar o uso → remover (cada passo é um deploy).
8. `CREATE INDEX CONCURRENTLY` não roda em transação, e o `apply_migrations.sh` usa `--single-transaction`: aplique-o à mão com `psql` como `crm_owner`.

## Rollback manual

O rollback é decisão humana, executada como `crm_owner`, na ordem inversa (`U006` … `U001`). Não há histórico de versões no
banco: depois de reverter, reaplique a partir da versão desejada com `bash database/scripts/apply_migrations.sh <versão_inicial>`.

## Workflows (`.github/workflows/`)

| Workflow | Quando | O que faz | Ambiente |
|---|---|---|---|
| `db-pr.yml` (check `db-validate`) | todo PR (o job só roda o harness se o PR toca `database/**`; senão fica verde em segundos) | Harness completo em PostgreSQL descartável | nenhum, sem segredos |
| `db-deploy.yml` | push na `main` em `database/migrations/**` + manual | `scripts/apply_migrations.sh` no Supabase, como `crm_owner`, pelo pooler *session* | `production` (aprovação) |
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
3. **Recuperação de desastre** (o dump não traz papéis nem GRANTs): num PostgreSQL novo, rode o bootstrap → `scripts/apply_migrations.sh` como `crm_owner` → decifre com `age -d -i chave.txt -o pacote.tar crm-core-*.dump.age`, extraia o `core.dump` do tar → `pg_restore --data-only --disable-triggers --no-owner -d <banco> core.dump`. O `db-restore-check` ensaia exatamente esse caminho todo mês.
4. Ensaio local de ponta a ponta: `bash database/tests/backup_rehearsal.sh` (precisa de Docker).

## Operação manual (enquanto o CI/CD está adiado)

Sem os workflows, bootstrap e migrations rodam da sua máquina (precisa de `psql` e `openssl` 3). As URLs são as mesmas do pooler *session* descritas acima, sem senha; as senhas ficam em variáveis de ambiente e nunca em argumento.

**Bootstrap** (1 vez, como `postgres`; reexecutar rotaciona as senhas dos papéis):

```bash
export CRM_OWNER_PASSWORD=... CRM_APP_PASSWORD=... CRM_BACKUP_PASSWORD=...   # >= 24 caracteres e distintas
OWNER_SCRAM=$(SCRAM_PASSWORD="$CRM_OWNER_PASSWORD"   bash database/scripts/scram_verifier.sh)
APP_SCRAM=$(SCRAM_PASSWORD="$CRM_APP_PASSWORD"       bash database/scripts/scram_verifier.sh)
BACKUP_SCRAM=$(SCRAM_PASSWORD="$CRM_BACKUP_PASSWORD" bash database/scripts/scram_verifier.sh)

# ADMIN_URL: postgresql://postgres.<ref>@<host-do-pooler>:5432/postgres?sslmode=require  (PGPASSWORD com a senha do postgres)
psql "$ADMIN_URL" -X -q -v ON_ERROR_STOP=1 \
  -v crm_owner_scram="$OWNER_SCRAM" -v crm_app_scram="$APP_SCRAM" -v crm_backup_scram="$BACKUP_SCRAM" \
  -f database/bootstrap/00_roles_and_schema.sql
```

**Extensões** (Fase 1, 1 vez, como `postgres`, depois do bootstrap; instala `pg_trgm` em `extensions` e dá `USAGE` só a `crm_owner`):

```bash
psql "$ADMIN_URL" -X -q -v ON_ERROR_STOP=1 -f database/bootstrap/20_extensions.sql
```

Se a extensão não puder ser criada no projeto, o script falha apontando o plano B (só busca por prefixo; remover o índice `ix_patients_name_trgm` da F02).

**Migrations** (o que o `db-deploy` fazia, como `crm_owner`):

```bash
export OWNER_URL='postgresql://crm_owner.<ref>@<host>:5432/postgres?sslmode=require'   # sem senha; TLS obrigatório
export PGPASSWORD="$CRM_OWNER_PASSWORD"
bash database/scripts/apply_migrations.sh         # todas, na ordem (base vazia)
bash database/scripts/apply_migrations.sh 14      # só da V014 em diante (base já migrada)
```

Antes de cada deploy, valide localmente com o harness (`docker compose --profile test`) e ensaie com `bash database/tests/deploy_rehearsal.sh`. O backup e o ensaio de restauração têm scripts próprios (`scripts/backup.sh`, `tests/backup_rehearsal.sh`).

## Contrato de sessão (backend → banco)

A cada transação autenticada o backend define, **na mesma transação**, com `set_config(nome, valor, true)`:

| Variável | Valor | Lida por |
|---|---|---|
| `app.clinic_id` | uuid da clínica | `core.current_clinic_id()` (todas as políticas) |
| `app.role` | `DENTIST_ADMIN` ou `RECEPTIONIST` (exato, maiúsculas) | `core.current_app_role()` (tabelas clínicas, Fase 1) |
| `app.user_id` | uuid do usuário autenticado | `core.current_user_id()` (autoria e auditoria, Fase 1) |

Ausente, vazio ou fora da lista → `NULL` → as políticas negam (falha fechada). `app.clinic_id` e `app.user_id` que não sejam UUID abortam a transação (`22P02`). O prazo de guarda do prontuário (20 anos) vive em `core.clinical_retention_period()`.

## Fase 1 (núcleo clínico): operação

Migrations `V007`–`V013` (funções de sessão, `patients`, `medical_histories`, `clinical_notes`, `patient_files`, `audit_log`, anonimização) e `bootstrap/20_extensions.sql`. Reversão manual: `U013 → U012 → U011 → U010 → U009 → U008 → U007` (ver `rollback/README.md`; as tabelas só se revertem sem dados).

**Testes da fase** (rodam no `tests/run_all.sh`; evidência de cada número em `docs/global/banco/Fase-1/spec.md`, seção 9): `session_functions.sql` (35, 53), `patients_constraints.sql` (29–31, 34–39, 42, 52), `clinical_roles.sql` (29–33, 35, 36, 52), `clinical_constraints.sql` (38, 40–42), `audit_log.sql` (43–45), `anonymization.sql` (46–51, 55), `performance_search.sql` (55), `phase1_privileges.sql` (17, 18, 51–54) e a ampliação de `schema_dictionary.sql`, `catalog_rls_pattern.sql`, `backup_*` e `rollback_chain.sh` (54, 56, 58). O teste 57 (`ddl-auto=validate` do backend) roda na CI do backend contra estes scripts.

**Validado no Supabase real (2026-10-21):** `bootstrap/20_extensions.sql` (`pg_trgm` em `extensions`, `USAGE` ao `crm_owner`) e V001–V013 aplicadas como `crm_owner` (inclui o `OWNER TO crm_definer` das V012/V013), com os 6 triggers de auditoria presentes. Seguem pendentes: `pg_dump --enable-row-security` pelo pooler e o checklist do bucket abaixo.

### Anonimização de paciente (LGPD)

`SELECT core.anonymize_patient('<uuid>')` com `app.role = DENTIST_ADMIN` (backend). Só vale para paciente **inativo** e **depois de 20 anos do último registro clínico** (sem prontuário, vale na hora). O backend deve: (1) listar as `storage_key` do paciente **antes**; (2) chamar a função; (3) **apagar os objetos no Storage depois do sucesso**. Falha no Storage deixa linhas de `patient_files` com `deleted_at` e `original_filename = 'anonimizado'` para conciliação. Códigos: `CR001` ativo, `CR002` retenção não vencida (`DETAIL` traz a data), `CR003` já anonimizado, `42501` papel, `28000` sessão, `P0002` inexistente. **Janela de backup:** os dumps de até 30 dias ainda contêm os dados originais; isso consta da política de privacidade. O prazo de 20 anos (Lei 13.787/2018) é parâmetro (`core.clinical_retention_period()`) e deve ser confirmado com assessoria jurídica antes de dados reais.

### Supabase Storage dos exames: checklist manual do bucket

Nenhum teste automatizado cobre o Storage real (upload, URL assinada e privacidade do bucket são do backend e do painel). Antes de aprovar a fase, e a cada troca de projeto, de chave ou de configuração, execute e registre aqui (data, responsável, resultado). `curl` sem a chave de serviço nas máquinas de teste.

| # | Verificação | Como conferir | Esperado | Data / responsável / resultado |
|---|---|---|---|---|
| B1 | Bucket privado | Painel: Storage → bucket → *Public bucket* desligado | Desligado | pendente |
| B2 | Acesso anônimo negado | `curl -i <url>/storage/v1/object/public/<bucket>/<qualquer-chave>` | `400`/`404`, nunca `200` | pendente |
| B3 | Listagem anônima negada | `curl -i -H "apikey: <anon key>" -H "Content-Type: application/json" -d '{"prefix":""}' <url>/storage/v1/object/list/<bucket>` | `401`/`403` ou lista vazia | pendente |
| B4 | Sem políticas de acesso | Painel: Storage → Policies → `storage.objects` | Nenhuma política para `anon` nem `authenticated` neste bucket | pendente |
| B5 | Limite de tamanho | Painel: configurações do bucket → *File size limit* | 10 MB | pendente |
| B6 | Tipos permitidos | Painel: *Allowed MIME types* | `image/jpeg`, `image/png`, `image/webp`, `application/pdf` (igual à `CHECK` de `patient_files.content_type`) | pendente |
| B7 | Upload fora do limite/tipo recusado | Upload de arquivo > 10 MB e de `text/plain` pelo backend (ambiente de teste) | Recusado pelo Storage | pendente do backend |
| B8 | URL assinada expira | Gerar URL com validade curta (ex.: 60 s) e abrir antes e depois | Abre antes; falha depois | pendente do backend |
| B9 | Chave de serviço só no backend | Buscar `CRM_STORAGE_SERVICE_KEY` e a chave `service_role` no repositório, no front e nos logs de deploy | Nenhuma ocorrência | pendente |
| B10 | Estrutura da chave | Objeto de teste em `<clinic_id>/<patient_id>/<uuid>` | Aceito; o registro em `patient_files` passa na `CHECK` de `storage_key` | pendente do backend |
| B11 | Data API desligada | Painel: *Settings → Data API*; `core` fora dos schemas expostos | Desligada | pendente |
| B12 | Objeto removido na anonimização | Após `anonymize_patient` em paciente de teste, o backend apaga os objetos | Objetos ausentes; registros com `deleted_at` e `original_filename = 'anonimizado'` | pendente do backend |

B1–B6, B9 e B11 são configuração (uma vez por projeto) e bloqueiam a aprovação da fase; B7, B8, B10 e B12 dependem do backend da Fase 1. Enquanto o CI/CD estiver adiado, **nada revalida a privacidade do bucket automaticamente**.

### Desempenho sob RLS (nota de operação)

As políticas chamam as funções de sessão como `(SELECT core.current_app_role())` para que o PostgreSQL as avalie uma vez por consulta. Busca por trecho/prefixo de nome com `LIKE` roda como filtro dentro da clínica (≈ 5 ms com 10 mil pacientes): `LIKE` não é `LEAKPROOF` e, sob RLS, não usa os índices trigram/prefixo. Ver `docs/features/banco/Fase-1/F02-patients/spec.md`, seção 4.1.

## Fase 2 (Agenda): operação

Migrations `V014`–`V021`: `clinics.timezone`, `appointments` (conflito de horário por `EXCLUDE`), `business_hours`, `holidays` + `clinic_holidays` (seed nacional 2026–2030), `clinical_notes.appointment_id`, auditoria de `appointments` e anonimização com `CR004`. Reversão manual: `U021 → U020 → … → U014` (ver `rollback/README.md`; só antes de haver dados, depois restaure do `pg_dump`).

**Antes da V015 (passo manual no Supabase, como `postgres`):** reexecute `bootstrap/20_extensions.sql` (idempotente), que agora também cria `btree_gist` em `extensions`. Sem ela a V015 aborta com a mensagem `btree_gist ausente` e não deixa nada pela metade; **não há plano B** (sem a extensão não há garantia de conflito de agenda no banco).

```bash
psql "$ADMIN_URL" -v ON_ERROR_STOP=1 -f database/bootstrap/20_extensions.sql
bash database/scripts/apply_migrations.sh 14        # V014 em diante, como crm_owner
```

**Testes (59–85):** rodam no mesmo `run_all.sh` (`MIGRATIONS_EXPECTED=21`). `appointments_concurrency.sh` (63, duas conexões como `crm_app`), `migrations_phase2.sh` (82 e 83, banco descartável próprio) e `backup_restore.sh` (85, as 4 tabelas novas no dump) são scripts de shell; o resto é `tests/*.sql`. O teste 84 (`ddl-auto=validate`) depende do backend da Fase 2 e está **pendente**.

**Restauração por recuperação de desastre (modo `data`):** a V018 semeia `core.holidays`; o dump traz as mesmas linhas. Antes do `pg_restore --data-only`, execute `TRUNCATE core.holidays` (o `restore_check.sh` já faz isso).

**Pré-checagem de conflito no backend (nota de desempenho):** sob RLS o operador `&&` de `tstzrange` não é `LEAKPROOF` e não vira condição de índice para o `crm_app` (varre a clínica toda, ~15 ms com 20 mil agendamentos). Use a mesma forma da agenda: `clinic_id = ? AND status <> 'CANCELED' AND status <> 'NO_SHOW' AND starts_at >= :ini - interval '12 hours' AND starts_at < :fim AND ends_at > :ini` (Index Scan em `ix_appt_clinic_start`, < 0,1 ms). A autoridade do conflito continua sendo a constraint `ex_appt_no_overlap` (23P01).

**A validar no Supabase real:** itens 1 a 7 da seção 10 de `docs/global/banco/Fase-2/spec.md` (em especial `btree_gist` e o `CREATE TRIGGER ... write_audit()` da V020 pelo `crm_owner`).

## Notas

- O Flyway deixou de ser usado (2026-10-21). Não há histórico de versões nem checksum no banco: quem aplica escolhe a versão inicial. Um banco que já foi migrado pelo Flyway ainda tem `core.flyway_schema_history`; descarte-a como `crm_owner`: `DROP TABLE core.flyway_schema_history;`.
- O Supabase gratuito pausa após ~7 dias sem atividade; o `db-maintenance` roda a cada 3 dias. O GitHub desativa workflows agendados
  depois de 60 dias sem atividade no repositório: reative em *Actions*.
- Se o runner do GitHub não alcançar o pooler (IPv4/IPv6), rode o `db-deploy` manualmente de uma máquina com acesso.
