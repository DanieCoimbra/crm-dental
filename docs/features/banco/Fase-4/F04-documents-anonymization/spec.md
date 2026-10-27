# Spec — F04 Anonimização com documentos (Fase 4)

## 1. Visão técnica
- **Feature:** `core.anonymize_patient` estendida a `clinical_documents`.
- **Stack:** PostgreSQL, `plpgsql`, `SECURITY DEFINER`.
- **Abordagem:** `V034__core_clinical_documents_anonymization.sql`, como `crm_owner` (membro de `crm_definer` sem `INHERIT`): a troca da função roda `SET LOCAL ROLE crm_definer` com `GRANT CREATE ON SCHEMA core TO crm_definer` temporário, revogado ao fim (padrão V013/V021/V029).

## 2. Objetos

```sql
GRANT SELECT                                                                 ON core.clinical_documents TO crm_definer;
GRANT UPDATE (content, rest_days, target_specialty, void_reason, anonymized_at, version) ON core.clinical_documents TO crm_definer;

CREATE POLICY cdoc_definer_upd ON core.clinical_documents
  FOR UPDATE TO crm_definer
  USING (clinic_id = core.current_clinic_id())
  WITH CHECK (clinic_id = core.current_clinic_id());
-- cdoc_definer_sel já criada na V033 (mínimo para o download); a V034 só amplia o GRANT de SELECT para todas as colunas
```

Função: `CREATE OR REPLACE FUNCTION core.anonymize_patient(p_patient_id uuid) RETURNS timestamptz` — corpo = **versão viva** (V029) + o bloco abaixo. Dono `crm_definer`, `SECURITY DEFINER`, `SET search_path = pg_catalog, pg_temp`; `EXECUTE` do `crm_app` permanece.

```sql
-- NOVO (Fase 4): depois de PERFORM set_config('core.anonymizing','on',true) e dos blocos de budgets/budget_documents
UPDATE core.clinical_documents
   SET content = NULL, rest_days = NULL, target_specialty = NULL, void_reason = NULL,
       anonymized_at = v_now, version = version + 1
 WHERE clinic_id = v_clinic AND patient_id = p_patient_id AND anonymized_at IS NULL;
```

## 3. Componentes
| Objeto | Mudança |
|---|---|
| `core.anonymize_patient(uuid)` | novo bloco de documentos |
| grants e política do `crm_definer` em `clinical_documents` | novos |

## 4. Lógica
Ordem na função: papel e sessão → paciente `FOR UPDATE` → `CR003`/`CR001` → retenção (`CR002`, **sem** documentos) → agenda (`CR004`) → parcelas (`CR006`) → liga `core.anonymizing` → prontuário, exames, notas da agenda, notas do orçamento, PDFs do orçamento → **documentos (novo)** → `patients` → `audit_log` (`patients/ANONYMIZE`) → desliga a flag → retorna `v_now`.

Cada `UPDATE` de documento passa por `trg_cdoc_forbid_immutable` (não toca colunas imutáveis), `trg_cdoc_guard_update` (transição de anonimização: `current_user = 'crm_definer'` + flag) e `trg_cdoc_audit` (`ANONYMIZE`).

## 5. Erros e bordas
| Cenário | Resultado |
|---|---|
| Paciente sem documentos | igual à V029 |
| Documento anulado com motivo | motivo zerado, `voided_*` permanece |
| Reanonimizar | `CR003` (paciente já anonimizado), documentos intactos |
| Sessão comum liga o GUC e atualiza documento | `23514` ou `42501` |
| `app.user_id` ausente | `28000` (a função já exige) |
| Falha em qualquer passo | rollback completo da transação |

## 6. Segurança e desempenho
- Políticas do definer sempre por `clinic_id`; nenhum `USING (true)`.
- Risco aceito herdado: nenhuma proteção contra o dentista anonimizar quando quiser (rota existente já restrita a `DENTIST_ADMIN`).
- Meta: p95 ≤ 100 ms para 50 documentos (índice `ix_cdoc_patient` serve o filtro).
- Rollback `U034`: `CREATE OR REPLACE` com o corpo da V029 (guardado em `U034`), `REVOKE` dos grants e `DROP POLICY cdoc_definer_upd`; **não** remove `cdoc_definer_sel` (da V033).
