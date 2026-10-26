# Contract — F02 Orçamento e itens (Fase 3)

## 1. Resumo
- **Feature:** F02-budgets (Fase 3)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo de orçamento); F03 (PDF); F04 (parcelas); F06 (auditoria); F07 (anonimização); `crm_backup`.

## 2. Entradas

O backend define, na **mesma transação** (em `READ COMMITTED`), `app.clinic_id`, `app.role` e `app.user_id`. Ausentes ou inválidos, tudo é negado.

`INSERT` / `UPDATE` em `core.budgets` como `crm_app` (só `DENTIST_ADMIN`):

```json
{
  "id": "uuid (gerado pela aplicação)",
  "clinic_id": "uuid (= app.clinic_id)",
  "patient_id": "uuid (paciente ativo da mesma clínica; imutável)",
  "appointment_id": "uuid, opcional (mesmo paciente e clínica)",
  "status": "DRAFT | SENT | APPROVED | REJECTED (nasce DRAFT)",
  "discount_amount": "numeric(12,2); 0 no INSERT, depois dos itens por UPDATE",
  "valid_until": "date, opcional",
  "notes": "varchar(500), opcional, texto claro, SEM dado clínico",
  "created_by": "uuid (= app.user_id)",
  "version": "bigint (@Version)"
}
```

**Não enviar** (o banco preenche e ignora o que vier): `number`, `total_amount`, `sent_at`, `decided_at`.

`INSERT` / `UPDATE` / `DELETE` em `core.budget_items` (só `DENTIST_ADMIN`, orçamento em `DRAFT`):

```json
{
  "id": "uuid", "clinic_id": "uuid (= app.clinic_id)", "budget_id": "uuid",
  "procedure_type_id": "uuid (da mesma clínica)",
  "quantity": "smallint, 1 a 999",
  "discount_amount": "numeric(12,2), desconto em valor do item",
  "position": "smallint, 1 a 50, único no orçamento",
  "version": "bigint (@Version)"
}
```
`description` e `unit_price` **não são enviados**: o banco copia do procedimento no `INSERT`.

## 3. Saídas

| Coluna | Tipo | Observação |
|---|---|---|
| `budgets.number` | `integer` | Sequencial por clínica, pelo banco; reler depois do `INSERT` |
| `budgets.total_amount` | `numeric(12,2)` | `Σ line_total − discount_amount`; reler depois de mexer em itens |
| `budgets.sent_at`, `decided_at` | `timestamptz` | Carimbos do banco |
| `budgets.status` | `varchar(10)` | Quatro valores |
| `budget_items.description`, `unit_price` | `varchar(120)`, `numeric(12,2)` | Snapshot do catálogo; imutáveis |
| `budget_items.line_total` | `numeric(12,2)` | Coluna gerada: `quantity * unit_price - discount_amount` |
| demais | | `updated_at` por trigger; `version` `@Version` |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `CR007` | Orçamento congelado: item ou conteúdo alterado fora de `DRAFT` | 409 |
| `CR008` | Status inválido: nasce fora de `DRAFT`, volta a `DRAFT`, sai de terminal, enviar/aprovar sem itens ou com total 0 | 409 |
| `CR003` / `CR005` | Paciente anonimizado / inativo na criação | 409 |
| `23514` | `CHECK` (teto, total negativo, quantidade, posição), coluna imutável | 422 |
| `23503` | Procedimento, paciente ou atendimento de outra clínica ou paciente | 404 |
| `23505` | Posição repetida (no `COMMIT`); número repetido sob isolamento > `READ COMMITTED` | 409 |
| `42501` | Recepção gravando; `created_by` ≠ `app.user_id` | 403 (o backend barra antes) |
| `28000` | Sem `app.user_id` (auditoria, F06) | 500 |

`UPDATE`/`DELETE` feitos pela recepção afetam **0 linhas** sem erro.

## 4. Regras e limites
- **Ordem de gravação:** cabeçalho em `DRAFT` (sem desconto) → itens → `UPDATE` do desconto do orçamento. Reordenar itens troca `position` numa transação.
- **Descontos:** em valor, nos dois níveis; o backend converte percentual. Total final entre 0 e 999.999,99.
- **Congelamento:** depois de `DRAFT` nada de conteúdo muda; descartar rascunho é `DRAFT → REJECTED` (não há `DELETE` de orçamento).
- **Ordem entre status** (por exemplo, exigir `SENT` antes de `APPROVED`) é do backend; o banco só impõe terminalidade, congelamento e a exigência de itens e total.
- **JPA:** mapear `number`, `total_amount`, `sent_at`, `decided_at`, `description`, `unit_price`, `line_total` como `insertable = false, updatable = false` e reler a linha depois de gravar; `updatable = false` nas colunas imutáveis.
- `notes` é texto claro e vai no PDF; nunca dado clínico.

## 5. Eventos emitidos
Nenhum. A auditoria do cabeçalho é efeito de F06 (cada mudança de item gera um `UPDATE` em `budgets`).

## 6. Critérios de aceite (integração)
- [ ] Testes 91 a 99 (numeração, status inicial, snapshot e total, desconto, congelamento, status, paciente, papéis, corrida item × envio).
- [ ] Teste 109 (propriedade): total sempre igual à soma; nenhum item alterado depois do envio.
- [ ] Testes 86 e 87 (parte): isolamento de tenant e FKs cruzadas.
- [ ] Teste 117 (parte): V024 aplica do zero; `U024` reverte e reaplica.
