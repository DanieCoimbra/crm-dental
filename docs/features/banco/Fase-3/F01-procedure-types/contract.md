# Contract — F01 Tipos de procedimento (Fase 3)

## 1. Resumo
- **Feature:** F01-procedure-types (Fase 3)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulos de catálogo, agenda e orçamento); F02 (FK dos itens); `crm_backup`.

## 2. Entradas

O backend define, na **mesma transação**, `app.clinic_id`, `app.role` (`DENTIST_ADMIN` | `RECEPTIONIST`) e `app.user_id`. Ausentes ou inválidos, tudo é negado.

`INSERT` / `UPDATE` em `core.procedure_types` como `crm_app` (só `DENTIST_ADMIN`):

```json
{
  "id": "uuid (gerado pela aplicação)",
  "clinic_id": "uuid (= app.clinic_id)",
  "name": "varchar(80), >= 2 caracteres úteis",
  "base_price": "numeric(12,2), 0 a 999999.99",
  "default_duration_minutes": "smallint, múltiplo de 5, 5 a 720",
  "color": "varchar(7), #RRGGBB",
  "active": "boolean (default true)",
  "created_by": "uuid (= app.user_id)",
  "version": "bigint (@Version)"
}
```

Em `core.appointments` (Fase 2), `procedure_type_id` passa a aceitar só procedimento **da mesma clínica** (ou `NULL`).

## 3. Saídas

| Coluna | Tipo | Observação |
|---|---|---|
| `id`, `clinic_id`, `created_by` | `uuid` | `id`, `clinic_id`, `created_by` e `created_at` imutáveis |
| `name`, `color` | `varchar` | `name` único entre os ativos, sem diferenciar caixa |
| `base_price` | `numeric(12,2)` | `BigDecimal` |
| `default_duration_minutes` | `smallint` | Só informativa para a agenda |
| `active` | `boolean` | Não há `DELETE` |
| `created_at`, `updated_at` | `timestamptz` | `updated_at` por trigger |
| `version` | `bigint` | `@Version` |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `23514` | `CHECK` (preço, duração, cor, nome); coluna imutável | 422 |
| `23505` | Nome ativo repetido (`ux_ptype_clinic_name`) | 409 |
| `23503` | `procedure_type_id` de outra clínica em `appointments` | 404 |
| `42501` | Recepção gravando; `created_by` ≠ `app.user_id`; `DELETE` | 403 (o backend barra antes) |

Em `UPDATE` feito pela recepção o banco devolve **0 linhas afetadas** sem erro.

## 4. Regras e limites
- **Quem faz o quê:** `DENTIST_ADMIN` cria e altera; `RECEPTIONIST` só lê. Sem `DELETE`: inativar é `active = false`.
- Mudar o `base_price` **não** altera orçamentos já criados (snapshot no item, F02).
- **Regra do backend:** impedir item de orçamento e agendamento novos com procedimento inativo; propor a duração do agendamento a partir de `default_duration_minutes`.
- Mapeamento: `numeric(12,2)` → `BigDecimal`; `version` → `@Version Long`; `updatable = false` nas colunas imutáveis.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 88: `CHECK`s, unicidade do nome ativo sem diferenciar caixa, reuso de nome inativo, mesma descrição em outra clínica.
- [ ] Teste 89: papéis (recepção lê e não grava, dentista grava, sem `DELETE`).
- [ ] Teste 90: FK de `appointments.procedure_type_id` (nulo passa; outra clínica `23503`); testes 59–85 da Fase 2 verdes.
- [ ] Teste 86/87 (parte): isolamento de tenant e FK cruzada.
- [ ] Teste 117 (parte): V022 e V023 aplicam do zero; `U023` e `U022` revertem e reaplicam.
