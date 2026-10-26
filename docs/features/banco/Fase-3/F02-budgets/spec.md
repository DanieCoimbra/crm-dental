# Spec — F02 Orçamento e itens (Fase 3)

Complementa `docs/global/banco/Fase-3/spec.md` (seções 2.3, 2.5, 2.6, 3, 4.1 a 4.4 e 7).

## 1. Visão técnica
- **Feature:** `core.budgets` e `core.budget_items` com integridade monetária no banco.
- **Stack:** PostgreSQL 16, PL/pgSQL (invoker, `search_path` fixo), migration `V024`, triggers genéricos da V002. Sem extensão nova.
- **Abordagem:** triggers `BEFORE` validam e preenchem (número, total, carimbos, snapshot) e `AFTER` recalculam o total; o orçamento é o lock que serializa itens e mudança de status.

## 2. Objetos (`V024__core_budgets.sql`, como `crm_owner`, transação única)

DDL das tabelas: spec global, seção 2.5 (`core.budgets`, `core.budget_items`). Índices (seção 2.4): `ux_budgets_clinic_number`, `ux_budgets_id_clinic`, `ux_budgets_id_patient_clinic`, `ix_budgets_patient`, `ix_budgets_status`, `ix_budgets_decided`, `ix_budgets_appointment`, `ux_items_budget_position` (adiável), `ix_items_procedure`.

### 2.1 Funções e triggers

| Objeto | Evento | Efeito |
|---|---|---|
| `core.budget_items_total(uuid)` | — | `STABLE`, invoker: `COALESCE(sum(line_total), 0)` dos itens; `EXECUTE` ao `crm_app` |
| `trg_budgets_before_insert` → `core.budgets_before_insert()` | `BEFORE INSERT` em `budgets` | `status <> 'DRAFT'` → `CR008`; guarda do paciente (2.2); número (2.3); `total_amount := 0 - discount_amount`; `sent_at`, `decided_at` := `NULL` |
| `trg_budgets_guard_update` → `core.guard_budget_update()` | `BEFORE UPDATE` | Tabela de decisão (2.4) |
| `trg_budgets_forbid_immutable` | `BEFORE UPDATE` | `id`, `clinic_id`, `number`, `patient_id`, `created_by`, `created_at` (`23514`) |
| `trg_budgets_set_updated_at` / `trg_items_set_updated_at` | `BEFORE UPDATE` | `core.set_updated_at()` |
| `trg_items_forbid_immutable` | `BEFORE UPDATE` em `budget_items` | `id`, `clinic_id`, `budget_id`, `procedure_type_id`, `description`, `unit_price`, `created_at` |
| `trg_items_guard` → `core.guard_budget_item()` | `BEFORE INSERT OR UPDATE OR DELETE` em `budget_items` | 2.5 |
| `trg_items_refresh_total` → `core.refresh_budget_total()` | `AFTER INSERT OR UPDATE OR DELETE` em `budget_items`, por linha | `UPDATE core.budgets SET total_amount = core.budget_items_total(id) - discount_amount WHERE id = <budget_id> AND clinic_id = <clinic_id>`; não altera `version` |

Funções de trigger com `SET search_path = pg_catalog, pg_temp`, `REVOKE EXECUTE ... FROM PUBLIC`.

### 2.2 Guarda do paciente (dentro de `budgets_before_insert`)
`SELECT p.active, p.anonymized_at FROM core.patients p WHERE p.id = NEW.patient_id AND p.clinic_id = NEW.clinic_id FOR KEY SHARE`. Sem linha: segue (a FK responde `23503`). `anonymized_at IS NOT NULL` → `CR003`; `active = false` → `CR005`. Só no `INSERT`.

### 2.3 Numeração
```
PERFORM pg_advisory_xact_lock(hashtextextended('budgets:' || NEW.clinic_id::text, 0));
NEW.number := COALESCE((SELECT max(number) FROM core.budgets WHERE clinic_id = NEW.clinic_id), 0) + 1;
```
O valor do cliente é sempre sobrescrito. A segunda transação espera o `COMMIT` da primeira e, em `READ COMMITTED`, relê o `max`. `UNIQUE (clinic_id, number)` é a rede.

### 2.4 `guard_budget_update()` — decisão
| Estado antigo | Regra |
|---|---|
| `DRAFT` | `NEW.total_amount := core.budget_items_total(OLD.id) - NEW.discount_amount`. Mudando para `SENT` ou `APPROVED`: ≥ 1 item e `total > 0`, senão `CR008`. `SENT` → `sent_at := now()`; `APPROVED`/`REJECTED` → `decided_at := now()`; permanecendo `DRAFT`, carimbos `NULL` |
| `SENT` | `NEW.status` ∈ {`SENT`, `APPROVED`, `REJECTED`}, senão `CR008`. `discount_amount`, `total_amount`, `appointment_id`, `valid_until`, `notes`, `sent_at` iguais, senão `CR007`. Ao decidir: `decided_at := now()` |
| `APPROVED`, `REJECTED` | `status` igual, senão `CR008`; conteúdo e `decided_at` iguais, senão `CR007` |

