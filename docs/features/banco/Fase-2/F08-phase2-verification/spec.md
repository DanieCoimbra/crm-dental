# Spec — F08 Verificação da Fase 2

Complementa `docs/global/banco/Fase-2/spec.md` (seções 5.1, 9 e 10) e a F07 da Fase 1.

## 1. Visão técnica
- **Feature:** testes, rollback e backup da Fase 2.
- **Stack:** `psql -v ON_ERROR_STOP=1`, shell (`run_all.sh`, `rollback_chain.sh`), Docker Compose de `database/` (perfil `test`), `pg_dump`/`pg_restore`.
- **Abordagem:** novos arquivos SQL por área, harness existente ampliado, numeração contínua a partir de 59.

## 2. Arquivos de teste (`database/tests/`)

| Arquivo | Testes | Feature de origem |
|---|---|---|
| `clinic_timezone.sql` | 73 | F01 |
| `appointments_conflict.sql` | 61, 62, 64 | F02 |
| `appointments_concurrency.sh` | 63 (duas conexões `psql`) | F02 |
| `appointments_roles.sql` | 65, 66 | F02 |
| `appointments_patient_guard.sql` | 74 | F02 |
| `business_hours.sql` | 70, 69 (parte) | F03 |
| `holidays.sql` | 69 (parte), 71, 72 | F04 |
| `notes_appointment_link.sql` | 60 (parte das notas), 75 | F05 |
| `audit_appointments.sql` | 76 | F06 |
| `anonymize_appointments.sql` | 77, 78 | F07 |
| `phase2_isolation.sql` | 59, 60 (parte do paciente), 67, 68 | F08 |
| `phase2_permissions.sql` | 79, 80 | F08 |
| `phase2_performance.sql` | 81 | F08 |
| `migrations_phase2.sh` | 82, 83, 85 | F08 |
| (backend) `ddl-auto=validate` | 84 | F08 (pendente do backend) |

### 2.1 Harness
- `run_all.sh` passa a exportar `MIGRATIONS_EXPECTED=21` e invoca os arquivos acima depois dos da Fase 1.
- `schema_dictionary.sql` e `table_counts.sql` incluem `appointments`, `business_hours`, `clinic_holidays`, `holidays`, `clinics.timezone` e `clinical_notes.appointment_id`.
- `roles_and_privileges.sql` e `permissions_tables.sql`: matriz da spec global, seção 3.1.
- `catalog_rls_pattern.sql`: exige `ENABLE` + `FORCE`, uma política por operação e a política `SELECT TO crm_backup`; **aceita** as quatro políticas de `crm_owner` em `core.holidays` e a política `SELECT USING (true)` de `crm_app` na mesma tabela.
- `rollback_chain.sh`: cadeia `U021 → U014` (reverte e reaplica).
- Testes de backup: o `pg_dump` como `crm_backup` inclui as 4 tabelas e as duas colunas novas.

### 2.2 Dados de teste
Duas clínicas (A e B), cada uma com usuário `DENTIST_ADMIN` e `RECEPTIONIST` e pacientes ativos, inativos e anonimizados; sessões criadas por `set_config('app.clinic_id'|'app.role'|'app.user_id', ..., true)` dentro de `BEGIN`/`COMMIT`, como na Fase 1.

### 2.3 Teste 63 (concorrência)
1. Conexão 1: `BEGIN; INSERT` (horário H); mantém aberta.
2. Conexão 2, em segundo plano: `INSERT` do mesmo H; confirma-se por `pg_stat_activity` (`wait_event_type = 'Lock'`) que está esperando.
3. Conexão 1: `COMMIT`; a conexão 2 termina com `23P01`.
4. Repete com `ROLLBACK` na conexão 1: a conexão 2 passa.
5. Variante com a clínica B no mesmo horário H: nunca espera.

### 2.4 Teste 81 (desempenho)
- Carga: 10 clínicas, 20 mil agendamentos cada, 8 por dia de 30 min (~2.500 dias), sem sobreposição; `ANALYZE` ao fim.
- Consultas medidas com `EXPLAIN (ANALYZE, BUFFERS)` como `crm_app` sob uma clínica: agenda da semana (consulta canônica da spec global 4.6), conflito (`&&`), histórico do paciente (keyset) e `EXISTS` do `CR004`.
- Aceite: agenda ≤ 10 ms, conflito ≤ 5 ms; planos sem `Seq Scan` e sem *sort*; `EXPLAIN` impresso no log. O limite aceita a variável `CI` e `-Dperf.limit-ms` equivalente (limite dobrado em máquina compartilhada), como nos testes da Fase 1.

### 2.5 Rollback e documentação
- `database/rollback/U014…U021`, com a ordem inversa e as ressalvas (as `DROP` só antes de haver dados; `U021` restaura a função da V013; `U019` e `U020` conforme os PRDs das features).
- `database/README.md`: seção "Fase 2" com o bootstrap estendido, o passo manual no Supabase (`CREATE EXTENSION btree_gist`) e o checklist da spec global, seção 10.
- `database/rollback/README.md`: o bootstrap não é migration; instruções de remoção de `btree_gist`.

## 3. Rastreabilidade (metas do PRD global → testes)

| Meta | Testes |
|---|---|
| 1. Conflito atômico | 61, 62, 63 |
| 2. Isolamento | 59, 60 |
| 3. Papéis | 65, 66, 67, 69 |
| 4. Auditoria e sem `DELETE` | 76, 79 |
| 5. Desempenho | 81 |
| 6. Migrations, rollback e backup | 82, 83, 84, 85 |

## 4. Erros e bordas do harness
| Cenário | Tratamento |
|---|---|
| `btree_gist` ausente no banco descartável | O bootstrap de teste a cria; o teste 83 prova o aborto sem ela |
| Teste 63 instável | Sincronizar por `pg_stat_activity`, nunca por `sleep` fixo |
| Teste 81 lento em máquina compartilhada | Limites relaxados por variável de ambiente; o `EXPLAIN` sempre impresso |
| Teste 84 sem backend | Marcado como pendente; não reprova a fase do banco |
| Docker indisponível | O harness falha com mensagem clara (como na Fase 1) |

## 5. Segurança e desempenho
- Os testes rodam só em banco descartável; as senhas vêm de variável de ambiente.
- Nenhum teste usa `BYPASSRLS` ou superusuário para provar as políticas (apenas para preparar o ambiente).
