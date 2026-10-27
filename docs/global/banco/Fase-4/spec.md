# Spec Global (Banco) — Fase 4: Documentos e Dashboard

**Base:** `docs/global/banco/Fase-4/prd.md` (decisões D1–D13), `docs/global/banco/Fase-{0,1,2,3}/spec.md`, `database/` (V001–V030, bootstrap, rollback, testes) e `back-java/`.
**Natureza:** especificação de contrato. Os trechos de SQL ilustram o contrato; as migrations versionadas (`V031`–`V035`) e os testes são do `dev-engineer-postgres`.
**Premissas lidas no código real (não assumidas):**
- `core.write_audit()` (V012) grava `TG_OP` como ação: não sabe dizer `VOID`, `ANONYMIZE` nem `DOWNLOAD`. A Fase 4 precisa de função própria.
- A `V029` é a última definição de `core.anonymize_patient` (a V030 não a toca). A Fase 4 a substitui por inteiro.
- `core.users` tem **um dentista por clínica** (`ux_users_one_dentist`). O CRO é, na prática, o CRO do dentista da clínica.
- `users_upd` só filtra por `clinic_id`: a regra "só o próprio dentista edita o CRO" não cabe em RLS (política não enxerga colunas); vai em trigger.
- A recepção **lê `budgets`** (C2 da Fase 3). Ver S16: a restrição do "faturamento do mês" ao dentista é do backend, não do banco.
- O harness vai até o teste 124 (`migrations_phase3.sh`). A Fase 4 começa no próximo número livre (≥ 125; o dev confere o maior em uso).

---

## 1. Technical Overview

- **Feature:** `core.clinical_documents` (atestado, receita, encaminhamento), CRO em `core.users`, auditoria com `VOID`/`DOWNLOAD`, anonimização e índices do dashboard.
- **Tech Stack:** PostgreSQL 16+ (Supabase como Postgres), schema `core`, `plpgsql`, RLS, triggers, `SECURITY DEFINER` com `search_path` fixo. Sem extensão nova.
- **Architecture Approach:** tabela imutável sob RLS por clínica **e por papel**; o banco reforça numeração, guardas de paciente, CRO do emissor, imutabilidade e a trilha de auditoria; o backend cifra o texto, calcula o `sha256`, gera o PDF e calcula as janelas de data do dashboard. O dashboard não ganha objeto de resumo, só índice.

### 1.1 Decisões de spec (além de D1–D13)

| # | Decisão | Valor e motivo |
|---|---|---|
| S1 | Schema | `core` (mantém A1). Sem schema `documents`: as FKs compostas, as políticas e a auditoria já estão em `core` |
| S2 | CRO | `users.cro_number varchar(15)` (só dígitos) e `users.cro_state varchar(2)` (UF em maiúsculas), os dois ou nenhum, só para `DENTIST_ADMIN`. **Trigger `guard_user_cro`** impede que outro usuário altere o CRO (`42501`); a RLS de `users` não muda. O `GRANT UPDATE` de `users` já é da tabela inteira: a guarda de coluna é o trigger |
| S3 | Numeração | `number integer` por `(clinic_id, document_type)`; `pg_advisory_xact_lock(hashtextextended('cdoc:' \|\| clinic_id \|\| ':' \|\| document_type, 0))` + `max()+1` no `BEFORE INSERT` (padrão da V024, chave diferente). `UNIQUE (clinic_id, document_type, number)` |
| S4 | `sha256` | `varchar(64)` hex minúsculo, calculado **pelo backend** sobre `v1\|document_type\|patient_id\|issued_on\|rest_days\|target_specialty\|content` (campos nulos = vazio; `content` = Base64 cifrado). O `number` fica **fora** (nasce no banco). O banco só confere o formato; o backend verifica ao reabrir. Sem `pgcrypto`: o hash é do payload cifrado, nunca do texto claro |
| S5 | Tamanho do `content` | `varchar(22000)`. Conta: 4.000 caracteres claros × 4 bytes (UTF-8) = 16.000 B + envelope (versão da chave, IV 12 B, tag 16 B) ≈ 16.030 B → Base64 ≈ 21.400 caracteres. O dev **reconfere o envelope em `shared/crypto`** antes de fixar; se o envelope for maior, ajusta o teto (migration ainda não aplicada) |
| S6 | Imutabilidade | `BEFORE UPDATE` aceita **só** duas transições: **anular** (papel `DENTIST_ADMIN`, invoker) e **anonimizar** (`current_user = 'crm_definer'` **e** `core.anonymizing = 'on'`; o GUC sozinho é forjável, lição do QA da Fase 3). Qualquer outra mudança falha com `23514`. `BEFORE DELETE` e `BEFORE TRUNCATE` bloqueiam todos os papéis (padrão de `payments`) |
| S7 | Anular | Backend atualiza só `voided_at`, `voided_by`, `void_reason`, `version`. `GRANT UPDATE (voided_at, voided_by, void_reason, version)` ao `crm_app`. **Mapeamento JPA:** `@DynamicUpdate` ou demais colunas `updatable=false`; senão o `UPDATE` do Hibernate toca coluna sem privilégio (`42501`) |
| S8 | Auditoria | Função própria `core.write_document_audit()` (`SECURITY DEFINER`): `INSERT`→`INSERT`; `UPDATE` com `voided_at` nulo→valor→`VOID`; `UPDATE` com `anonymized_at` nulo→valor→`ANONYMIZE`; qualquer outro `UPDATE` (impossível pela S6) falha. A linha `patients/ANONYMIZE` da V013 continua |
| S9 | Download | `core.record_document_download(p_document_id uuid) RETURNS void`, `SECURITY DEFINER`, `EXECUTE` só ao `crm_app`. Grava `DOWNLOAD` em `audit_log`. Sem deduplicação: cada chamada é um evento |
| S10 | Retenção LGPD | Documentos **não** entram no prazo de retenção do prontuário (`clinical_retention_period`), como orçamento e agenda. Emitir documento acompanha uma nota clínica, que já conta. *(Contestável: ver pergunta 1.)* |
| S11 | Anulado | Continua `SELECT`-ável e baixável; a marca no PDF é do backend |
| S12 | Faturamento por papel | Sem objeto novo no banco. A restrição do KPI "faturamento do mês" ao `DENTIST_ADMIN` é regra do **backend** (S16 abaixo) |
| S13 | Índices do dashboard | `ix_appt_completed_patient` criado por padrão; os demais só se o teste de desempenho reprovar (ver 2.6) |
| S14 | Códigos de erro | Novo: **`CR012`** (emissor sem CRO). Reaproveitados: `CR003`, `CR005`, `42501`, `28000`, `P0002`, `23514`, `23503`, `23505`. Anular duas vezes ou mudar campo imutável = `23514` |
| S15 | Seed | Nenhum |
| S16 | Achado do PRD | A D11 dizia que `budgets`/`payments` já impedem a recepção de ver o faturamento. **Só `payments` e `installments` são vedados**; a recepção lê orçamentos e totais (C2), logo soma o mês pelo backend se quiser. O corte do KPI por papel é produto/UX no backend; **não é fronteira de segurança do banco**. Risco aceito, registrado também no PRD (D11) |

