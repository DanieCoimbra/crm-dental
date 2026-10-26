# PRD — F08 Verificação da Fase 3 (testes, rollback e backup)

**Derivação:** PRD global da Fase 3, US-310 e metas 1 a 7; spec global, seção 9 (testes 86–121), 5.1 e 10. Equivale à F08 da Fase 2.
**Dependências externas:** F01 a F07 (migrations V022–V029), `database/tests/` e `run_all.sh` das Fases 1 e 2, `scripts/apply_migrations.sh`, backup (`backup.sh`, `backup_rehearsal.sh`, `restore_check.sh`). Não há consumidor posterior: é o portão de qualidade da fase.

## 1. Introdução

- **Problema:** as garantias da fase (total e congelamento do orçamento, soma das parcelas, pagamento append-only, papéis, isolamento, auditoria, anonimização) só valem se forem provadas do zero, em banco descartável, e se o backup e o rollback cobrirem as tabelas novas.
- **Solução:** ampliar a suíte `database/tests/` com os testes 86–121, estender os testes de catálogo, de permissão e de backup, e a cadeia de rollback `U029 → U022`.
- **Metas:**
  1. `run_all.sh` passa do zero sobre V001–V029, com 0 falhas.
  2. Os 36 testes numerados (86–121) existem e cobrem cada critério de aceite de F01–F07.
  3. O teste de propriedade (109) confere as invariantes de dinheiro em ≥ 1.000 operações.
  4. Planos do teste 116 sem `Seq Scan` e dentro dos limites (vencidas ≤ 10 ms, recebimentos do mês ≤ 10 ms, faturamento ≤ 10 ms, orçamentos do paciente ≤ 5 ms).
  5. `pg_dump` como `crm_backup` traz as 6 tabelas novas; restauração fecha as contagens.

## 2. User stories

### US-001: Suíte de isolamento, papéis e constraints
**Descrição:** Como responsável pelo banco, quero testes que provem o isolamento e os papéis nas tabelas novas.

**Critérios de aceite:**
- [ ] Testes 86 e 87 (isolamento e FKs cruzadas), 98 e 103 (papéis), com duas clínicas (A e B).
- [ ] Testes locais das features: 88–90 (F01), 91–99 (F02), 110 (F03), 100–103 (F04), 104–109 (F05), 111 (F06), 112–113 (F07), todos incorporados ao `run_all.sh`.
- [ ] Cada teste sai com código ≠ 0 se falhar (`ON_ERROR_STOP`).

### US-002: Concorrência e propriedade
**Descrição:** Como dentista, quero a prova de que numeração, envio e pagamento resistem a duas operações simultâneas e de que o dinheiro fecha.

**Critérios de aceite:**
- [ ] Testes 91 (numeração), 99 (item × envio), 107 (pagamento no último saldo) e 110 (revisões) com duas conexões `psql` determinísticas.
- [ ] Teste 109: laço `plpgsql` com semente fixa, ≥ 1.000 operações válidas e inválidas, conferindo (a)–(e) a cada passo e ao final.

### US-003: Permissões e catálogo
**Descrição:** Como responsável pela segurança, quero a conferência automática dos grants, da RLS e das funções.

**Critérios de aceite:**
- [ ] Teste 114: `crm_app` sem `DELETE` (exceto `budget_items`), sem `UPDATE`/`DELETE`/`TRUNCATE` em `payments`, sem `TRUNCATE`/`DROP`/`ALTER` nas 6 tabelas; `UPDATE` de `budget_documents` só em `deleted_at`; `crm_definer` com os grants mínimos; `EXECUTE` do `crm_app` só nas funções esperadas.
- [ ] Teste 115: toda tabela nova com `ENABLE` + `FORCE`, política por operação e política `SELECT TO crm_backup`; funções novas com `search_path` fixo e sem `EXECUTE` para `PUBLIC`; **nenhuma** `SECURITY DEFINER` nova.

