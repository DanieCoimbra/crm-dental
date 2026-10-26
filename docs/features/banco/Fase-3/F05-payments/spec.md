# Spec — F05 Pagamentos e estorno (Fase 3)

Complementa `docs/global/banco/Fase-3/spec.md` (seções 2.3, 2.4, 2.5, 2.6, 3, 4.5 e 7).

## 1. Visão técnica
- **Feature:** `core.payments` append-only, estorno por linha e status automático da parcela.
- **Stack:** PostgreSQL 16, PL/pgSQL (invoker, `search_path` fixo), migration `V027`.
- **Abordagem:** guard `BEFORE INSERT` trava a parcela e valida o saldo; `AFTER INSERT` sincroniza o status; triggers de linha e de comando fecham `UPDATE`, `DELETE` e `TRUNCATE`.

## 2. Objetos (`V027__core_payments.sql`, como `crm_owner`, transação única)

### 2.1 Tabela e índices
DDL: spec global, 2.5 (`core.payments`). Índices: `ux_pay_id_inst_clinic (id, installment_id, clinic_id)`, `ux_pay_reverses (reverses_payment_id)`, `ix_pay_installment (clinic_id, installment_id)`, `ix_pay_clinic_paid (clinic_id, paid_on, id)`. `created_by` sem índice (decisão registrada).

### 2.2 Triggers e funções
| Objeto | Evento | Efeito |
|---|---|---|
| `trg_pay_guard` → `core.guard_payment()` | `BEFORE INSERT` | 2.3 |
| `trg_pay_sync_status` → `core.sync_installment_status()` | `AFTER INSERT` | 2.4 |
| `trg_pay_append_only` → `core.payments_append_only()` | `BEFORE UPDATE OR DELETE ... FOR EACH ROW` e `BEFORE TRUNCATE ... FOR EACH STATEMENT` | `RAISE EXCEPTION 'payments e somente de insercao' USING ERRCODE = '42501'` |

Funções com `SET search_path = pg_catalog, pg_temp`, `SECURITY INVOKER`, `REVOKE EXECUTE ... FROM PUBLIC`.

### 2.3 `guard_payment()`
1. `SELECT i.patient_id, i.amount, i.status FROM core.installments i WHERE i.id = NEW.installment_id AND i.clinic_id = NEW.clinic_id FOR NO KEY UPDATE`. Sem linha (outra clínica, papel sem acesso, inexistente): `RETURN NEW` (o `WITH CHECK` ou a FK respondem; a recepção recebe `42501`).
2. `SELECT anonymized_at FROM core.patients WHERE id = i.patient_id AND clinic_id = NEW.clinic_id FOR KEY SHARE`; anonimizado → `CR003`. Inativo: segue.
3. `i.status = 'CANCELED'` → `CR010`.
4. `v_net := core.installment_net_paid(NEW.installment_id)` (leitura nova depois da espera do lock).
5. `kind = 'PAYMENT'`: `v_net + NEW.amount > i.amount` → `CR010`.
6. `kind = 'REVERSAL'`: o pagamento apontado deve ser da mesma parcela e clínica, `kind = 'PAYMENT'` e `amount = NEW.amount`, senão `CR010`. Estorno repetido: `23505` (`ux_pay_reverses`); alvo de outra parcela: `23503` (FK composta).

### 2.4 `sync_installment_status()`
```sql
UPDATE core.installments
   SET status = CASE WHEN core.installment_net_paid(id) >= amount THEN 'PAID' ELSE 'OPEN' END,
       version = version + 1
 WHERE id = NEW.installment_id AND clinic_id = NEW.clinic_id
   AND status IS DISTINCT FROM CASE WHEN core.installment_net_paid(id) >= amount THEN 'PAID' ELSE 'OPEN' END;
```
Só atualiza quando o status muda. O `UPDATE` dispara `guard_installment` (consistente por construção), a constraint trigger da soma (só se `status` mudou; passa) e a auditoria da parcela. `version + 1` faz uma entidade JPA velha da parcela falhar por bloqueio otimista.

### 2.5 RLS e grants
`ENABLE` + `FORCE`. `SELECT` `T AND D`; `INSERT` `WITH CHECK (T AND D AND U)`; `SELECT TO crm_backup USING (true)`; **sem política de `UPDATE`/`DELETE`**. `GRANT SELECT, INSERT ON core.payments TO crm_app; GRANT SELECT ... TO crm_backup`. Nenhum `UPDATE`, `DELETE` ou `TRUNCATE` concedido.

## 3. Componentes
`V027`, `U027`, `tests/payments.sql`, `tests/payments_append_only.sql`, `tests/payments_race.sh`. Entidade JPA `Payment` (`@Immutable`, sem `@Version`).

## 4. Lógica
1. **Pagar:** `INSERT (kind='PAYMENT', amount, method, paid_on)`; o banco trava a parcela, confere o saldo e, no `AFTER`, marca `PAID` se quitou.
2. **Estornar:** `INSERT (kind='REVERSAL', reverses_payment_id, amount = do pagamento)`; o banco confere alvo e valor; a parcela volta a `OPEN` se estava `PAID`.
3. **Corrigir erro de lançamento:** estornar e lançar de novo (nunca editar).
4. **Caixa do mês:** `SUM(CASE kind WHEN 'PAYMENT' THEN amount ELSE -amount END)` por `paid_on` em `[início, fim)`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Pagamento maior que o saldo | `CR010` |
| Pagamento em parcela `CANCELED` | `CR010` |
| Pagamento parcial; depois o restante | Passam; `PAID` no último |
| Estorno de valor diferente | `CR010` |
| Estorno de estorno | `CR010` |
| Dois estornos do mesmo pagamento | `23505` |
| Estorno de pagamento de outra parcela | `23503` |
| `UPDATE`/`DELETE`/`TRUNCATE` por `crm_app` | `42501` (grant) |
| `UPDATE`/`DELETE`/`TRUNCATE` por `crm_owner` | `42501` (trigger) |
| Dois pagamentos no último saldo | Um passa; o outro `CR010` |
| Parcelas diferentes simultâneas | Ambos passam |
| Paciente anonimizado | `CR003` |
| Paciente inativo | Passa |
| Recepção | 0 linhas; `INSERT` → `42501` |
| `app.role` ausente ou `'ADMIN'` | Tudo negado |
| `created_by` ≠ `app.user_id` | `42501` |
| Valor ≤ 0 ou > 999.999,99; `paid_on` < 2000; `method` fora da lista | `23514` |
| Sem `app.user_id` | `28000` (auditoria, F06) |

## 6. Segurança e desempenho
- Histórico de dinheiro imutável por três camadas (grant, ausência de política, trigger); estorno por linha nova; sem texto livre.
- Sem `SECURITY DEFINER`; o status da parcela muda com o papel do chamador.
- Custo por `INSERT`: 1 lock da parcela + 1 soma por `ix_pay_installment` + 1 `UPDATE` de parcela quando o status muda + 2 linhas de auditoria no máximo; ≤ 15 ms.
- Soma de recebimentos do mês ≤ 10 ms por `ix_pay_clinic_paid`, sem `Seq Scan`.