---

## 2. Data Models & Schema

### 2.1 Migrations

| Migration | Rollback | Conteúdo | Depende |
|---|---|---|---|
| `V031__core_users_cro.sql` | `U031` | colunas, `CHECK`s e trigger `guard_user_cro` em `users` | V004 |
| `V032__core_clinical_documents.sql` | `U032` | tabela, triggers, RLS, grants, índices | V008, V015, V004, V031 |
| `V033__core_clinical_documents_audit.sql` | `U033` | `ck_audit_table`/`ck_audit_action`, `write_document_audit`, `record_document_download` | V012, V032 |
| `V034__core_clinical_documents_anonymization.sql` | `U034` | grants/políticas do `crm_definer`; `anonymize_patient` estendida | V029, V032, V033 |
| `V035__core_dashboard_indexes.sql` | `U035` | `ix_appt_completed_patient` (+ condicionais) | V015, V008 |

`MIGRATIONS_EXPECTED=35` (harness e `migrations_phase2.sh`). Todas transacionais, uma mudança coesa por arquivo, aditivas. `CREATE INDEX CONCURRENTLY` fora de transação só se o volume pedir (ver 2.6).

### 2.2 `V031` — CRO em `core.users`

```sql
ALTER TABLE core.users ADD COLUMN cro_number varchar(15);
ALTER TABLE core.users ADD COLUMN cro_state  varchar(2);

ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_pair  CHECK ((cro_number IS NULL) = (cro_state IS NULL));
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_role  CHECK (cro_number IS NULL OR role = 'DENTIST_ADMIN');
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_fmt   CHECK (cro_number IS NULL OR cro_number ~ '^[0-9]{1,15}$');
ALTER TABLE core.users ADD CONSTRAINT ck_users_cro_state CHECK (cro_state IS NULL OR cro_state IN
  ('AC','AL','AP','AM','BA','CE','DF','ES','GO','MA','MT','MS','MG','PA','PB','PR','PE','PI','RJ','RN','RS','RO','RR','SC','SP','SE','TO'));
```

`core.guard_user_cro()` (`BEFORE UPDATE`, invoker, `search_path = pg_catalog, pg_temp`): se `NEW.cro_number IS DISTINCT FROM OLD.cro_number OR NEW.cro_state IS DISTINCT FROM OLD.cro_state` e `core.current_user_id() IS DISTINCT FROM NEW.id` → `RAISE ... USING ERRCODE = '42501'`. `REVOKE EXECUTE ... FROM PUBLIC`. Sem `GRANT` novo (o `UPDATE` de tabela da V004 cobre). `INSERT` com CRO é aceito pelas `CHECK`s (o registro da clínica cria o dentista sem CRO; o cadastro de recepcionista com CRO é barrado por `ck_users_cro_role`).

