# PRD — F06 Verificação da Fase 4 (banco)

**Derivação:** PRD global da Fase 4, US-408, US-409, decisão D13 e FR-13, FR-14; spec global, seção 7 e 8.
**Dependências externas:** F01 a F05 (todas as migrations V031–V035). Mesmo papel da F08 da Fase 3.

## 1. Introdução

- **Problema:** sem verificação transversal, a fase pode passar com falhas de isolamento, privilégio, restauração e reversão, e as pendências herdadas das Fases 2 e 3 continuam abertas.
- **Solução:** suíte de isolamento, permissões, migrações e restauração para as tabelas novas, harness atualizado (`MIGRATIONS_EXPECTED=35`), aplicação conferida no Supabase real e fechamento das pendências herdadas.
- **Metas:**
  1. Harness completo verde, 0 falhas.
  2. Isolamento entre duas clínicas e sem `app.clinic_id`: 0 linhas visíveis, 0 `INSERT` cruzado.
  3. `crm_app` sem `DROP/TRUNCATE/ALTER`, sem `BYPASSRLS`, sem `GRANT ALL`.
  4. Cadeia de rollback `U035 → U031` e reaplicação sem resíduo; função `anonymize_patient` restaurada.
  5. Pendências D13 fechadas ou formalmente adiadas.

## 2. User stories

### US-001: Isolamento e permissões
**Descrição:** Como arquiteto, quero provar o isolamento e o menor privilégio das tabelas novas.

**Critérios de aceite:**
- [ ] `phase4_isolation.sql`: duas clínicas, `clinical_documents` e `users.cro_*`; sem `app.clinic_id` nada aparece; `INSERT` com `clinic_id` alheio falha por `WITH CHECK`.
- [ ] `phase4_permissions.sql`: matriz da spec global 2.5 (dentista, recepção, `crm_backup`, `crm_definer`); `catalog_rls_pattern.sql` verde para a tabela nova.
- [ ] `schema_dictionary.sql` atualizado (colunas, índices, constraints, funções).

### US-002: Migrações, backup e restauração
**Descrição:** Como operador, quero aplicar, reverter e restaurar com segurança.

**Critérios de aceite:**
- [ ] `migrations_phase4.sh`: V031–V035 do zero; `U035 → U031` em cadeia; reaplicação; `anonymize_patient` igual à V029 depois de `U034`.
- [ ] `backup_restore.sh` e `restore_check` cobrem `clinical_documents`; `trg_cdoc_no_delete`/`no_truncate` desligados só nos scripts de limpeza e restauração (como `trg_pay_no_truncate`).
- [ ] `MIGRATIONS_EXPECTED=35` em `run_all.sh` e nos testes que o leem.

### US-003: Supabase real e pendências herdadas
**Descrição:** Como arquiteto, quero a fase aplicada e conferida no ambiente real e as pendências fechadas.

**Critérios de aceite:**
- [ ] V031–V035 aplicadas como `crm_owner` (`apply_migrations.sh 35`, porta 5432) e conferidas no SQL Editor (colunas de `users`, RLS `ENABLE`+`FORCE`, função de download, constraints do `audit_log`, índice).
- [ ] Smoke da Fase 3 refeito depois da V030 sem regressão (backend `prod`, `ddl-auto=validate`), e a clínica fictícia do smoke anterior limpa pelo usuário.
- [ ] Conflito de horário `23P01` e `CR004` exercitados no Supabase real.
- [ ] V030 documentada na spec (§4.2 e §8) e no `database/README.md`; spec 4.6 da Fase 2 (pré-checagem de conflito por intervalo) decidida ou adiada por escrito.
- [ ] README do banco com a Fase 4 (tabela de migrations, regras para o backend, testes novos).
- [ ] Teste de `ddl-auto=validate` da Fase 4 registrado como dependência do backend, não como falha.

## 3. Requisitos funcionais
- **FR-1:** Suíte nova a partir do teste 138 (ver contrato) integrada a `tests/run_all.sh`.
- **FR-2:** Nenhum segredo, chave AES ou texto clínico em claro em log, migration, seed ou comentário.
- **FR-3:** Relatório de verificação com resultado de cada teste e, para o real, o que foi conferido e por quem.

## 4. Non-goals
- CI/CD (adiado até o fim do projeto); homologação separada; testes do backend (outra função).
- Resolver as pendências de segurança fora do banco (senha do `postgres`, bucket sem limite de 10 MB/MIME, guarda da chave AES).

## 5. Requisitos de IA
Não se aplica.

## 6. Especificações técnicas
Ver `spec.md`. Harness: `docker compose -f database/docker-compose.yml --profile test run --rm --build tests` com `POSTGRES_PASSWORD` exportada (Docker Desktop ligado), cerca de 3 a 4 minutos.

## 7. Riscos e perguntas abertas
1. Operação do Supabase real é manual: a conferência depende do usuário rodar o SQL Editor como `postgres`.
2. Lock consultivo `cdoc:` atrás do pooler 6543 só se confirma com o backend.
3. Restauração: os triggers de imutabilidade não podem barrar a carga do `pg_restore`.
- Perguntas abertas: nenhuma.
