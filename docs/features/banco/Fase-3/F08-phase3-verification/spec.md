# Spec — F08 Verificação da Fase 3 (testes, rollback e backup)

Complementa `docs/global/banco/Fase-3/spec.md` (seções 5.1, 9 e 10) e as specs da F08 das Fases 1 e 2.

## 1. Visão técnica
- **Feature:** suíte de testes, cadeia de rollback e conferência de backup da Fase 3.
- **Stack:** `psql -v ON_ERROR_STOP=1`, scripts `bash` com duas conexões para concorrência, `pg_dump`/`pg_restore`, contêiner descartável (`database/docker-compose.yml`, perfil `test`).
- **Abordagem:** um arquivo por área, numeração contínua a partir de 86, tudo chamado por `run_all.sh`; os testes de papel usam só `crm_app`, `crm_definer` e `crm_backup` sob RLS (`crm_owner` apenas prepara dados).

## 2. Arquivos (`database/tests/`)

| Arquivo | Testes | Feature |
|---|---|---|
| `procedure_types.sql` | 88, 89 | F01 |
| `appointments_procedure_fk.sql` | 90 | F01 |
| `commercial_concurrency.sh` | 91, 99, 107 e 110 (duas conexões) | F02, F03, F05 |
| `budgets.sql` | 92, 94, 97, 98 | F02 |
| `budget_items.sql` | 93 | F02 |
| `budget_status.sql` | 95, 96 | F02 |
| `installments.sql` | 100, 101, 102, 103 | F04 |
| `payments.sql` | 104, 105, 108 (e a parte de `payments` do 103) | F05 |
| `payments_append_only.sql` | 106 | F05 |
| `money_invariants.sql` | 109 (propriedade) | F02, F04, F05 |
| `budget_documents.sql` | 110 | F03 |
| `audit_commercial.sql` | 111 | F06 |
| `anonymize_commercial.sql` | 112, 113 | F07 |
| `phase3_isolation.sql` | 86, 87 | todas |
| `phase3_permissions.sql` | 114, 115 | todas |
| `phase3_performance.sql` | 116 | F02, F04, F05 |
| `migrations_phase3.sh` | 117, 118 (duas partes: banco com dados e banco novo) | todas |
| `backup_restore.sh` (ampliado) | 119 | todas |
| `schema_dictionary.sql`, `table_counts.sql` (ampliados) | 121 | todas |

Ampliados: `run_all.sh`, `catalog_rls_pattern.sql`, `roles_and_privileges.sql`, `permissions_tables.sql`, `rollback_chain.sh`, `anonymization.sql`, `audit_log.sql`, `backup_rehearsal.sh`, `scripts/restore_check.sh`, `scripts/table_counts.sql`.

## 3. Roteiros-chave

### 3.1 Dados de apoio
Duas clínicas (A e B), cada uma com um `DENTIST_ADMIN`, uma `RECEPTIONIST`, 2 pacientes (um inativo), 2 procedimentos e 1 agendamento; criados como `crm_owner`/`postgres` no banco descartável; cada teste abre uma transação como `crm_app` com `set_config('app.clinic_id'|'app.role'|'app.user_id', ..., true)`.

### 3.2 Concorrência (duas conexões)
Padrão: a conexão 1 abre a transação e faz a primeira operação; a conexão 2 inicia a sua em segundo plano; o script aguarda `pg_stat_activity.wait_event_type = 'Lock'` da conexão 2; a conexão 1 confirma (ou desfaz); o script lê o resultado da conexão 2.
- **91:** dois `INSERT` de orçamento na clínica A → números 1 e 2; clínica B não espera.
- **99:** (a) A insere item, B tenta `SENT`, A confirma → B envia com o item; (b) B envia primeiro, A insere → `CR007`.
- **107:** dois pagamentos no último saldo → um passa, o outro `CR010`; com `ROLLBACK` do primeiro, o segundo passa; parcelas diferentes nunca esperam.
- **110:** duas inserções de PDF no mesmo orçamento → revisões 1 e 2.