*Atenção:* `find_user_for_login` e demais funções da V006 selecionam colunas explícitas: não mudam. `forbid_immutable_columns` de `users` (`id`, `clinic_id`, `role`, `created_at`) não muda.

### 2.3 `V032` — `core.clinical_documents`

Dicionário de dados:

| Coluna | Tipo | Nulo | Default | Observação |
|---|---|---|---|---|
| `id` | `uuid` | não | `gen_random_uuid()` | PK |
| `clinic_id` | `uuid` | não | | FK `core.clinics` `RESTRICT`; tenant |
| `patient_id` | `uuid` | não | | FK composta `(patient_id, clinic_id)` → `patients` `RESTRICT` |
| `appointment_id` | `uuid` | sim | | FK composta `(appointment_id, patient_id, clinic_id)` → `appointments` (`ux_appt_id_patient_clinic`) `RESTRICT`; `MATCH SIMPLE` (nulo = sem checagem) |
| `document_type` | `varchar(15)` | não | | `CERTIFICATE`, `PRESCRIPTION`, `REFERRAL` |
| `number` | `integer` | não | trigger | atribuído pelo banco; o cliente é sobrescrito |
| `issued_on` | `date` | não | | data civil no fuso da clínica (backend) |
| `rest_days` | `smallint` | sim | | 1–90, só `CERTIFICATE` |
| `target_specialty` | `varchar(60)` | sim | | só `REFERRAL` |
| `content` | `varchar(22000)` | sim | | Base64 do AES-256-GCM; nulo só se anonimizado |
| `sha256` | `varchar(64)` | não | | hex minúsculo (S4) |
| `voided_at` | `timestamptz` | sim | | |
| `voided_by` | `uuid` | sim | | FK composta `(voided_by, clinic_id)` → `users` `RESTRICT` |
| `void_reason` | `varchar(200)` | sim | | texto livre em claro; zerado na anonimização |
| `anonymized_at` | `timestamptz` | sim | | preenchido só pela anonimização |
| `created_by` | `uuid` | não | | FK composta `(created_by, clinic_id)` → `users` `RESTRICT`; `= app.user_id` |
| `created_at` / `updated_at` | `timestamptz` | não | `now()` | `updated_at` por trigger |
| `version` | `bigint` | não | `0` | |

Constraints:

```sql
CONSTRAINT ck_cdoc_type     CHECK (document_type = 'CERTIFICATE' OR document_type = 'PRESCRIPTION' OR document_type = 'REFERRAL'),
CONSTRAINT ck_cdoc_number   CHECK (number >= 1),
CONSTRAINT ck_cdoc_sha256   CHECK (sha256 ~ '^[0-9a-f]{64}$'),
CONSTRAINT ck_cdoc_rest     CHECK (rest_days IS NULL OR (document_type = 'CERTIFICATE' AND rest_days BETWEEN 1 AND 90)),
CONSTRAINT ck_cdoc_special  CHECK (target_specialty IS NULL OR (document_type = 'REFERRAL' AND char_length(btrim(target_specialty)) >= 2)),
-- campos obrigatórios por tipo (dispensados depois de anonimizar)
CONSTRAINT ck_cdoc_required CHECK (anonymized_at IS NOT NULL
    OR ((document_type <> 'CERTIFICATE' OR rest_days IS NOT NULL)
    AND (document_type <> 'REFERRAL'    OR target_specialty IS NOT NULL))),
CONSTRAINT ck_cdoc_content  CHECK (
    (anonymized_at IS NULL     AND content IS NOT NULL AND char_length(content) >= 40 AND content ~ '^[A-Za-z0-9+/]+={0,2}$')
 OR (anonymized_at IS NOT NULL AND content IS NULL AND rest_days IS NULL AND target_specialty IS NULL AND void_reason IS NULL)),
CONSTRAINT ck_cdoc_void     CHECK (
    (voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL)
 OR (voided_at IS NOT NULL AND voided_by IS NOT NULL AND (void_reason IS NULL OR char_length(btrim(void_reason)) >= 1))),
CONSTRAINT ux_cdoc_number   UNIQUE (clinic_id, document_type, number)
```

Predicados com `OR` de igualdades (lição do `pg_dump` que reinterpreta `IN` em índices/exclusões; aqui por consistência com as demais `CHECK`s de tipo).

Índices:

| Índice | Definição | Atende |
|---|---|---|
| `ix_cdoc_patient` | `(clinic_id, patient_id, created_at DESC, id DESC)` | lista do paciente por keyset, FK composta do paciente, anonimização por paciente |
| `ix_cdoc_appointment` | `(clinic_id, appointment_id) WHERE appointment_id IS NOT NULL` | FK composta da consulta |

