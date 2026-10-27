# PRD — F04 Anonimização com documentos (Fase 4)

**Derivação:** PRD global da Fase 4, US-406, decisão D7 e FR-11; spec global, seções 4.5 e S6, S10.
**Dependências externas:** F02 (`clinical_documents` e o estado anonimizado no `ck_cdoc_content`); F03 (trigger que grava `ANONYMIZE` por documento); Fase 1 F-anonimização (V013), Fase 2 (V021) e Fase 3 (V029: última definição de `core.anonymize_patient`). Consumida pelo backend (rota de anonimização do paciente, sem mudança de contrato).

## 1. Introdução

- **Problema:** documentos clínicos carregam texto cifrado e dados de saúde do paciente; ao anonimizar o titular (LGPD), não pode sobrar dado pessoal ou clínico neles.
- **Solução:** `anonymize_patient` passa a zerar `content`, `rest_days`, `target_specialty` e `void_reason` dos documentos do paciente e marcar `anonymized_at`; a linha (número, tipo, datas, emissor) permanece. Não bloqueia a anonimização (D7) e não entra na retenção (S10).
- **Metas:**
  1. 0 colunas com dado pessoal ou clínico remanescentes nos documentos do paciente.
  2. 1 linha `ANONYMIZE` por documento, além da de `patients`.
  3. Anonimização de paciente com 50 documentos p95 ≤ 100 ms.
  4. Função anterior (V029) restaurável pelo rollback.

## 2. User stories

### US-001: Zerar os documentos do paciente
**Descrição:** Como titular, quero que meus documentos sejam esvaziados ao ser anonimizado.

**Critérios de aceite:**
- [ ] Para cada documento do paciente: `content`, `rest_days`, `target_specialty`, `void_reason` nulos; `anonymized_at` preenchido; `version` incrementado.
- [ ] Número, tipo, `issued_on`, `sha256`, emissor e datas permanecem; paciente continua referenciado.
- [ ] Documento já anonimizado não é tocado (`anonymized_at IS NULL` no filtro).
- [ ] Sem documentos: a função se comporta como na V029.

### US-002: Sem bloqueio e sem forja
**Descrição:** Como arquiteto, quero que documentos não bloqueiem a anonimização e que a exceção de imutabilidade não seja forjável.

**Critérios de aceite:**
- [ ] Documento (emitido, anulado ou não) não gera `CR00x` novo; precedência `CR002 → CR004 → CR006` inalterada.
- [ ] Sessão comum que liga `core.anonymizing = 'on'` e tenta `UPDATE` em documento → `23514`/`42501` (exige `current_user = 'crm_definer'`).
- [ ] O `crm_definer` só enxerga e altera documentos da clínica da sessão.

## 3. Requisitos funcionais
- **FR-1:** `GRANT SELECT` e `UPDATE (content, rest_days, target_specialty, void_reason, anonymized_at, version)` em `clinical_documents` ao `crm_definer`, com políticas `cdoc_definer_sel/upd` por `clinic_id`.
- **FR-2:** `CREATE OR REPLACE FUNCTION core.anonymize_patient(uuid)` sobre o corpo vivo da V029, com o `UPDATE` de documentos depois de ligar a flag e antes de anonimizar `patients`.
- **FR-3:** `U034` restaura a função da V029 e remove grants e políticas do definer.

## 4. Non-goals
- Entrar na retenção do prontuário (S10); apagar a linha do documento; bloquear a anonimização por documento; Storage (não há PDF guardado).

## 5. Requisitos de IA
Não se aplica.

## 6. Especificações técnicas
Ver `spec.md`. A função é substituída por inteiro: copiar de um banco vivo, não de memória.

## 7. Riscos e perguntas abertas
1. Perder uma alteração futura da função se o corpo copiado estiver desatualizado: diferir do catálogo antes de escrever.
2. `UPDATE` do definer dispara os triggers da F02 e F03: a ordem (guarda, `set_updated_at`, auditoria) precisa de teste.
3. Documento anulado com motivo em claro: o motivo é zerado (coberto).
- Perguntas abertas: nenhuma.
