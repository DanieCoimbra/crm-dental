# PRD — F02 Documentos clínicos (Fase 4)

**Derivação:** PRD global da Fase 4, US-402, US-403, US-404, decisões D1–D7 e FR-1 a FR-7; spec global, seções 2.3, 2.4, 4.1 e 4.2 e S3–S7.
**Dependências externas:** F01 (CRO do emissor, `CR012`); Fase 1 (`patients`, `users`, RLS, funções de sessão); Fase 2 (`appointments`, `ux_appt_id_patient_clinic`). Consumida por F03 (auditoria e download), F04 (anonimização) e pelo backend (emissão, listagem, anulação).

## 1. Introdução

- **Problema:** atestado, receita e encaminhamento não têm registro, número nem prova de quem emitiu.
- **Solução:** `core.clinical_documents`, tabela imutável (salvo anulação e anonimização), com texto cifrado pela aplicação, campos estruturados mínimos, número por clínica e tipo atribuído pelo banco, RLS por clínica e por papel.
- **Metas:**
  1. Recepção com 0 linhas visíveis e 0 gravações.
  2. Numeração sem repetição sob concorrência.
  3. `INSERT` p95 ≤ 20 ms com 100 mil documentos.
  4. 0 `UPDATE` além de anular e anonimizar; 0 `DELETE`/`TRUNCATE` por qualquer papel.

## 2. User stories

### US-001: Emitir documento
**Descrição:** Como dentista, quero emitir atestado, receita ou encaminhamento de um paciente.

**Critérios de aceite:**
- [ ] Tabela conforme spec global 2.3 (tipo, número, `issued_on`, `rest_days`, `target_specialty`, `content`, `sha256`, `appointment_id` opcional).
- [ ] `CERTIFICATE` exige `rest_days` (1 a 90); `REFERRAL` exige `target_specialty`; `PRESCRIPTION` não tem campo estruturado.
- [ ] `number` por `(clinic_id, document_type)` atribuído pelo banco; valor do cliente é sobrescrito.
- [ ] Emissor sem CRO → `CR012`; paciente anonimizado → `CR003`; inativo → `CR005`; recepção → `42501`.
- [ ] `appointment_id` de outro paciente ou clínica → `23503`.

### US-002: Consultar documentos do paciente
**Descrição:** Como dentista, quero listar os documentos de um paciente por data.

**Critérios de aceite:**
- [ ] `ix_cdoc_patient (clinic_id, patient_id, created_at DESC, id DESC)` serve a lista por keyset.
- [ ] Recepção: 0 linhas.
- [ ] `EXPLAIN` com RLS usa o índice com 100 mil documentos.

### US-003: Anular documento
**Descrição:** Como dentista, quero anular um documento emitido por engano, sem apagá-lo.

**Critérios de aceite:**
- [ ] `UPDATE` só de `voided_at`, `voided_by` (= `app.user_id`), `void_reason` (≤ 200) e `version`, de nulo para valor, uma vez.
- [ ] Outra coluna, nova anulação ou `DELETE`/`TRUNCATE` → falha (`23514`/`42501`).
- [ ] Recepção: 0 linhas atualizadas.

## 3. Requisitos funcionais
- **FR-1:** `core.clinical_documents` com RLS `ENABLE` + `FORCE`, política por operação e política `SELECT` do `crm_backup`.
- **FR-2:** Triggers `trg_cdoc_before_insert`, `trg_cdoc_forbid_immutable`, `trg_cdoc_guard_update`, `trg_cdoc_set_updated_at`, `trg_cdoc_no_delete`, `trg_cdoc_no_truncate`.
- **FR-3:** `GRANT` mínimo: `SELECT, INSERT` e `UPDATE (voided_at, voided_by, void_reason, version)` ao `crm_app`; `SELECT` ao `crm_backup`.
- **FR-4:** `content` aceita só Base64 (mín. 40, máx. 22.000 caracteres); `sha256` hex minúsculo.

## 4. Non-goals
- Geração de PDF, Storage, modelos por clínica, edição, versionamento, snapshot de cabeçalho.
- Auditoria (F03) e anonimização (F04), mas o trigger de guarda já prevê a transição de anonimização.
- Receita de controle especial, CID, busca por conteúdo.

## 5. Requisitos de IA
Não se aplica.

## 6. Especificações técnicas
Ver `spec.md`. Integração: backend cifra `content`, calcula `sha256`, relê a linha (número) e mapeia `number` como não inserível.

## 7. Riscos e perguntas abertas
1. Lock consultivo `cdoc:<clinic>:<tipo>` atrás do pooler 6543: só se confirma com o backend e no Supabase real.
2. Mapeamento JPA do `UPDATE` por coluna (`@DynamicUpdate`).
3. `DELETE`/`TRUNCATE` bloqueados afetam scripts de limpeza e de restauração (desligar `trg_cdoc_no_*` só neles).
- Perguntas abertas: nenhuma.