### US-004: Desempenho
**Descrição:** Como dentista, quero que o financeiro responda rápido com a RLS ativa.

**Critérios de aceite:**
- [ ] Teste 116: 20 clínicas × 5 mil parcelas e 5 mil pagamentos (100 mil de cada), orçamentos proporcionais; as consultas da spec global 4.8 cumprem as metas, sem `Seq Scan` (exceto a lista de procedimentos), com `EXPLAIN` registrado.

### US-005: Migrations, rollback e backup
**Descrição:** Como responsável pelo banco, quero aplicar, reverter, reaplicar e fazer backup/restauração.

**Critérios de aceite:**
- [ ] Teste 117: V022–V029 aplicam do zero; a cadeia `U029 → U022` reverte e reaplica (estende `rollback_chain.sh`); `MIGRATIONS_EXPECTED = 29`.
- [ ] Teste 118: `anonymize_patient` restaurada pela `U029` volta ao comportamento da V021.
- [ ] Teste 119: `pg_dump` como `crm_backup` traz as 6 tabelas; restaura em PostgreSQL limpo (superusuário, `--disable-triggers`) com as mesmas contagens; o guarda de catálogo aceita a constraint trigger adiável, a coluna gerada, os índices parciais e as políticas.
- [ ] Teste 120: `ddl-auto=validate` do backend contra o schema (inclui o teste 84 da Fase 2, hoje possível, e o da Fase 3 quando o backend existir).
- [ ] Teste 121: `schema_dictionary.sql` e `table_counts.sql` conhecem as 6 tabelas novas.

## 3. Requisitos funcionais

- **FR-1:** Arquivos de teste novos e ampliados em `database/tests/`, listados na spec.
- **FR-2:** `run_all.sh` executa tudo do zero e exporta `MIGRATIONS_EXPECTED=29`.
- **FR-3:** `schema_dictionary.sql`, `roles_and_privileges.sql`, `permissions_tables.sql`, `table_counts.sql`, `catalog_rls_pattern.sql` e os testes de backup ampliados para as tabelas e colunas novas.
- **FR-4:** `database/rollback/` ganha `U022…U029` e o `README.md` do rollback é atualizado.
- **FR-5:** `database/README.md` documenta a Fase 3 (sem passo manual novo no Supabase; PDF no bucket dos exames).
- **FR-6:** Rastreabilidade: cada critério de aceite de F01–F07 aparece em pelo menos um teste numerado (tabela na spec).

## 4. Fora do escopo

- Testes do backend e do frontend.
- Teste de carga com concorrência alta (bastam os pares de conexões).
- Validação contra o Supabase real (checklist manual, spec global seção 10).
- CI/CD (adiado).
- Corrigir a pendência da spec 4.6 da Fase 2 (pré-checagem de conflito por intervalo).

## 5. Considerações técnicas e riscos

- O teste 120 só cobre a Fase 3 quando o backend existir; até lá, fica pendente (como os testes 57 e 84).
- O teste 116 insere 100 mil parcelas e 100 mil pagamentos com triggers ativos (guard, sincronização, auditoria): carregar como `crm_app` com sessão por clínica ou, no banco descartável, como `crm_owner` com os triggers de auditoria e de pagamento tratados por `session_replication_role`/lote — a escolha precisa preservar os índices e as estatísticas reais (`ANALYZE` depois da carga).
- O teste 119 usa superusuário com `--disable-triggers`; confirmar que o `pg_restore` de `payments` não dispara o trigger de `TRUNCATE` e que `restore_check.sh` só trunca `holidays`.
- Os testes de concorrência (91, 99, 107, 110) dependem de sincronizar duas conexões de forma determinística (por exemplo, `pg_stat_activity` aguardando o lock).
- A semente do teste 109 precisa ser fixa e impressa em caso de falha, para reproduzir.
- **Pergunta aberta:** nenhuma.
