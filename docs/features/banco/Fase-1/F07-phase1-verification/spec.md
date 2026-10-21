# Spec — F07 Verificação, Backup e Operação da Fase 1

Complementa `docs/global/banco/Fase-1/spec.md` (seções 5, 9 e 10).

## 1. Visão técnica
- **Feature:** testes SQL/shell, rollback, backup e documentação da Fase 1.
- **Stack:** `psql`, `bash`, PostgreSQL descartável, `pg_dump`/`pg_restore`.
- **Abordagem:** estender `database/tests/run_all.sh` sem criar harness novo; cada arquivo `.sql` é autônomo, com asserções em blocos `DO` e `RAISE EXCEPTION` na falha.

## 2. Artefatos

### 2.1 Novos testes (`database/tests/`)
| Arquivo | Cobre (testes da spec global) |
|---|---|
| `session_functions.sql` | 35; funções de sessão (F01) |
| `patients_constraints.sql` | 29–31 (patients), 34, 36, 37, 38, 39, 42 |
| `clinical_roles.sql` | 32, 33, 35, 36 (prontuário e exames por papel) |
| `clinical_constraints.sql` | 29–31, 38, 40, 41, 42 (histórico, nota, exame) |
| `audit_log.sql` | 43, 44, 45 |
| `anonymization.sql` | 46–51 |
| `performance_search.sql` | 55 (massa de 10 mil/100 mil e `EXPLAIN`) |

### 2.2 Testes ampliados
`tenant_isolation.sql` (29, 30), `roles_and_privileges.sql` (17, 52, 53), `permissions_tables.sql` (18, 52), `catalog_rls_pattern.sql` (54), `schema_dictionary.sql`, `migrations_integrity.sql` (56), `backup_role.sql` e `backup_restore.sh` (58), `table_counts.sql`, `catalog_snapshot.sql`, `rollback_chain.sh` (56).

### 2.3 Rollback
`rollback/U007…U013`: `DROP` na ordem inversa (`U013` funções/guards/políticas do definer → `U012` triggers, função e `audit_log` → `U011` … `U008` → `U007` funções de sessão). `README` do rollback avisa que reverter tabelas com dados exige restauração do `pg_dump`.

### 2.4 Bootstrap no harness
`bootstrap_checks.sh` executa `00_roles_and_schema.sql` e depois `20_extensions.sql`; o stub de Supabase cria o schema `extensions`, se faltar.

### 2.5 Checklist manual do bucket (US-006)
Executado no painel do Supabase e com `curl`, sem a chave de serviço nas máquinas de teste. Cada item registra data, responsável e resultado em `database/README.md`.

| # | Verificação | Como conferir | Esperado |
|---|---|---|---|
| B1 | Bucket privado | Painel: Storage → bucket → *Public bucket* desligado | Desligado |
| B2 | Acesso anônimo negado | `curl -i <url>/storage/v1/object/public/<bucket>/<qualquer-chave>` | `400`/`404`, nunca `200` |
| B3 | Listagem anônima negada | `curl -i -H "apikey: <anon key>" <url>/storage/v1/object/list/<bucket>` com corpo `{"prefix":""}` | `401`/`403` ou lista vazia |
| B4 | Sem políticas de acesso | Painel: Storage → Policies → `storage.objects` | Nenhuma política para `anon` nem `authenticated` neste bucket |
| B5 | Limite de tamanho | Painel: configurações do bucket → *File size limit* | 10 MB |
| B6 | Tipos permitidos | Painel: *Allowed MIME types* | `image/jpeg`, `image/png`, `image/webp`, `application/pdf` (igual à `CHECK` de `patient_files.content_type`) |
| B7 | Upload fora do limite/tipo recusado | Upload de arquivo > 10 MB e de `text/plain` pelo backend (ou pela API com a chave de serviço, em ambiente de teste) | Recusado pelo Storage |
| B8 | URL assinada expira | Gerar URL com validade curta (ex.: 60 s) e abrir antes e depois | Abre antes; falha depois |
| B9 | Chave de serviço só no backend | Buscar `CRM_STORAGE_SERVICE_KEY` e a chave `service_role` no repositório, no front e em logs de deploy | Nenhuma ocorrência |
| B10 | Estrutura da chave | Objeto de teste em `<clinic_id>/<patient_id>/<uuid>` | Aceito; o registro em `patient_files` passa na `CHECK` de `storage_key` |
| B11 | Data API desligada | Painel: *Settings → Data API*; `core` fora dos schemas expostos | Desligada (herdado da Fase 0) |
| B12 | Objeto removido na anonimização | Após `anonymize_patient` em um paciente de teste, o backend apaga os objetos | Objetos ausentes; registros com `deleted_at` e `original_filename = 'anonimizado'` |

Os itens B1 a B6 e B11 são de configuração (uma vez por projeto); B7, B8, B10 e B12 dependem do backend da Fase 1 e só podem ser concluídos quando ele existir; até lá ficam registrados como "pendente do backend".

## 3. Componentes
Scripts e SQL acima; `database/README.md`; revisão de `docs/global/banco/Fase-0/{prd,spec}.md`.

## 4. Lógica (ordem do `run_all.sh`)
1. Stubs do Supabase → bootstrap (`00_`, `10_`, `20_`).
2. `scripts/apply_migrations.sh` V001–V013 como `crm_owner` (cada uma em uma transação).
3. `tests/*.sql` na ordem alfabética; cada um cria suas clínicas/usuários e limpa o que criou (ou usa transação revertida).
4. `backup_restore.sh` (58) e `performance_search.sql` (isolado e por último entre os `.sql`, pois carrega 100 mil linhas).
6. `rollback_chain.sh`, destrutivo, sempre por último.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Teste deixa lixo em outro teste | Cada arquivo usa ids próprios (uuids fixos por arquivo) e `ROLLBACK` quando possível |
| Teste de retenção sem relógio | O `crm_owner` não serve (o `FORCE RLS` o deixa sem linhas). O teste insere, como `crm_app`, registros **já com** `updated_at`/`noted_at` antigos: `updated_at` só é recalculado em `UPDATE`, então o `INSERT` aceita o valor explícito |
| `pg_trgm` ausente | O harness falha cedo e aponta o plano B |
| Massa de desempenho lenta | Arquivo separado, gerada com `generate_series` e `ANALYZE` antes do `EXPLAIN` |
| Restauração com contagem diferente | `backup_restore.sh` falha com a tabela divergente |

## 6. Segurança e desempenho
- Senhas dos papéis vêm do harness (geradas, descartáveis); nada em repositório.
- Testes rodam só em banco descartável; o `run_all.sh` recusa host que não seja o configurado para descarte.
- Tempo total do harness: meta ≤ 10 minutos.
