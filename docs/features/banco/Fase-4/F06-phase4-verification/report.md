# Relatório de verificação — Fase 4 (banco)

**Data:** 2026-10-27 · **Escopo:** V031–V035, U031–U035, testes 125–143 · **Ambientes:** harness local (PostgreSQL 16 em Docker) e Supabase real.

## 1. Harness local (`tests/run_all.sh`, `MIGRATIONS_EXPECTED=35`)

Resultado: `TODOS OS TESTES PASSARAM`, 0 falhas, 251 verificações `ok` (rodada final de 2026-10-27, depois de todas as correções).

| Teste | Arquivo | O que prova | Resultado |
|---|---|---|---|
| 125, 126 | `users_cro.sql` | `CHECK`s do CRO; só o próprio usuário altera o CRO | ✅ |
| 127, 128 | `clinical_documents.sql` | constraints por tipo, conteúdo, `sha256`, FKs; `CR012`, `CR003`, `CR005`, recepção `42501` | ✅ |
| 129 | `clinical_documents_immutable.sql` | só anular, uma vez; `DELETE`/`TRUNCATE` negados a todos; GUC forjado não anonimiza | ✅ |
| 130 | `documents_numbering.sql` | numeração por clínica e tipo; `ROLLBACK` devolve o número | ✅ |
| 131 | `documents_concurrency.sh` | duas conexões: a 2ª espera, `ROLLBACK` libera, tipos e clínicas não se esperam | ✅ |
| 132–134 | `audit_documents.sql` | `INSERT`/`VOID`/`DOWNLOAD`; função de download (recepção, clínica errada, inexistente, anonimizado); constraints e log só de inserção | ✅ |
| 135, 136 | `anonymize_documents.sql` | payload zerado, linha mantida, `ANONYMIZE` por documento, sem bloqueio; exceção não forjável | ✅ |
| 137 | `phase4_performance.sql` | metas com 100 mil linhas (abaixo) | ✅ |
| 138 | `phase4_isolation.sql` | duas clínicas e sem `app.clinic_id`; `WITH CHECK`; `crm_backup` lê as duas | ✅ |
| 139 | `phase4_permissions.sql` | matriz papel × tabela × operação; sem DDL, sem `BYPASSRLS`; `SECURITY DEFINER` com `search_path` fixo | ✅ |
| 140 | `migrations_phase4.sh` | V001–V035 do zero; `U035 → U031`; reaplicação; `anonymize_patient` restaurada; `U033` recusa com auditoria gravada | ✅ |
| 141 | `schema_dictionary.sql` | colunas, tipos, índices e FKs novos | ✅ |
| 142 | `backup_restore.sh` | `clinical_documents` e CRO no dump, contagens e restauração | ✅ |
| 143 | `ddl-auto=validate` da Fase 4 | **depende do backend da Fase 4** | ⏳ pendente por contrato |

**Desempenho medido (100 mil documentos e agendamentos, RLS ativa, `crm_app`):** K1 7,2 ms · K2 0,08 ms · K3 0,08 ms · K4 4,5 ms (`Index Only Scan` em `ix_appt_completed_patient`) · K5 0,3 ms · lista de documentos 0,06 ms · `INSERT` de documento 0,8 ms · `record_document_download` 0,2 ms · anonimizar paciente com 50 documentos 22 ms. Todos dentro das metas; o índice condicional de K1 não foi necessário.

## 2. Supabase real (projeto `mlfpsckefuhbtluenitk`)

| Verificação | Como | Resultado |
|---|---|---|
| V031–V035 aplicadas | aplicadas pelo usuário; conferência de catálogo como `crm_app` (somente leitura) | ✅ |
| `users.cro_number varchar(15)`, `cro_state varchar(2)`, trigger `trg_users_guard_cro` | catálogo | ✅ |
| `clinical_documents`: 19 colunas, RLS `ENABLE` + `FORCE`, 6 políticas, 7 triggers, 4 índices | catálogo | ✅ |
| `ck_audit_table` inclui `clinical_documents`; `ck_audit_action` inclui `VOID` e `DOWNLOAD` | catálogo | ✅ |
| Funções `SECURITY DEFINER` (`write_document_audit`, `record_document_download`, `anonymize_patient`): dono `crm_definer`, `search_path` fixo | catálogo | ✅ |
| `crm_app`: `SELECT`/`INSERT` na tabela; `UPDATE` só de `voided_at`, `voided_by`, `void_reason`, `version`; sem `DELETE`/`TRUNCATE`; executa o download e a anonimização, não a função de trigger | `has_*_privilege` | ✅ |
| `anonymize_patient` trata `clinical_documents` | `pg_get_functiondef` | ✅ |
| Smoke da Fase 3 depois da V030 | backend `prod` + `smoke_phase3.py` (somente API) | ✅ 24/24 |
| `23P01`: sobreposição → 409; mesmo horário → 409; adjacente → 201; corrida simultânea → um 201 e um 409 (pooler 6543) | API | ✅ |
| `CR004` | SQL como `crm_app` em transação revertida (0 linhas depois) | ✅ |
| Limpeza dos dados de teste | `cleanup_smoke_phase3_phase4.sql` pelo usuário como `postgres`; conferência: 0 clínicas `teste-smoke-%` | ✅ |

**Observações do real**
- Pela API, `CR004` é inalcançável: inativar o paciente cancela os agendamentos futuros. A prova do banco foi feita por SQL.
- `clinical_documents`, `budgets` e `payments` pertencem a `postgres` no real (aplicadas pelo SQL Editor); `users` pertence a `crm_owner`. O isolamento não muda (`crm_app` não é dono de nada). O teste 139 local exige o dono `crm_owner`, que é o desenho do harness.
- Não conferido no real (por não haver acesso ao `postgres`/`crm_backup` no `.env`): contagem exata de linhas por papel e `pg_dump --enable-row-security` pelo pooler (pendência herdada).

## 3. Decisão registrada: spec 4.6 da Fase 2 (pré-checagem de conflito por intervalo)

**Adiada.** O conflito de horário é garantido pelo `EXCLUDE ex_appt_no_overlap` e o backend o traduz de `23P01` em 409; isso foi exercitado no real (seção 2), inclusive na corrida simultânea. Uma pré-checagem por intervalo seria só otimização de latência de erro e não é necessária para a correção. Reavaliar se o dashboard ou a agenda passarem a precisar de "horários livres" calculados no banco. *(Decisão do arquiteto de banco; o usuário pode vetá-la.)*

## 4. Pendências

| # | Pendência | Dono |
|---|---|---|
| 1 | Teste 143: `ddl-auto=validate` das entidades da Fase 4 | backend da Fase 4 |
| 2 | Conciliar o código `CR012` e o contrato de download com o backend | backend da Fase 4 |
| 3 | Confirmar o lock consultivo `cdoc:` atrás do pooler 6543 com o backend (a numeração concorrente de orçamentos já passou no real; a de documentos só se exercita com o backend) | backend da Fase 4 |
| 4 | Pendências de segurança fora do banco: senha do `postgres`, bucket sem limite de 10 MB/MIME, guarda da chave AES | usuário |
| 5 | Sem commit: a Fase 4 está só na árvore de trabalho | usuário |
