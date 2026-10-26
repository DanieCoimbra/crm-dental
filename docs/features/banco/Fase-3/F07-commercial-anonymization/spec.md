# Spec — F07 Anonimização com histórico comercial e financeiro (Fase 3)

Complementa `docs/global/banco/Fase-3/spec.md` (seções 3.1, 4.7, 5.3 e 7) e as specs da F06 (Fase 1) e F07 (Fase 2).

## 1. Visão técnica
- **Feature:** ajuste de `core.anonymize_patient` ao comercial e ao financeiro.
- **Stack:** PL/pgSQL `SECURITY DEFINER`, migration `V029`.
- **Abordagem:** a função da V021 é substituída por `CREATE OR REPLACE` com dois passos novos; o definer ganha o mínimo de privilégio, escopado ao tenant.

## 2. Objetos (`V029__core_commercial_anonymization.sql`, como `crm_owner`, transação única)

### 2.1 Privilégios e políticas do `crm_definer`
```sql
GRANT SELECT                  ON core.budgets          TO crm_definer;
GRANT UPDATE (notes, version) ON core.budgets          TO crm_definer;
GRANT SELECT                  ON core.installments     TO crm_definer;
GRANT SELECT                  ON core.budget_documents TO crm_definer;
GRANT UPDATE (deleted_at)     ON core.budget_documents TO crm_definer;

CREATE POLICY budgets_definer_sel ON core.budgets FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY budgets_definer_upd ON core.budgets FOR UPDATE TO crm_definer
  USING (clinic_id = core.current_clinic_id()) WITH CHECK (clinic_id = core.current_clinic_id());
CREATE POLICY inst_definer_sel ON core.installments FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY bdoc_definer_sel ON core.budget_documents FOR SELECT TO crm_definer
  USING (clinic_id = core.current_clinic_id());
CREATE POLICY bdoc_definer_upd ON core.budget_documents FOR UPDATE TO crm_definer
  USING (clinic_id = core.current_clinic_id()) WITH CHECK (clinic_id = core.current_clinic_id());
```

### 2.2 Substituição da função (padrão da V021)
`GRANT CREATE ON SCHEMA core TO crm_definer; SET LOCAL ROLE crm_definer; CREATE OR REPLACE FUNCTION core.anonymize_patient(p_patient_id uuid) RETURNS timestamptz ...; RESET ROLE; REVOKE CREATE ON SCHEMA core FROM crm_definer;`. Dono (`crm_definer`) e `EXECUTE` do `crm_app` permanecem.

Mudanças em relação à V021 (os demais passos ficam idênticos):
1. **Novo passo, logo depois do `CR004` e antes de ligar a flag:**
   ```sql
   IF EXISTS (SELECT 1 FROM core.installments i
               WHERE i.clinic_id = v_clinic AND i.patient_id = p_patient_id AND i.status = 'OPEN') THEN
     RAISE EXCEPTION 'quite ou cancele as parcelas em aberto antes de anonimizar' USING ERRCODE = 'CR006';
   END IF;
   ```
2. **Depois de `set_config('core.anonymizing', 'on', true)`**, junto das limpezas de `notes` de agendamento e dos exames:
   ```sql
   UPDATE core.budgets SET notes = NULL, version = version + 1
    WHERE patient_id = p_patient_id AND notes IS NOT NULL;
   UPDATE core.budget_documents SET deleted_at = COALESCE(deleted_at, v_now)
    WHERE clinic_id = v_clinic AND patient_id = p_patient_id;
   ```
3. Orçamentos, itens, parcelas e pagamentos não são tocados. A retenção de 20 anos não os considera.

## 3. Componentes
`V029`, `U029`, `tests/anonymization.sql` (ampliado), `tests/anonymize_commercial.sql` (novo). `AnonymizationService` do backend (lista as `storage_key` antes; apaga os objetos depois; traduz `CR006` em 409).

## 4. Lógica (ordem dos passos)
1. Papel `DENTIST_ADMIN` e sessão com clínica e usuário (`42501`/`28000`).
2. Paciente da clínica, não anonimizado, inativo (`P0002`, `CR003`, `CR001`) — `FOR UPDATE`.
3. Retenção do prontuário (`CR002`).
4. Agendamento futuro em aberto (`CR004`).
5. **Parcela `OPEN` (`CR006`).**
6. Liga a flag `core.anonymizing`.
7. Prontuário apagado; exames marcados; `appointments.notes` zeradas; **`budgets.notes` zeradas; PDFs marcados**.
8. Dados pessoais do paciente substituídos; `ANONYMIZE` no `audit_log`.
9. Desliga a flag; devolve o instante.

Cada `UPDATE` de orçamento passa pelos triggers: `guard_budget_update` aceita `notes → NULL` sob a flag; a auditoria registra 1 `UPDATE` por orçamento; `budget_documents` passa por `forbid_immutable` (só `deleted_at` muda) e `guard_budget_document_update`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Parcela `OPEN` do paciente | `CR006`; nada muda |
| Parcelas só `PAID`/`CANCELED` | Passa |
| Orçamento `SENT` sem parcelas | Passa |
| Retenção não vencida e parcela `OPEN` | `CR002` (precedência) |
| Agendamento futuro e parcela `OPEN` | `CR004` (precedência) |
| Orçamento `APPROVED`/`REJECTED` com `notes` | `notes` viram `NULL` (exceção da flag) |
| PDF já marcado `deleted_at` | Mantém o instante original (`COALESCE`) |
| Estorno concorrente reabrindo parcela | A anonimização espera o lock do paciente e vê `OPEN` → `CR006` |
| Sem `app.user_id` | `28000` |
| Paciente já anonimizado | `CR003` |
| Outro papel que não `DENTIST_ADMIN` | `42501` |

## 6. Segurança e desempenho
- `SECURITY DEFINER` com `search_path = pg_catalog, pg_temp`, sem SQL dinâmico; `crm_definer` sem login e sem `BYPASSRLS`; todas as políticas do definer exigem `clinic_id = tenant`; nenhum `USING (true)`.
- O definer só altera `budgets.notes`/`version` e `budget_documents.deleted_at`; só lê `installments`.
- Desempenho: `CR006` por `ix_inst_patient`; `UPDATE` de orçamentos e PDFs por `ix_budgets_patient` e `ix_bdoc_patient`; anonimização com 500 agendamentos e 100 orçamentos dentro do `statement_timeout` de 15 s.