`created_by` e `voided_by`: sem índice próprio, pois `users` nunca é apagado (`RESTRICT` nunca é avaliado). O dev confere o critério vigente em `performance_indexes.sql`/`catalog_rls_pattern.sql` e acrescenta se o teste exigir.

**Triggers** (todas as funções `REVOKE EXECUTE ... FROM PUBLIC`, `search_path = pg_catalog, pg_temp`):

| Trigger | Quando | Função e regra |
|---|---|---|
| `trg_cdoc_before_insert` | `BEFORE INSERT`, invoker | ver 4.1 |
| `trg_cdoc_forbid_immutable` | `BEFORE UPDATE` | `forbid_immutable_columns('id','clinic_id','document_type','number','patient_id','appointment_id','issued_on','sha256','created_by','created_at')` |
| `trg_cdoc_guard_update` | `BEFORE UPDATE`, invoker | ver 4.2 |
| `trg_cdoc_set_updated_at` | `BEFORE UPDATE` | `core.set_updated_at()` |
| `trg_cdoc_no_delete` | `BEFORE DELETE` por linha | `RAISE ... '42501'`, qualquer papel |
| `trg_cdoc_no_truncate` | `BEFORE TRUNCATE` por comando | idem |
| `trg_cdoc_audit` | `AFTER INSERT OR UPDATE` | `write_document_audit()`; **criado na V033 (F03)**, não na V032 |

### 2.4 RLS e GRANTs de `clinical_documents`

`ENABLE` e `FORCE ROW LEVEL SECURITY`. Funções de sessão sempre como `(SELECT ...)`.

| Política | Papel | Condição |
|---|---|---|
| `cdoc_sel` (`SELECT`) | `crm_app` | `clinic_id = core.current_clinic_id() AND (SELECT core.current_app_role()) = 'DENTIST_ADMIN'` |
| `cdoc_ins` (`INSERT`) | `crm_app` | mesmo tenant e papel, `AND created_by = (SELECT core.current_user_id())` |
| `cdoc_upd` (`UPDATE`) | `crm_app` | `USING` e `WITH CHECK` com tenant e papel (a transição é validada pelo trigger) |
| *(sem política de `DELETE`)* | | negado |
| `cdoc_backup_sel` | `crm_backup` | `USING (true)` |
| `cdoc_definer_sel` | `crm_definer` | `clinic_id = core.current_clinic_id()`; criada na **V033** com `SELECT (id, clinic_id, anonymized_at)` para o download; a V034 amplia o `SELECT` |
| `cdoc_definer_upd` | `crm_definer` | idem, para `UPDATE`; criada na **V034** |

GRANTs: `crm_app`: `SELECT, INSERT`, `UPDATE (voided_at, voided_by, void_reason, version)`. `crm_backup`: `SELECT`. `crm_definer` (V034): `SELECT`, `UPDATE (content, rest_days, target_specialty, void_reason, anonymized_at, version)`. Nada a `PUBLIC`; sem `GRANT ALL`, sem `DELETE`/`TRUNCATE`.

### 2.5 Matriz papel × tabela × operação

| Tabela / objeto | `DENTIST_ADMIN` | `RECEPTIONIST` | `crm_backup` | `crm_definer` |
|---|---|---|---|---|
| `clinical_documents` | `SELECT`, `INSERT`, anular (`UPDATE` restrito) | **nada** (0 linhas; `INSERT` → `42501`) | `SELECT` | `SELECT`, `UPDATE` das 6 colunas (só dentro da anonimização) |
| `users.cro_*` | altera o **próprio** | não tem CRO (`ck_users_cro_role`) | `SELECT` | — |
| `record_document_download()` | executa | `42501` | — | dono |
| `audit_log` | `SELECT` (já era) | nada | `SELECT` | `INSERT` |
| `patients`, `appointments` (dashboard) | leem | leem | `SELECT` | — |
| `budgets` (dashboard) | lê | **lê** (C2; S16) | `SELECT` | — |
| `installments`, `payments` | leem | **nada** | `SELECT` | `SELECT` em `installments` |

### 2.6 `V035` — índices do dashboard

Consultas de referência (parâmetros `:ini`/`:fim` e `:tz` calculados pelo backend no fuso da clínica; `clinic_id` explícito além da RLS):

