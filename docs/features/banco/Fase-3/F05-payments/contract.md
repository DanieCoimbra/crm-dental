# Contract — F05 Pagamentos e estorno (Fase 3)

## 1. Resumo
- **Feature:** F05-payments (Fase 3)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo financeiro); F06 (auditoria); `crm_backup`.

## 2. Entradas

O backend define, na **mesma transação**, `app.clinic_id`, `app.role` (só `DENTIST_ADMIN`) e `app.user_id`. **Só `INSERT`** em `core.payments` como `crm_app`:

```json
{
  "id": "uuid (gerado pela aplicação)",
  "clinic_id": "uuid (= app.clinic_id)",
  "installment_id": "uuid (parcela OPEN da mesma clínica)",
  "kind": "PAYMENT | REVERSAL",
  "amount": "numeric(12,2), 0.01 a 999999.99; no REVERSAL, igual ao pagamento estornado",
  "method": "CASH | PIX | DEBIT_CARD | CREDIT_CARD | BANK_TRANSFER | OTHER",
  "paid_on": "date (data civil do recebimento, no fuso da clínica, calculada pelo backend)",
  "reverses_payment_id": "uuid; obrigatório no REVERSAL, nulo no PAYMENT",
  "created_by": "uuid (= app.user_id)"
}
```
Sem `UPDATE` nem `DELETE` (negados). Sem `version`.

## 3. Saídas

| Coluna | Tipo | Observação |
|---|---|---|
| `kind`, `amount`, `method`, `paid_on` | | Imutáveis (a linha nunca muda) |
| `reverses_payment_id` | `uuid` | Aponta o pagamento estornado (mesma parcela) |
| `created_at` | `timestamptz` | Instante do lançamento |
| efeito em `installments` | | `status` vira `PAID` ou volta a `OPEN` (com `version + 1`) |

**Líquido da parcela** = Σ `PAYMENT` − Σ `REVERSAL`; saldo = `installments.amount` − líquido.

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `CR010` | Pagamento maior que o saldo; parcela cancelada; estorno com valor ou alvo inválido (não é `PAYMENT`) | 409 |
| `CR003` | Paciente anonimizado | 409 |
| `23505` | Estorno repetido (`ux_pay_reverses`) | 409 |
| `23503` | Parcela ou pagamento estornado de outra clínica ou outra parcela | 404 |
| `23514` | `CHECK` (valor, data, `method`, `kind`/`reverses_payment_id`) | 422 |
| `42501` | Recepção; `created_by` ≠ `app.user_id`; `UPDATE`/`DELETE`/`TRUNCATE` | 403 (o backend barra antes) |
| `28000` | Sem `app.user_id` (auditoria, F06) | 500 |

A recepção recebe **0 linhas** em qualquer `SELECT`.

## 4. Regras e limites
- **Pagar:** `INSERT` `PAYMENT`; parcial permitido; o status da parcela é do banco.
- **Estornar:** `INSERT` `REVERSAL` com o mesmo valor do pagamento e `reverses_payment_id`; um estorno por pagamento; **não** existe estorno parcial nem de estorno.
- **Corrigir:** estornar e relançar; nunca editar ou apagar.
- **Concorrência:** o lock da parcela serializa pagamentos; o segundo relê o saldo e pode falhar com `CR010`.
- **Relatórios:** entradas do mês = líquido por `paid_on` em `[início, fim)` (caixa); o backend informa "hoje" e o fuso.
- **JPA:** `Payment` `@Immutable`, sem `@Version`; reler a parcela depois do `INSERT` (o status e a `version` mudaram).
- Sem texto livre; paciente inativo ainda paga.

## 5. Eventos emitidos
Nenhum. Efeito colateral: `UPDATE` automático da parcela, auditado (F06).

## 6. Critérios de aceite (integração)
- [ ] Testes 104 (parcial, quitação, estorno, estorno repetido), 105 (excede saldo, parcela cancelada, status manual incoerente), 106 (append-only por `crm_app` e `crm_owner`, `TRUNCATE`), 107 (concorrência), 108 (paciente anonimizado/inativo).
- [ ] Teste 109 (propriedade): líquido nunca > valor nem < 0; `PAID` ⇔ líquido ≥ valor.
- [ ] Teste 116 (parte): soma de recebimentos do mês e líquido da parcela sem `Seq Scan`, dentro das metas.
- [ ] Teste 119 (parte): restauração de `payments` com as mesmas contagens.
- [ ] Teste 117 (parte): V027 aplica do zero; `U027` reverte e reaplica.
