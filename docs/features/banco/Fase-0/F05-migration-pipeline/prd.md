# PRD — F05 Pipeline de Migrations e CI/CD

> **Flyway abandonado (2026-10-21):** o projeto não usa mais o Flyway. As migrations são aplicadas por `database/scripts/apply_migrations.sh` (psql, uma transação por arquivo), **sem histórico nem checksum no banco**; `flyway.conf`, `flyway.sh`, `pg_uri_to_flyway_env.sh`, `core.flyway_schema_history` e os testes 23/24 (histórico e `validate`) deixaram de existir. Onde este documento cita Flyway, é o desenho original da Fase 0; vale o que está em `database/README.md`.

> **CI/CD adiado (2026-10-21):** os workflows de `.github/workflows/` foram removidos do repositório por decisão do projeto e voltam no fim. Onde este documento cita workflows, ambientes ou checks do GitHub, é o desenho previsto; no momento nada disso executa. Os scripts em `database/scripts` e `database/tests` seguem válidos e rodam localmente. Os workflows removidos estão no commit `c823a47`.

**Derivação:** PRD global do banco, US-001, US-007, FR-12 e decisões B3, B7, B8; spec global, seções 5, 6 e 12.
**Dependências externas:** F01 (bootstrap e papéis), F02 a F04 (as migrations V001–V006 e os testes). Consumida por todas as fases futuras do banco.

## 1. Introdução

- **Problema:** sem homologação e com banco gratuito, uma migration quebrada vai direto a produção; é preciso validá-la antes e aplicá-la de forma auditável.
- **Solução:** estrutura de repositório, configuração do Flyway e três workflows do GitHub Actions: validação em PR (banco descartável + testes), deploy após merge (com aprovação) e manutenção periódica (*keep-alive* + limpeza).
- **Metas:**
  1. 100% dos PRs que alteram `database/**` rodam migrations do zero e todos os testes antes de poderem ser mesclados.
  2. Deploy só após merge na `main` e aprovação manual do ambiente `production`.
  3. Editar uma migration já aplicada faz o `flyway validate` falhar.
  4. O projeto Supabase nunca fica mais de 3 dias sem atividade.

## 2. User stories

### US-001: Validar em PR
**Descrição:** Como responsável pelo banco, quero provar que as migrations funcionam antes do merge.

**Critérios de aceite:**
- [ ] `db-pr.yml` sobe `postgres:16`, cria papéis de stub do Supabase, roda o bootstrap, `flyway migrate` e `database/tests/*.sql` como `crm_app`/`crm_backup`.
- [ ] Qualquer teste falho reprova o PR.
- [ ] Sem segredos de produção no workflow.

### US-002: Implantar em produção
**Descrição:** Como responsável pelo banco, quero aplicar as migrations no Supabase de forma controlada.

**Critérios de aceite:**
- [ ] `db-deploy.yml` roda em `push` na `main` com mudanças em `database/migrations/**`.
- [ ] Executa `flyway validate` e depois `flyway migrate` como `crm_owner` pelo pooler *session*.
- [ ] Exige aprovação manual do ambiente `production`; execuções são serializadas (`concurrency`).

### US-003: Manutenção periódica
**Descrição:** Como operador, quero evitar a pausa do plano gratuito e limpar tokens vencidos.

**Critérios de aceite:**
- [ ] `db-maintenance.yml` roda a cada 3 dias e manualmente.
- [ ] Executa `SELECT 1` e `SELECT core.purge_expired_refresh_tokens()` como `crm_app`.

### US-004: Convenções de migration
**Descrição:** Como desenvolvedor, quero regras claras para criar migrations seguras.

**Critérios de aceite:**
- [ ] Nome `V<NNN>__descricao.sql`; migrations aplicadas são imutáveis.
- [ ] Cada migration tem nota de rollback em `database/rollback/U<NNN>__*.sql`.
- [ ] `cleanDisabled=true`; `outOfOrder=false`.

## 3. Requisitos funcionais

- **FR-1:** Estrutura `database/{bootstrap,migrations,rollback,tests}` e `database/flyway.conf`.
- **FR-2:** Flyway CLI em contêiner (`flyway/flyway`), `defaultSchema=core`, histórico em `core.flyway_schema_history`.
- **FR-3:** Workflows com `permissions: contents: read`, segredos só em *environments*, saída sem segredos (`::add-mask::`).
- **FR-4:** Os testes SQL usam `psql -v ON_ERROR_STOP=1` e asserções em blocos `DO`.
- **FR-5:** Executar o teste de catálogo da F02 em todo PR.
- **FR-6:** Um workflow de PR separado do backend aplica estes mesmos scripts para o `ddl-auto=validate` (teste 25).

## 4. Fora do escopo

- Homologação (decisão B8: só produção).
- Backup e restauração (F06).
- Migrações automáticas de rollback.
- Provisionamento do projeto Supabase.

## 5. Considerações técnicas e riscos

- Runners do GitHub são IPv4; a conexão direta do Supabase é IPv6 → usar pooler *session* (validar).
- Workflows agendados são desativados após 60 dias sem atividade no repositório.
- Sem homologação: manter migrations pequenas, aditivas e reversíveis.
- **Pergunta aberta:** nenhuma.