```sql
-- K1 total de pacientes
SELECT count(*) FROM core.patients WHERE clinic_id = :c AND active = true;
-- K2 consultas hoje
SELECT count(*) FROM core.appointments
 WHERE clinic_id = :c AND starts_at >= :dia_ini AND starts_at < :dia_fim
   AND status <> 'CANCELED' AND status <> 'NO_SHOW';
-- K3 faturamento do mês (competência, Fase 3)
SELECT COALESCE(sum(total_amount), 0) FROM core.budgets
 WHERE clinic_id = :c AND status = 'APPROVED' AND decided_at >= :mes_ini AND decided_at < :mes_fim;
-- K4 taxa de retorno (D10): :mes_ini/:mes_fim e :ano_ini = mes_ini - 12 meses
WITH m AS (SELECT DISTINCT patient_id FROM core.appointments
            WHERE clinic_id = :c AND status = 'COMPLETED' AND starts_at >= :mes_ini AND starts_at < :mes_fim)
SELECT count(*) AS atendidos,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM core.appointments p
                                       WHERE p.clinic_id = :c AND p.patient_id = m.patient_id AND p.status = 'COMPLETED'
                                         AND p.starts_at >= :ano_ini AND p.starts_at < :mes_ini)) AS retornos
  FROM m;
-- K5 gráfico semanal (segunda a domingo; :sem_ini/:sem_fim = [segunda 00:00, próxima segunda) na clínica)
SELECT (starts_at AT TIME ZONE :tz)::date AS dia, status, count(*)
  FROM core.appointments WHERE clinic_id = :c AND starts_at >= :sem_ini AND starts_at < :sem_fim
 GROUP BY 1, 2;
```

| KPI | Índice esperado | Plano esperado |
|---|---|---|
| K1 | `ix_patients_clinic_name` (existente) | `Index Scan`/`Bitmap` por `clinic_id`, filtro `active` |
| K2, K5 | `ix_appt_clinic_start` (existente) | `Index Scan` por faixa de `starts_at` |
| K3 | `ix_budgets_decided` (existente, parcial `APPROVED`) | `Index Scan` por faixa de `decided_at` |
| K4 | existentes (`ix_appt_clinic_start` para o mês, `ix_appt_patient` para a sonda) + **`ix_appt_completed_patient`** | sonda `Index Only Scan` por `(clinic_id, patient_id, starts_at)` |

Criado por padrão em `V035`:

```sql
CREATE INDEX ix_appt_completed_patient ON core.appointments (clinic_id, patient_id, starts_at) WHERE status = 'COMPLETED';
```

Condicional (só entra na `V035` se o teste `phase4_performance` reprovar a meta de 50 ms antes da aplicação): `ix_patients_clinic_active ON core.patients (clinic_id) WHERE active = true` para K1. Nenhum índice novo em `budgets`. `EXPLAIN (ANALYZE)` com RLS ativa e papel `crm_app` fica registrado no teste. Se a tabela real passar de 1 milhão de linhas, o índice é criado à mão com `CONCURRENTLY` (`apply_migrations.sh` usa `--single-transaction`).

### 2.7 Auditoria (`V033`)

```sql
ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (
  table_name IN ('patients','medical_histories','clinical_notes','patient_files','appointments',
                 'budgets','installments','payments','budget_documents','clinical_documents'));
ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_action;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_action CHECK (
  action IN ('INSERT','UPDATE','DELETE','ANONYMIZE','VOID','DOWNLOAD'));
```

*Confirmar* a lista vigente de `ck_audit_table` na `V028`/`V030` antes de recriar (a lista acima segue a V028; o dev lê o catálogo e acrescenta só `clinical_documents`). O padrão da concessão temporária de `EXECUTE` em função de dono `crm_definer` (`SET LOCAL ROLE crm_definer`, V020/V028) vale para criar o trigger e para criar as funções `SECURITY DEFINER` (precisam de `GRANT CREATE ON SCHEMA core TO crm_definer` temporário, V029).

---

## 3. Component Architecture

Não há UI. Objetos de banco novos:

| Objeto | Tipo | Responsabilidade |
|---|---|---|
| `core.clinical_documents` | tabela | registro imutável do documento emitido |
| `core.guard_user_cro()` | trigger (invoker) | só o próprio usuário altera o CRO |
| `core.clinical_documents_before_insert()` | trigger (invoker) | papel, guardas de paciente, CRO do emissor, número, limpeza de campos de sistema |
| `core.guard_clinical_document_update()` | trigger (invoker) | só anular ou anonimizar |
| `core.clinical_documents_block()` | trigger | bloqueia `DELETE` e `TRUNCATE` |
| `core.write_document_audit()` | trigger (`SECURITY DEFINER`) | `INSERT`/`VOID`/`ANONYMIZE` no `audit_log` |
| `core.record_document_download(uuid)` | função (`SECURITY DEFINER`) | `DOWNLOAD` no `audit_log` |
| `core.anonymize_patient(uuid)` | função (`SECURITY DEFINER`, substituída) | passa a anonimizar documentos |

---

## 4. Core Logic & Algorithms

### 4.1 Emissão (`BEFORE INSERT`)

