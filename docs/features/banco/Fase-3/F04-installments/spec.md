# Spec — F04 Parcelas (Fase 3)

Complementa `docs/global/banco/Fase-3/spec.md` (seções 2.3, 2.4, 2.5, 3, 4.5 e 7).

## 1. Visão técnica
- **Feature:** `core.installments` com soma conferida ao `COMMIT` e status coerente com os pagamentos.
- **Stack:** PostgreSQL 16, PL/pgSQL (invoker, `search_path` fixo), `CREATE CONSTRAINT TRIGGER`, migration `V026`.
- **Abordagem:** guard `BEFORE` valida geração, imutabilidade e transições; constraint trigger adiável confere a soma; a função de líquido pago nasce nesta migration (a `payments` ainda não existe, ver 2.3).

## 2. Objetos (`V026__core_installments.sql`, como `crm_owner`, transação única)

### 2.1 Tabela e índices
DDL: spec global, 2.5 (`core.installments`). Índices: `ux_inst_id_clinic`, `ux_inst_budget_number` (único parcial `WHERE status <> 'CANCELED'`), `ix_inst_budget (budget_id, number, id)`, `ix_inst_patient (clinic_id, patient_id, due_date, id)`, `ix_inst_open_due (clinic_id, due_date, id) WHERE status = 'OPEN'`. `created_by` (FK composta para `users`) sem índice, decisão registrada na migration.

### 2.2 Triggers e funções
| Objeto | Evento | Efeito |
|---|---|---|
| `trg_inst_set_updated_at` | `BEFORE UPDATE` | `core.set_updated_at()` |
| `trg_inst_forbid_immutable` | `BEFORE UPDATE` | `id`, `clinic_id`, `budget_id`, `patient_id`, `number`, `amount`, `created_by`, `created_at` (`23514`) |
| `trg_inst_guard` → `core.guard_installment()` | `BEFORE INSERT OR UPDATE` | 2.4 |
| `ctrg_inst_sum_matches_total` → `core.check_installments_sum()` | `CONSTRAINT TRIGGER ... AFTER INSERT OR UPDATE OF amount, status ... DEFERRABLE INITIALLY DEFERRED ... FOR EACH ROW` | 2.5 |
| `core.installment_net_paid(uuid)` | — | `STABLE`, invoker: `COALESCE(sum(CASE kind WHEN 'PAYMENT' THEN amount ELSE -amount END), 0) FROM core.payments WHERE installment_id = $1`; `EXECUTE` ao `crm_app` |

### 2.3 Ordem com a F05
`core.installment_net_paid` lê `core.payments`, que só existe na V027. A V026 cria a função em **PL/pgSQL** (o corpo não é validado na criação, ao contrário de `LANGUAGE sql`) e a V027 cria a tabela. Entre as duas migrations a função existe mas só é chamada quando o status de uma parcela muda; os testes de status e cancelamento da F04 rodam sobre V001–V027. Na reversão, `U027` remove a tabela antes de `U026` remover a função.

### 2.4 `guard_installment()`
- **`INSERT`:** `SELECT status FROM core.budgets WHERE id = NEW.budget_id AND clinic_id = NEW.clinic_id FOR KEY SHARE`; sem linha: `RETURN NEW` (a RLS/FK respondem). `status <> 'APPROVED'` → `CR009`. `NEW.status <> 'OPEN'` → `CR009`. Paciente: `SELECT anonymized_at FROM core.patients WHERE id = NEW.patient_id AND clinic_id = NEW.clinic_id FOR KEY SHARE`; anonimizado → `CR003`.
- **`UPDATE` com status diferente** (curto-circuito quando igual): `OLD.status = 'CANCELED'` → `CR009`; `NEW.status = 'CANCELED'` exige `OLD.status = 'OPEN'` e `core.installment_net_paid(OLD.id) = 0`, senão `CR009`; `NEW.status = 'PAID'` exige `net >= amount`; `NEW.status = 'OPEN'` exige `net < amount`; senão `CR009`.
- **`UPDATE` com `due_date` diferente** e `OLD.status <> 'OPEN'` → `CR009`.

### 2.5 `check_installments_sum()`
Para `NEW.budget_id`/`NEW.clinic_id`: `count(*)` e `COALESCE(sum(amount), 0)` das parcelas com `status <> 'CANCELED'`, e `total_amount` do orçamento. `count > 0 AND sum <> total_amount` → `CR011`. Roda no `COMMIT`, com as variáveis de sessão locais ainda válidas.

### 2.6 RLS e grants
`ENABLE` + `FORCE`. `SELECT` `T AND D`; `INSERT` `WITH CHECK (T AND D AND U)`; `UPDATE` `USING/WITH CHECK (T AND D)`; `SELECT TO crm_backup USING (true)`; sem política de `DELETE` (a recepção não tem política: 0 linhas). `GRANT SELECT, INSERT, UPDATE ... TO crm_app; GRANT SELECT ... TO crm_backup; GRANT EXECUTE ON FUNCTION core.installment_net_paid(uuid) TO crm_app`. A política do `crm_definer` (`SELECT`) entra na F07.

## 3. Componentes
`V026`, `U026`, `tests/installments.sql`, `tests/installments_sum.sql`, `tests/installments_roles.sql`. Entidade JPA `Installment` (`status` não atualizável; cancelamento por consulta própria).

## 4. Lógica
1. **Gerar:** o backend divide o total (a última parcela absorve os centavos) e insere as parcelas `OPEN`; ao `COMMIT`, o constraint trigger confere a soma.
2. **Refazer o plano:** na mesma transação, `UPDATE status = 'CANCELED'` das abertas sem saldo pago e `INSERT` das novas (os números se reaproveitam).
3. **Renegociar data:** `UPDATE due_date` enquanto `OPEN`.
4. **Quitação e estorno:** o status muda pelo trigger da F05 (OPEN ↔ PAID), validado por `guard_installment`.
5. **Atraso:** `OPEN` e `due_date < :hoje` (consulta por `ix_inst_open_due`).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Orçamento `DRAFT`, `SENT` ou `REJECTED` | `CR009` |
| Parcela criada `PAID` ou `CANCELED` | `CR009` |
| `patient_id` diferente do orçamento | `23503` |
| Paciente anonimizado | `CR003` |
| Σ ativas ≠ total, no `COMMIT` | `CR011` |
| Cancelar uma sem substituir | `CR011` |
| Refazer o plano na mesma transação | Passa |
| Número 0 ou 61 | `23514` |
| Número repetido entre ativas | `23505` |
| Reaproveitar número de parcela cancelada | Passa |
| Alterar `amount`, `number`, `budget_id`, `patient_id` | `23514` |
| `due_date` de `PAID`/`CANCELED` | `CR009` |
| Marcar `PAID` sem líquido; `OPEN` com líquido cheio | `CR009` |
| Cancelar com saldo pago; sair de `CANCELED` | `CR009` |
| Recepção | 0 linhas; `INSERT` → `42501` |
| `app.role` ausente ou `'ADMIN'` | Tudo negado |
| `DELETE` | Negado |

## 6. Segurança e desempenho
- Dado financeiro só para `DENTIST_ADMIN` (decisão C2), sob RLS `FORCE`; nenhuma função `SECURITY DEFINER`.
- Soma no `COMMIT`: 1 consulta por linha inserida (até 60) por `ix_inst_budget`.
- Metas: parcelas vencidas (página de 50) ≤ 10 ms com 5 mil parcelas na clínica e 100 mil no banco, sem `Seq Scan`, **com a RLS ativa**.
