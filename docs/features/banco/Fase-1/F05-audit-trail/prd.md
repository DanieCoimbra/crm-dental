# PRD — F05 Trilha de Auditoria (Fase 1)

**Derivação:** PRD global da Fase 1, US-108, decisão P4 e FR-8, FR-9; spec global, seções 2.3 (`audit_log`), 2.6, 3.2 e 4.1.
**Dependências externas:** F01 (`current_user_id()`), F02, F03, F04 (as quatro tabelas auditadas), Fase 0 (`users`, `clinics`, `crm_definer`). Consumida por F06 (grava `ANONYMIZE`) e pelo backend (consulta de auditoria, somente leitura).

## 1. Introdução

- **Problema:** dados clínicos exigem rastro de quem os criou, alterou ou removeu, sem que o próprio rastro vire vazamento.
- **Solução:** tabela `audit_log` só com identificadores, alimentada por triggers `SECURITY DEFINER` nas quatro tabelas clínicas, append-only inclusive para o dono.
- **Metas:**
  1. 100% dos `INSERT/UPDATE/DELETE` clínicos geram exatamente 1 linha de auditoria.
  2. Nenhuma coluna de valor ou de dado pessoal no log.
  3. `crm_app` só lê (e só o dentista); nunca escreve, altera ou apaga.
  4. Sem `app.user_id`, a operação clínica falha.

## 2. User stories

### US-001: Registrar cada alteração clínica
**Descrição:** Como dentista, quero saber quem criou, alterou ou removeu dados clínicos e quando.

**Critérios de aceite:**
- [ ] `core.audit_log` (`id`, `clinic_id`, `user_id`, `table_name`, `record_id`, `action`, `occurred_at`) conforme a spec global.
- [ ] Triggers `AFTER INSERT OR UPDATE OR DELETE ... FOR EACH ROW` em `patients`, `medical_histories`, `clinical_notes` e `patient_files` chamam `core.write_audit()`.
- [ ] `action` ∈ `INSERT`, `UPDATE`, `DELETE`, `ANONYMIZE`.

### US-002: Autoria obrigatória
**Descrição:** Como titular, quero que nenhuma gravação clínica seja anônima.

**Critérios de aceite:**
- [ ] Sem `app.user_id`, `write_audit()` falha com `28000` e a transação inteira reverte.
- [ ] `user_id` de outra clínica falha pela FK composta `(user_id, clinic_id)`.

### US-003: Log inviolável
**Descrição:** Como responsável pela segurança, quero que o log só cresça.

**Critérios de aceite:**
- [ ] `crm_app` sem `INSERT/UPDATE/DELETE/TRUNCATE`; só `SELECT`, e apenas para `DENTIST_ADMIN` da clínica.
- [ ] Trigger `BEFORE UPDATE OR DELETE` impede alteração até pelo dono.
- [ ] Escrita só pela função (dono `crm_definer`), com política `INSERT TO crm_definer WITH CHECK (clinic_id = tenant)`.

### US-004: Consulta do histórico
**Descrição:** Como dentista, quero ver o histórico recente da clínica e de um registro.

**Critérios de aceite:**
- [ ] Índices `ix_audit_clinic_time` e `ix_audit_record` atendem as duas consultas sem *sort*.

## 3. Requisitos funcionais

- **FR-1:** Migration `V012__core_audit_log.sql`: tabela, índices, RLS, grants, `core.write_audit()` (dono `crm_definer`), `trg_audit_log_append_only` (linha: `UPDATE`/`DELETE`), `trg_audit_log_no_truncate` (comando: `TRUNCATE`, inclusive `CASCADE`) e os quatro triggers `trg_*_audit`.
- **FR-2:** Política `SELECT TO crm_backup USING (true)`.
- **FR-3:** Nenhuma coluna de valor (antigo/novo) e nenhum campo textual livre.
- **FR-4:** `audit_log.id` é `bigint GENERATED ALWAYS AS IDENTITY` (decisão S1).

## 4. Fora do escopo

- Auditoria de **leitura** do prontuário.
- Comparação de valores antigo/novo.
- Expurgo ou retenção do log (cresce; sem dado pessoal).
- Endpoint de consulta (backend).

## 5. Considerações técnicas e riscos

- Falha na auditoria bloqueia a operação clínica (intencional).
- `audit_log` guarda `record_id` e `user_id`: pseudonimizados; por isso pode sobreviver à anonimização.
- O trigger roda como `crm_definer`; `search_path` fixo e sem SQL dinâmico.
- Volume: uma linha por linha alterada; irrelevante no porte do produto.
- **Pergunta aberta:** nenhuma.