1. Se `core.current_app_role()` ≠ `'DENTIST_ADMIN'`: `42501` (a negativa vem **antes** de qualquer outra para a recepção não receber `CR012` no lugar de permissão).
2. `SELECT active, anonymized_at ... FROM core.patients WHERE id = NEW.patient_id AND clinic_id = NEW.clinic_id FOR KEY SHARE` (espera uma anonimização em curso e relê o paciente já anonimizado). Anonimizado → `CR003`; inativo → `CR005`; sem linha → segue (a FK responde `23503`).
3. `SELECT cro_number FROM core.users WHERE id = NEW.created_by AND clinic_id = NEW.clinic_id`: linha existente com `cro_number IS NULL` → **`CR012`**. Sem linha → segue (FK).
4. `pg_advisory_xact_lock(hashtextextended('cdoc:' || clinic_id || ':' || document_type, 0))`; `NEW.number := COALESCE((SELECT max(number) FROM core.clinical_documents WHERE clinic_id = NEW.clinic_id AND document_type = NEW.document_type), 0) + 1`.
5. `NEW.voided_at/voided_by/void_reason/anonymized_at := NULL` (documento nasce válido e identificado).
6. `RETURN NEW`. A política `cdoc_ins` e as `CHECK`s respondem depois.

A V030 teve de acrescentar a guarda de paciente anonimizado ao `budget_documents` por esquecimento; aqui a guarda já nasce no `INSERT` (passo 2). Não é necessário reaproveitar `forbid_child_of_anonymized`.

### 4.2 Atualização (`BEFORE UPDATE`)

Depois de `forbid_immutable_columns`:
- **Anulação:** `OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL`, papel `DENTIST_ADMIN`, `NEW.voided_by = core.current_user_id()`, e `content`, `rest_days`, `target_specialty`, `anonymized_at` inalterados (`void_reason` pode ser preenchido). Senão `23514`.
- **Anonimização:** `current_user = 'crm_definer' AND current_setting('core.anonymizing', true) = 'on'`, `OLD.anonymized_at IS NULL`, `NEW.anonymized_at IS NOT NULL`, `content/rest_days/target_specialty/void_reason` → `NULL`, `voided_*` inalterados. Senão `23514`.
- Qualquer outro `UPDATE` (inclusive anular um anulado, ou `UPDATE` só de `version`) → `23514`.
`version` e `updated_at` livres nas duas transições.

### 4.3 Auditoria (`write_document_audit`)

`app.user_id` obrigatório (`28000` sem ele, a operação reverte). `INSERT` → `INSERT`. `UPDATE`: anulação → `VOID`; anonimização → `ANONYMIZE` (uma linha por documento, além da linha `patients/ANONYMIZE`). Grava só `clinic_id`, `user_id`, `table_name='clinical_documents'`, `record_id`, `action`, `occurred_at`. Nenhum valor.

### 4.4 Download (`record_document_download`)

1. Papel ≠ `DENTIST_ADMIN` → `42501`. Sem clínica ou usuário na sessão → `28000`.
2. `SELECT anonymized_at FROM core.clinical_documents WHERE id = p_document_id` (a política do definer limita à clínica da sessão). Sem linha → `P0002`. Anonimizado → `CR003`.
3. `INSERT INTO core.audit_log (clinic_id, user_id, table_name, record_id, action) VALUES (current_clinic, current_user_id, 'clinical_documents', p_document_id, 'DOWNLOAD')`.
4. O backend chama a função **antes de devolver o PDF e na mesma transação**: se a gravação falha, não entrega.

### 4.5 Anonimização (`V034`)

`CREATE OR REPLACE` sobre o corpo exato da V029 (o dev diverge-o contra a função **viva** do banco antes de copiar). Acrescenta, depois de ligar a flag `core.anonymizing` e antes de anonimizar `patients`:

```sql
UPDATE core.clinical_documents
   SET content = NULL, rest_days = NULL, target_specialty = NULL, void_reason = NULL,
       anonymized_at = v_now, version = version + 1
 WHERE clinic_id = v_clinic AND patient_id = p_patient_id AND anonymized_at IS NULL;
```

Precedência dos erros e os cálculos de retenção ficam iguais (S10). Dono e `EXECUTE` do `crm_app` permanecem; `GRANT CREATE ON SCHEMA core TO crm_definer` temporário como na V029.

### 4.6 Sequência do backend (contrato)

```
POST emitir:   BEGIN; set_config(clinic, role, user)  →  cifra content, calcula sha256
               INSERT clinical_documents (sem number/voided_*/anonymized_at)  →  relê a linha (number)  →  COMMIT
GET pdf:       BEGIN; set_config(...)  →  SELECT  →  record_document_download(id)  →  gera PDF (marca ANULADO se voided_at)  →  COMMIT
POST anular:   BEGIN; set_config(...)  →  UPDATE voided_at, voided_by, void_reason (version otimista)  →  COMMIT
```

Transação em `READ COMMITTED`. Mapeamento: `number`, `voided_*` (exceto no UPDATE de anulação), `anonymized_at` e `updated_at` como não inseríveis; `UPDATE` só nas colunas do GRANT (S7).

