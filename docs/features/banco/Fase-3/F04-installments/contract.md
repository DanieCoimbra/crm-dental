# Contract — F04 Parcelas (Fase 3)

## 1. Resumo
- **Feature:** F04-installments (Fase 3)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo financeiro); F05 (pagamentos); F06 (auditoria); F07 (`CR006`); `crm_backup`.

## 2. Entradas

O backend define, na **mesma transação**, `app.clinic_id`, `app.role` (só `DENTIST_ADMIN` enxerga e grava) e `app.user_id`. `INSERT` / `UPDATE` em `core.installments` como `crm_app`:

```json
{
  "id": "uuid (gerado pela aplicação)",
  "clinic_id": "uuid (= app.clinic_id)",
  "budget_id": "uuid (orçamento APPROVED)",
  "patient_id": "uuid (= paciente do orçamento)",
  "number": "smallint, 1 a 60, único entre as não canceladas",
  "amount": "numeric(12,2), 0.01 a 999999.99",
  "due_date": "date",
  "status": "OPEN no INSERT",
  "created_by": "uuid (= app.user_id)",
  "version": "bigint (@Version)"
}
```
Cancelar: `UPDATE ... SET status = 'CANCELED'` por consulta própria (a coluna `status` não é atualizável pelo JPA). Renegociar data: `UPDATE ... SET due_date`.

## 3. Saídas

| Coluna | Tipo | Observação |
|---|---|---|
| `status` | `varchar(10)` | `OPEN`, `PAID`, `CANCELED`; `OPEN`↔`PAID` mudam por trigger de pagamento (com `version + 1`) |
| `amount`, `number`, `budget_id`, `patient_id` | | Imutáveis |
| `due_date` | `date` | Muda só em `OPEN` |
| demais | | `updated_at` por trigger; `version` `@Version` |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `CR009` | Orçamento não `APPROVED`; criada fora de `OPEN`; status incoerente com o líquido pago; mudança em parcela terminal; cancelamento com saldo pago | 409 |
| `CR011` | Σ parcelas ativas ≠ `total_amount`, **no `COMMIT`** | 409 |
| `CR003` | Paciente anonimizado | 409 |
| `23505` | Número repetido entre as ativas | 409 |
| `23514` | `CHECK` (valor, número, data); coluna imutável | 422 |
| `23503` | Orçamento ou paciente de outra clínica ou divergente | 404 |
| `42501` | Recepção; `created_by` ≠ `app.user_id`; `DELETE` | 403 (o backend barra antes) |
| `28000` | Sem `app.user_id` (auditoria, F06) | 500 |

A recepção recebe **0 linhas** em qualquer `SELECT`.

## 4. Regras e limites
- **Geração:** só de orçamento `APPROVED`; o backend calcula as parcelas (última absorve os centavos); a soma é conferida no `COMMIT`.
- **Refazer o plano:** cancelar as abertas sem saldo e inserir as novas **na mesma transação**; parcela paga só se cancela depois de estornar.
- **Atraso é derivado:** `status = 'OPEN' AND due_date < :hoje` (o backend informa "hoje" no fuso da clínica).
- **JPA:** `status` não atualizável (muda por trigger); tratar `CR011` no `COMMIT`, não no `flush`; reler a parcela depois de pagamentos.
- Sem `DELETE`; o histórico fica no `status` e na auditoria.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 100 (geração só de `APPROVED`, nasce `OPEN`, paciente coerente), 101 (soma, cancelamento, refazer o plano), 102 (imutáveis, `due_date`, números), 103 (papéis).
- [ ] Teste 116 (parte): contas a receber, vencidas e ficha do paciente sem `Seq Scan` e dentro das metas.
- [ ] Teste 109 (propriedade): soma das parcelas ativas = total; `PAID` ⇔ líquido ≥ valor.
- [ ] Teste 117 (parte): V026 aplica do zero; `U026` reverte e reaplica.