### 3.3 Propriedade (109)
Laço `plpgsql` com `setseed(0.42)` e 1.000+ iterações; cada iteração sorteia uma operação entre criar orçamento, incluir/alterar/remover item, aplicar desconto, enviar, aprovar, recusar, gerar parcelas, refazer o plano, pagar (parcial/total), estornar, cancelar, tentar alterar pagamento; operações inválidas devem falhar com o código esperado e **não** alterar dados (comparação de somas antes/depois). A cada 50 iterações e no fim confere:
(a) `total_amount = Σ line_total − discount_amount` em todo orçamento `DRAFT` (e igual ao congelado nos demais);
(b) Σ parcelas ativas = `total_amount` onde há parcela ativa;
(c) 0 parcelas com líquido > `amount` ou < 0;
(d) `status = 'PAID'` ⇔ líquido ≥ `amount`;
(e) nenhum orçamento fora de `DRAFT` com item inserido, alterado ou removido depois do envio (contagem de itens e soma estáveis).
A semente e a iteração são impressas na falha.

### 3.4 Desempenho (116)
Carga: 20 clínicas × (5 mil parcelas, 5 mil pagamentos, ~1,5 mil orçamentos); `ANALYZE`; consultas da spec global 4.8 com `EXPLAIN (ANALYZE, BUFFERS)` como `crm_app` sob a sessão de uma clínica. Falha se houver `Seq Scan` nas tabelas grandes ou se o tempo passar do limite (vencidas ≤ 10 ms, recebimentos do mês ≤ 10 ms, faturamento ≤ 10 ms, orçamentos do paciente ≤ 5 ms, `INSERT` de pagamento ≤ 15 ms, orçamento de 20 itens ≤ 100 ms). Limite ajustável por variável (`PERF_FACTOR`) para máquinas lentas.

### 3.5 Migrations e rollback (117, 118)
`apply_migrations.sh` aplica V001–V029; `rollback_chain.sh` reverte `U029 → U022` na ordem inversa (U029 antes de U028 etc.; `U027` antes de `U026`) e reaplica; confere o catálogo antes e depois; a função restaurada pela `U029` é testada contra os cenários da V021 (sem `CR006`, sem notas de orçamento).

### 3.6 Backup e restauração (119)
`pg_dump` como `crm_backup` (todas as 6 tabelas e as colunas novas) → restauração em PostgreSQL limpo como superusuário com `--disable-triggers` → contagens iguais por tabela (`table_counts.sql`) → guarda de catálogo (`pg_get_constraintdef`, `pg_get_triggerdef`, `pg_get_indexdef`, `pg_policies`) idêntico ao do banco de origem.

## 4. Rastreabilidade
Tabela `docs/features/banco/Fase-3/traceability.md` não é gerada (a skill proíbe arquivos extras); a rastreabilidade é a coluna "Feature" da seção 2 e os critérios de aceite de cada `contract.md`, seção 6.

## 5. Erros e bordas
| Situação | Resposta |
|---|---|
| Teste falha | `psql` sai com ≠ 0; `run_all.sh` aborta e mostra o arquivo e o número |
| Plano com `Seq Scan` ou limite estourado (116) | Reprova a fase |
| Invariante violada (109) | Reprova; imprime semente e iteração |
| Contagem diferente após restauração (119) | Reprova |
| Cadeia de rollback não reaplica | Reprova |
| Concorrência não determinística | Teste inválido; ajustar a sincronização |

## 6. Segurança e desempenho
- Testes só como `crm_app`/`crm_definer`/`crm_backup`; segredos do contêiner de teste vêm de variável de ambiente.
- O 116 carrega ~200 mil linhas com triggers; manter o tempo total da suíte aceitável (meta: `run_all.sh` completo em minutos, não horas).
