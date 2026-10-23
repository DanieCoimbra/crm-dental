# PRD — F08 Verificação da Fase 2 (testes, rollback e backup)

**Derivação:** PRD global da Fase 2, US-210 e metas 1 a 6; spec global, seção 9 (testes 59–85), 5.1 e 10. Equivale à F07 da Fase 1.
**Dependências externas:** F01 a F07 (todas as migrations V014–V021 e o bootstrap), `database/tests/` e `run_all.sh` da Fase 1, `scripts/apply_migrations.sh`, backup (`backup.sh`, `backup_rehearsal.sh`). Não há consumidor posterior: é o portão de qualidade da fase.

## 1. Introdução

- **Problema:** as garantias da fase (conflito, papéis, isolamento, auditoria, anonimização) só valem se forem provadas do zero, em banco descartável, e se o backup e o rollback cobrirem as tabelas novas.
- **Solução:** ampliar a suíte `database/tests/` com os testes 59–85, estender os testes de catálogo, de permissão e de backup, e a cadeia de rollback `U021 → U014`.
- **Metas:**
  1. `run_all.sh` passa do zero sobre V001–V021, com 0 falhas.
  2. Os 27 testes numerados (59–85) existem e cobrem cada critério de aceite das features F01–F07.
  3. Planos do teste 81 sem `Seq Scan` e dentro dos limites (agenda ≤ 10 ms, conflito ≤ 5 ms).
  4. `pg_dump` como `crm_backup` traz as 4 tabelas novas; restauração fecha as contagens.

## 2. User stories

### US-001: Suíte de isolamento, papéis e constraints
**Descrição:** Como responsável pelo banco, quero testes que provem o isolamento e os papéis nas tabelas novas.

**Critérios de aceite:**
- [ ] Testes 59, 60 (isolamento, FK composta), 67 e 68 (`app.role` inválido, autoria), com duas clínicas.
- [ ] Testes locais das features: 61–66 e 74 (F02), 70 (F03), 69, 71 e 72 (F03/F04), 73 (F01), 75 (F05), 76 (F06), 77 e 78 (F07), todos incorporados ao `run_all.sh`.
- [ ] Cada teste sai com código ≠ 0 se falhar (`ON_ERROR_STOP`).

### US-002: Concorrência e desempenho
**Descrição:** Como dentista, quero a prova de que dois agendamentos simultâneos não ocupam o mesmo horário e de que a agenda é rápida.

**Critérios de aceite:**
- [ ] Teste 63 com duas conexões `psql` (a segunda espera e recebe `23P01`; com `ROLLBACK` da primeira, passa).
- [ ] Teste 81: 10 clínicas × 20 mil agendamentos sem sobreposição (200 mil no total); `EXPLAIN` da agenda da semana ≤ 10 ms, do conflito ≤ 5 ms, do histórico do paciente e do `CR004` sem `Seq Scan`.

### US-003: Permissões e catálogo
**Descrição:** Como responsável pela segurança, quero a conferência automática dos grants, da RLS e das funções.

**Critérios de aceite:**
- [ ] Teste 79: `crm_app` sem `DELETE`/`TRUNCATE`/`DROP`/`ALTER` em `appointments`, `business_hours`, `holidays`; `DELETE` apenas em `clinic_holidays`; `crm_definer` só com `SELECT` e `UPDATE (notes, version)` em `appointments`.
- [ ] Teste 80: toda tabela nova com `ENABLE` + `FORCE`, política por operação e política `SELECT TO crm_backup`; funções novas com `search_path` fixo e sem `EXECUTE` para `PUBLIC`; `catalog_rls_pattern.sql` aceita as políticas de `crm_owner` em `holidays`.

### US-004: Migrations, rollback e backup
**Descrição:** Como responsável pelo banco, quero aplicar, reverter, reaplicar e fazer backup/restauração.

**Critérios de aceite:**
- [ ] Teste 82: V014–V021 aplicam do zero; a cadeia `U021 → U014` reverte e reaplica (estende `rollback_chain.sh`); `MIGRATIONS_EXPECTED = 21`.
- [ ] Teste 83: bootstrap idempotente; V015 sem `btree_gist` aborta com a mensagem do guarda.
- [ ] Teste 84: `ddl-auto=validate` do backend passa contra o schema (depende do backend da Fase 2).
- [ ] Teste 85: `pg_dump` como `crm_backup` traz as 4 tabelas e as colunas novas; restaura em PostgreSQL limpo com as mesmas contagens; o guarda de catálogo da restauração aceita o `EXCLUDE` e as políticas.

## 3. Requisitos funcionais

- **FR-1:** Arquivos de teste novos e ampliados em `database/tests/`, listados na spec.
- **FR-2:** `run_all.sh` executa tudo do zero e exporta `MIGRATIONS_EXPECTED=21`.
- **FR-3:** `schema_dictionary.sql`, `roles_and_privileges.sql`, `permissions_tables.sql`, `table_counts.sql`, `catalog_rls_pattern.sql` e os testes de backup ampliados para as tabelas e colunas novas.
- **FR-4:** `database/rollback/` ganha `U014…U021` e o `README.md` do rollback cita o bootstrap estendido.
- **FR-5:** `database/README.md` documenta a Fase 2 (extensão `btree_gist` no bootstrap, passos manuais no Supabase).

## 4. Fora do escopo

- Testes do backend (módulo `scheduling`) e do frontend.
- Teste de carga com concorrência alta (basta o par de conexões do teste 63).
- Validação contra o Supabase real (checklist manual, spec global seção 10).
- CI/CD (adiado).

## 5. Considerações técnicas e riscos

- O teste 84 só roda quando o backend da Fase 2 existir; até lá fica marcado como pendente (como o teste 57 na Fase 1).
- O teste 81 insere 200 mil linhas com o `EXCLUDE` ativo: gerar os horários sem sobreposição (8 por dia, ~2.500 dias por clínica) e carregar como `crm_app` com sessão por clínica, ou como `crm_owner` com as políticas ajustadas apenas no banco descartável.
- O teste 63 depende de sincronizar duas conexões (por exemplo, `pg_sleep` curto ou `pg_stat_activity` aguardando); deve ser determinístico.
- **Pergunta aberta:** nenhuma.
