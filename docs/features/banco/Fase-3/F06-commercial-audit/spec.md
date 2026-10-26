# Spec — F06 Auditoria do comercial e financeiro (Fase 3)

Complementa `docs/global/banco/Fase-3/spec.md` (seções 2.3, 2.6, 5.3, 7 e 10) e a spec da F06 da Fase 2.

## 1. Visão técnica
- **Feature:** extensão da trilha de auditoria a quatro tabelas.
- **Stack:** PL/pgSQL (função existente), migration `V028`.
- **Abordagem:** recriar a constraint de `table_name` e acrescentar quatro triggers por linha; nada muda na função nem nas políticas.

## 2. Objetos (`V028__core_commercial_audit.sql`, como `crm_owner`, transação única)

```sql
ALTER TABLE core.audit_log DROP CONSTRAINT ck_audit_table;
ALTER TABLE core.audit_log ADD CONSTRAINT ck_audit_table CHECK (
  table_name IN ('patients','medical_histories','clinical_notes','patient_files','appointments',
                 'budgets','installments','payments','budget_documents'));

-- concessão temporária ao crm_owner (como dono da função), padrão da V020
SET LOCAL ROLE crm_definer;
GRANT EXECUTE ON FUNCTION core.write_audit() TO crm_owner;
RESET ROLE;

CREATE TRIGGER trg_budgets_audit AFTER INSERT OR UPDATE OR DELETE ON core.budgets
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
CREATE TRIGGER trg_inst_audit    AFTER INSERT OR UPDATE OR DELETE ON core.installments
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
CREATE TRIGGER trg_pay_audit     AFTER INSERT OR UPDATE OR DELETE ON core.payments
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();
CREATE TRIGGER trg_bdoc_audit    AFTER INSERT OR UPDATE OR DELETE ON core.budget_documents
  FOR EACH ROW EXECUTE FUNCTION core.write_audit();

SET LOCAL ROLE crm_definer;
REVOKE EXECUTE ON FUNCTION core.write_audit() FROM crm_owner;
RESET ROLE;
```
- `varchar(30)` de `table_name` comporta `budget_documents` (16 caracteres).
- `DROP`/`ADD CONSTRAINT` validam as linhas existentes, mas não executam os triggers de linha do `audit_log` (o log continua só de inserção).
- `write_audit()` usa `TG_TABLE_NAME` e `v_row.id`/`v_row.clinic_id`: as quatro tabelas têm `id` e `clinic_id`; nada muda na função.
- Os triggers incluem `DELETE` por padrão; `crm_app` não apaga nenhuma das quatro (e `payments` o nega por trigger).
- Ordem entre triggers do mesmo evento é alfabética; a auditoria (`*_audit`) não depende dos demais.

## 3. Componentes
`V028`, `U028`, `tests/audit_log.sql` e `tests/audit_commercial.sql` (ampliados/novos). Entidade `AuditEntry` (`@Immutable`, existente) do backend.

## 4. Lógica
1. Qualquer `INSERT/UPDATE/DELETE` em uma das quatro tabelas dispara `write_audit()` depois da linha.
2. A função lê `app.user_id`; se nulo, aborta a transação inteira (`28000`).
3. Insere `(clinic_id, user_id, TG_TABLE_NAME, id, TG_OP)` como `crm_definer`, sob a política escopada ao tenant; a FK composta confere o usuário.
4. Efeitos em cascata geram linhas próprias: recálculo do total (`UPDATE budgets`), sincronização do status da parcela (`UPDATE installments`), anonimização (F07).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Sem `app.user_id` | `28000`; nada é gravado |
| `user_id` de outra clínica | `23503` |
| `UPDATE` sem mudança de valores | Gera 1 linha (aceito) |
| `table_name` fora da lista | `23514` |
| Item de orçamento alterado | Gera `UPDATE` em `budgets`, nenhuma linha de `budget_items` |
| Pagamento que quita a parcela | 1 linha `INSERT` em `payments` e 1 `UPDATE` em `installments` |
| Mudança em `procedure_types` | Sem linha |
| Recepção consulta o log | 0 linhas (política da Fase 1) |

## 6. Segurança e desempenho
- Log só com identificadores: id do registro e do usuário, ação e instante; sem valores monetários, status ou `storage_key`.
- Custo: 1 `INSERT` por linha alterada (dezenas por dia); a falha de auditoria bloqueia a operação (intencional).