Exceção: com `current_setting('core.anonymizing', true) = 'on'`, `notes` pode virar `NULL` em qualquer estado.

### 2.5 `guard_budget_item()`
1. `SELECT status FROM core.budgets WHERE id = <budget_id> AND clinic_id = <clinic_id> FOR NO KEY UPDATE`. Sem linha: `RETURN` (a RLS ou a FK respondem, S16).
2. `status <> 'DRAFT'` → `CR007`.
3. `INSERT`: `SELECT name, base_price FROM core.procedure_types WHERE id = NEW.procedure_type_id AND clinic_id = NEW.clinic_id`; sem linha → `23503` explícito; senão `NEW.description := name; NEW.unit_price := base_price`.

### 2.6 RLS e grants
`ENABLE` + `FORCE` nas duas tabelas. `budgets`: `SELECT` `T AND A`; `INSERT` `WITH CHECK (T AND D AND U)`; `UPDATE` `USING/WITH CHECK (T AND D)`. `budget_items`: `SELECT` `T AND A`; `INSERT`/`UPDATE`/`DELETE` `T AND D`. `SELECT TO crm_backup USING (true)` nas duas. Grants: `budgets` `SELECT, INSERT, UPDATE`; `budget_items` `SELECT, INSERT, UPDATE, DELETE`; `crm_backup` `SELECT`; `GRANT EXECUTE ON FUNCTION core.budget_items_total(uuid) TO crm_app`. As políticas e grants do `crm_definer` entram na F07.

## 3. Componentes
`V024`, `U024`, `tests/budgets.sql`, `tests/budget_items.sql`, `tests/budget_status.sql`, `tests/budget_race.sh` (duas conexões), `tests/budget_numbering.sh`. Entidades JPA `Budget` e `BudgetItem` (backend da Fase 3).

## 4. Lógica
1. **Criar:** `INSERT` do cabeçalho (`DRAFT`, sem desconto) → número e total inicial pelo banco → itens (`INSERT` com `procedure_type_id`, `quantity`, `discount_amount`, `position`) → cada item copia descrição e preço e dispara o recálculo → `UPDATE` do desconto do orçamento.
2. **Enviar/aprovar:** `UPDATE budgets SET status = ...` → o guard confere itens e total, carimba e congela.
3. **Editar item:** o trigger do item trava o orçamento; em `DRAFT` passa e o `AFTER` recalcula; fora de `DRAFT`, `CR007`.
4. **Reordenar:** troca de `position` numa transação; a unicidade só é verificada no `COMMIT`.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Orçamento criado `SENT` | `CR008` |
| Desconto > 0 no `INSERT` | `23514` |
| Dois `INSERT` simultâneos na clínica | Segundo espera; números sequenciais distintos |
| Cliente envia `number`, `total_amount`, `sent_at`, `decided_at`, `unit_price`, `description` | Ignorados |
| Item de procedimento de outra clínica ou inexistente | `23503` |
| Quantidade 0 ou 1000; posição 0 ou 51; desconto de item > linha | `23514` |
| Total > 999.999,99 ou desconto > soma | `23514` |
| Item mexido com orçamento ≠ `DRAFT` | `CR007` |
| Enviar/aprovar sem itens ou com total 0 | `CR008` |
| `SENT → DRAFT`; saída de terminal | `CR008` |
| Alterar desconto, notas, validade, atendimento fora de `DRAFT` | `CR007` |
| Trocar `patient_id`, `number` | `23514` |
| `appointment_id` de outro paciente | `23503` |
| Paciente inativo / anonimizado na criação | `CR005` / `CR003` |
| Recepção grava | `INSERT` → `42501`; `UPDATE`/`DELETE` afetam 0 linhas |
| `app.role` ausente ou `'ADMIN'` | Tudo negado |
| `created_by` ≠ `app.user_id` | `42501` |

## 6. Segurança e desempenho
- Sem função `SECURITY DEFINER`; a única função com `EXECUTE` ao `crm_app` é `budget_items_total`, invoker, que lê sob a RLS do chamador.
- Preço e total não forjáveis; texto livre só em `notes` (500, texto claro).
- Custo por item: 1 lock do orçamento + 1 `SELECT` do catálogo + 1 `UPDATE` de `budgets`; salvar 20 itens ≤ 100 ms.
- Orçamentos do paciente ≤ 5 ms por `ix_budgets_patient`.