---

## 5. Error Handling & Edge Cases

| Cenário | Resultado | Tradução sugerida (backend) |
|---|---|---|
| Recepção insere/lê/anula documento | `INSERT` → `42501`; `SELECT`/`UPDATE` → 0 linhas | 403/404 |
| Emissor sem CRO | `CR012` | 409 (pedir cadastro do CRO) |
| Paciente anonimizado / inativo | `CR003` / `CR005` | 409 |
| `CERTIFICATE` sem `rest_days`, `REFERRAL` sem especialidade, tipo trocado | `23514` | 422 |
| `content` fora do Base64 ou > teto | `23514` | 422 (bug do cliente) |
| `appointment_id` de outro paciente/clínica | `23503` | 422 |
| Anular um documento já anulado, ou outro `UPDATE` | `23514` | 409 |
| `UPDATE` de coluna sem `GRANT` (mapeamento JPA errado) | `42501` | 500 (bug) |
| `DELETE`/`TRUNCATE` | `42501` | — |
| Duas emissões concorrentes do mesmo tipo | serializadas pelo lock; números consecutivos distintos; o `ROLLBACK` devolve o número (`max()+1`), sem buracos nem repetição | — |
| Emissão concorrente com anonimização do paciente | `FOR KEY SHARE` espera a anonimização; relê e responde `CR003` | 409 |
| `record_document_download` com documento de outra clínica | `P0002` (a RLS do definer oculta) | 404 |
| `record_document_download` de documento anonimizado | `CR003` | 409/410 |
| `app.user_id` ausente em emissão ou anulação | `28000` (auditoria exige) | 500 |
| Outro usuário altera o CRO | `42501` | 403 |
| CRO duplicado entre clínicas | permitido (sem `UNIQUE`; um dentista por clínica e o CRO não identifica a clínica) | — |
| Fuso: `issued_on` e janelas do dashboard | calculados pelo backend; o banco não decide | — |

**Falhas conhecidas e aceitas:** o banco confia no `app.role` informado (risco 1 da Fase 1); `sha256` não é conferido pelo banco; `void_reason` fica em claro; a recepção soma o faturamento via `budgets` (S16); reimpressão usa o cabeçalho atual (PRD, risco 1).

---

## 6. Security & Performance

### 6.1 Segurança

- RLS `ENABLE` + `FORCE` com política por operação e por papel; `crm_app` não é dono, sem `BYPASSRLS`, sem `DROP/TRUNCATE/ALTER`.
- Funções `SECURITY DEFINER` (`write_document_audit`, `record_document_download`, `anonymize_patient`): dono `crm_definer`, `search_path = pg_catalog, pg_temp`, sem SQL dinâmico, `EXECUTE` revogado de `PUBLIC` e concedido só ao necessário; todas as consultas do definer passam por política com `clinic_id = current_clinic_id()` (nenhum `USING (true)`).
- A exceção de imutabilidade na anonimização exige `current_user = 'crm_definer'` e a flag (S6).
- Risco herdado: `crm_app` pode tomar o lock consultivo `cdoc:<clinic>:<tipo>` de outra clínica (negação de serviço); aceito, como na numeração do orçamento (projeto de portfólio).
- Logs: o conteúdo é cifrado na aplicação; `void_reason` em claro nunca vai a log; parâmetros sensíveis não aparecem (`log_statement` do Supabase fora do repositório).
- Chave AES fora do banco; perder `CRM_AES_KEYS` = perder o conteúdo.

### 6.2 Desempenho (metas, Docker local, RLS ativa, `crm_app`)

| Operação | Meta p95 | Massa de teste |
|---|---|---|
| `INSERT` de documento (com número e guardas) | ≤ 20 ms | 100 mil documentos |
| Lista do paciente (keyset, 20 itens) | ≤ 10 ms | idem |
| `record_document_download` | ≤ 10 ms | idem |
| K1 a K5 do dashboard | ≤ 50 ms cada | 100 mil agendamentos, 100 mil parcelas e pagamentos, 20 mil pacientes na clínica |
| Anonimização de paciente com 50 documentos | ≤ 100 ms | |

Sem `Seq Scan` nas tabelas grandes nos planos de K2–K5 (predicados seletivos, ver lição da Fase 3).

---

## 7. Testes (para o `dev-engineer-postgres`; numeração ≥ 125)

Todos rodam em `tests/run_all.sh`; semente em `tests/support/phase4_seed.sql` (duas clínicas, dentista com e sem CRO, recepcionista, pacientes ativo/inativo/anonimizado).

