# PRD — F06 Backup e Restauração

> **Flyway abandonado (2026-10-21):** o projeto não usa mais o Flyway. As migrations são aplicadas por `database/scripts/apply_migrations.sh` (psql, uma transação por arquivo), **sem histórico nem checksum no banco**; `flyway.conf`, `flyway.sh`, `pg_uri_to_flyway_env.sh`, `core.flyway_schema_history` e os testes 23/24 (histórico e `validate`) deixaram de existir. Onde este documento cita Flyway, é o desenho original da Fase 0; vale o que está em `database/README.md`.

> **CI/CD adiado (2026-10-21):** os workflows de `.github/workflows/` foram removidos do repositório por decisão do projeto e voltam no fim. Onde este documento cita workflows, ambientes ou checks do GitHub, é o desenho previsto; no momento nada disso executa. Os scripts em `database/scripts` e `database/tests` seguem válidos e rodam localmente. Os workflows removidos estão no commit `c823a47`.

**Derivação:** PRD global do banco, meta 6, FR-12 e a nota de backup dos padrões adotados; risco 8 (plano gratuito sem PITR); spec global, seções 1.1, 3.3, 6 e 12 (testes 26 a 28).
**Dependências externas:** F01 (papel `crm_backup`), F02 (receita de política de leitura do backup), F03 (tabelas), F05 (estrutura de workflows e ambientes).

## 1. Introdução

- **Problema:** o plano gratuito do Supabase não oferece PITR nem backup gerenciado confiável; sem backup próprio, uma falha apaga o projeto de portfólio.
- **Solução:** `pg_dump` diário criptografado por um papel somente leitura (`crm_backup`, sem `BYPASSRLS`, com política de leitura nomeada) e uma verificação mensal de restauração.
- **Metas:**
  1. Um backup por dia, guardado por 30 dias fora do Supabase.
  2. Dump criptografado: nenhum dado em texto claro no artefato.
  3. Restauração verificada todo mês em PostgreSQL limpo, com as mesmas contagens de linhas.
  4. Nenhum papel de backup consegue escrever ou alterar o schema.

## 2. User stories

### US-001: Backup diário
**Descrição:** Como operador, quero um dump diário de `core`.

**Critérios de aceite:**
- [ ] `db-backup.yml` roda diariamente e manualmente; usa `crm_backup` pelo pooler *session*.
- [ ] Comando `pg_dump -Fc --schema=core --exclude-table=core.flyway_schema_history --enable-row-security --no-owner --no-acl` (o `crm_backup` não lê o histórico do Flyway).
- [ ] Dump criptografado com `age` (chave pública) e publicado como *artifact* privado com `retention-days: 30`.
- [ ] Falha do workflow é visível (status vermelho).

### US-002: Backup completo apesar da RLS
**Descrição:** Como operador, quero que o backup leia todas as clínicas sem usar `BYPASSRLS`.

**Critérios de aceite:**
- [ ] Cada tabela de negócio tem política `SELECT ... TO crm_backup USING (true)`.
- [ ] Sem essa política, o teste de catálogo (F02) e o teste 26 falham o PR.

### US-003: Restauração verificada
**Descrição:** Como operador, quero ter certeza de que o backup restaura.

**Critérios de aceite:**
- [ ] `db-restore-check.yml` mensal baixa o último dump, descriptografa, restaura em `postgres:16` descartável (após o bootstrap) e compara contagens e constraints.
- [ ] Falha na restauração reprova o workflow.

### US-004: Papel de backup somente leitura
**Descrição:** Como responsável pela segurança, quero que o backup não possa alterar nada.

**Critérios de aceite:**
- [ ] `crm_backup` só tem `SELECT` e `USAGE`; `INSERT`, `UPDATE`, `DELETE` e DDL falham.
- [ ] `rolbypassrls` falso.

## 3. Requisitos funcionais

- **FR-1:** Workflows `db-backup.yml` e `db-restore-check.yml` conforme o contract.
- **FR-2:** Chave pública `age` em `BACKUP_AGE_PUBLIC_KEY`; chave privada apenas no ambiente `restore-test`.
- **FR-3:** Dumps nomeados `crm-core-<UTC yyyymmddThhmmss>.dump.age`.
- **FR-4:** Retenção de 30 dias (`retention-days`).
- **FR-5:** O dump contém as políticas RLS mas não papéis, donos nem GRANTs; a recuperação roda o bootstrap, o `flyway migrate` e então `pg_restore --data-only`. Os segredos do backup ficam no ambiente `backup` (sem revisor).

## 4. Fora do escopo

- PITR e réplicas.
- Armazenamento externo pago (S3 etc.).
- Backup de outros schemas ou do `auth`/`storage` do Supabase (não usados).
- Retenção além de 30 dias.

## 5. Considerações técnicas e riscos

- Perda máxima de dados aceita: até 24 h (projeto de portfólio, dados fictícios).
- A chave privada do `age` no GitHub é um compromisso aceito para o portfólio; num caso real, ficaria offline.
- Artefatos do GitHub têm limite de tamanho e retenção; o banco do plano gratuito (≈ 500 MB) cabe com folga.
- `pg_dump` com `--enable-row-security` e o pooler *session* precisa ser validado no projeto real.
- **Pergunta aberta:** nenhuma.
