# Spec — F06 Verificação da Fase 4 (banco)

## 1. Visão técnica
- **Feature:** testes transversais, harness, ensaio no Supabase real e fechamento das pendências herdadas.
- **Stack:** `psql`, shell, Docker (harness `database/tests`), SQL Editor do Supabase.
- **Abordagem:** nenhuma migration nova; arquivos de teste e scripts, mais atualização de documentação.

## 2. Entregáveis

| Arquivo / ação | Conteúdo |
|---|---|
| `tests/support/phase4_seed.sql` | duas clínicas; dentista com CRO e sem CRO; recepcionista; pacientes ativo, inativo e anonimizado; agendamentos e orçamentos mínimos |
| `tests/phase4_isolation.sql` (138) | isolamento de `clinical_documents` e `users.cro_*`; sem `app.clinic_id`; `WITH CHECK` |
| `tests/phase4_permissions.sql` (139) | matriz papel × tabela × operação; `crm_app` sem DDL; sem `BYPASSRLS`; recepção sem documentos, sem parcela e pagamento |
| `tests/migrations_phase4.sh` (140) | V031–V035 do zero, `U035 → U031`, reaplicação, `anonymize_patient` = V029 depois de `U034`, falha atômica da `U033` com auditoria gravada (`U031` e `U032` são destrutivas por convenção) |
| `tests/schema_dictionary.sql` (141) | colunas, constraints, índices, funções e triggers novos |
| `tests/backup_restore.sh` (142) | dump e restauração com `clinical_documents` e auditoria; triggers `no_delete`/`no_truncate` tratados só nos scripts |
| `tests/run_all.sh` | `MIGRATIONS_EXPECTED=35`; chamada de `documents_concurrency.sh` e `migrations_phase4.sh`; `catalog_rls_pattern` cobre a tabela |
| Teste 143 | `ddl-auto=validate` da Fase 4: **depende do backend**; registrado como pendente |
| `database/README.md` | seção "Fase 4: documentos e dashboard (V031–V035)", tabela de migrations, regras para o backend, testes novos; seção da V030 |
| `docs/global/banco/Fase-3/spec.md` §4.2/§8 | V030 documentada |
| Relatório da fase | resultado dos testes 125–143 e da conferência no real |

Numeração dos testes das demais features: F01 125–126; F02 127–131; F03 132–134; F04 135–136; F05 137.

## 3. Componentes
Scripts e testes acima; sem objeto de banco.

## 4. Lógica

**Harness local**
1. `export POSTGRES_PASSWORD="$(openssl rand -hex 24)"`; `docker compose -f database/docker-compose.yml --profile test run --rm --build tests`.
2. Esperado: `TODOS OS TESTES PASSARAM`, 0 falhas; depois `docker compose -f database/docker-compose.yml down -v` (com a variável definida).

**Supabase real (manual, ordem)**
1. Backup lógico antes (`scripts/backup.sh`).
2. `apply_migrations.sh 35` como `crm_owner`, porta 5432.
3. Conferir no SQL Editor como `postgres`: `\d core.users` (colunas e `CHECK`s), `relrowsecurity`/`relforcerowsecurity` de `clinical_documents`, `pg_policies`, `has_function_privilege('crm_app', 'core.record_document_download(uuid)', 'EXECUTE')`, constraints do `audit_log`, índice `ix_appt_completed_patient`.
4. Smoke da Fase 3 refeito (backend `prod` com o `.env` carregado linha a linha, nunca imprimir valores).
5. Teste de conflito (`23P01`) e `CR004` pela API ou por SQL no real; limpar o que criar.
6. Limpeza do resíduo do smoke anterior: o usuário roda `cleanup_smoke_phase3.sql` como `postgres`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| `down -v` sem `POSTGRES_PASSWORD` | não remove o volume: definir a variável |
| Migração aplicada com falha no real | transação aborta; corrigir com nova versão (nunca editar migration aplicada) |
| Resíduo de teste em `clinical_documents` | `DELETE` é negado até ao dono: limpar com o bloco de limpeza que desliga `trg_cdoc_no_delete` como `postgres` |
| Docker desligado | harness não roda; reportar, não marcar como aprovado |

## 6. Segurança e desempenho
- Nenhum segredo em log; senhas de papéis só por variável.
- Harness completo em cerca de 3 a 4 minutos; testes de desempenho com massa fixa e semente fixa para serem reprodutíveis.
- O relatório distingue o que rodou no Docker do que foi conferido no real.