| Arquivo | Cobre |
|---|---|
| `users_cro.sql` | `CHECK`s do CRO (UF, formato, par, recepcionista com CRO); outro usuário altera CRO → `42501`; o próprio altera |
| `clinical_documents.sql` | constraints por tipo, `CHECK` de conteúdo, FKs compostas, `appointment_id` de outro paciente, `CR012`, `CR003`, `CR005`, recepção → `42501` |
| `clinical_documents_immutable.sql` | só anular/anonimizar; anular duas vezes; `DELETE`/`TRUNCATE` negados a todos (incl. dono); `UPDATE` de coluna sem `GRANT` |
| `documents_numbering.sql` + `documents_concurrency.sh` | numeração sem repetição com duas conexões por clínica e tipo; tipos independentes; buraco após `ROLLBACK` |
| `audit_documents.sql` | `INSERT`/`VOID`/`ANONYMIZE`/`DOWNLOAD`; função de download: recepção, clínica errada, inexistente, anonimizado; `audit_log` segue só de inserção |
| `anonymize_documents.sql` | colunas zeradas, linha mantida, `anonymized_at`, auditoria, sem bloqueio; função forjando só o GUC não anonimiza |
| `phase4_isolation.sql` | duas clínicas e sem `app.clinic_id` → 0 linhas; `INSERT` com `clinic_id` alheio falha |
| `phase4_permissions.sql` | matriz 2.5; `crm_app` sem `DROP/TRUNCATE/ALTER`; `catalog_rls_pattern` verde |
| `phase4_performance.sql` | metas 6.2, `EXPLAIN` registrado (sem `Seq Scan`) |
| `migrations_phase4.sh` | migrations do zero, reversões `U035`→`U031`, função `anonymize_patient` restaurada (V029) |
| `schema_dictionary.sql`, `backup_restore.sh`, `restore_check` | ampliados com a tabela e as colunas novas; `trg_cdoc_no_truncate`/`no_delete` desligados só nos scripts de limpeza e de restauração |

**Regras de rollback:** `U035` derruba o índice; `U034` restaura a função da V029 e remove grants/políticas do definer; `U033` **falha atomicamente** se `audit_log` já tem `clinical_documents`, `VOID` ou `DOWNLOAD` (o log é só de inserção, como a `U028`); `U032` e `U031` são **destrutivas por convenção do projeto** (como a `U025`): o dono (`crm_owner`) não enxerga linhas sob RLS `FORCE`, então elas não conseguem conferir se há documentos ou CRO gravados; com dados, restaure do `pg_dump` em vez de reverter (só a `U033` falha de forma atômica, porque o `ADD CONSTRAINT` do log valida as linhas existentes).

**Operação (manual no Supabase real):** aplicar como `crm_owner` com `apply_migrations.sh 35` (porta 5432), conferir no SQL Editor (colunas de `users`, RLS ativa e FORCE em `clinical_documents`, função de download, constraint do `audit_log`). Backup (`backup.sh`) e `restore_check.sh` ampliados. Pendências herdadas (D13): `23P01`/`CR004` no real, smoke da Fase 3 pós-V030, V030 documentada em spec e README.

---

## 8. Integração com o backend (resumo do contrato)

- `ddl-auto=validate`; Flyway desligado. Nomes e tipos do dicionário (2.3) são o contrato das entidades.
- `User`: mapear `cro_number`/`cro_state`; só o dentista os edita no próprio perfil.
- `ClinicalDocument`: `number` não inserível; `voided_*` e `anonymized_at` só pelas rotas de anular/anonimizar; `@DynamicUpdate`; leitura de PDF sempre via `record_document_download`.
- `anonymize_patient` (rota existente): sem nada a apagar no Storage para documentos.
- Códigos novos para o `GlobalExceptionHandler`: `CR012`; `23514` de transição inválida do documento → 409.
- Dashboard: o backend aplica o corte por papel de K3 e calcula todas as janelas (fuso da clínica, semana segunda a domingo).

## 9. Perguntas abertas

> **Respondidas em 2026-10-27:** (1) **não**, documentos ficam fora da retenção; (2) **sim**, o corte do faturamento é só do backend; (3) índice de K1 **só se o teste reprovar**; (4) **sim**, `created_by`/`voided_by` sem índice. Mantidas abaixo como registro.

1. **Retenção (S10):** documentos entram no prazo de retenção do prontuário (`CR002`)? *(Padrão: não, como orçamento e agenda. Se for sim, `anonymize_patient` inclui `max(created_at)` de `clinical_documents` no cálculo de `v_last`.)*
2. **Faturamento do mês e recepção (S16):** aceitar que o corte por papel seja só do backend? *(Padrão: sim, a recepção já lê orçamentos e totais. A alternativa é uma função `SECURITY DEFINER` que devolve o faturamento só ao dentista e `budgets` seguir lido pela recepção; custa uma função a mais e não protege os totais por orçamento.)*
3. **Índice condicional de K1:** criar `ix_patients_clinic_active` já na `V035` ou só se o teste reprovar? *(Padrão: só se reprovar.)*
4. **`created_by`/`voided_by` sem índice:** seguir o critério de `performance_indexes.sql`? *(Padrão: sim.)*
