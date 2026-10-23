# Contract — F03 Horário comercial (Fase 2)

## 1. Resumo
- **Feature:** F03-business-hours (Fase 2)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo `scheduling`, configurações); `crm_backup`.

## 2. Entradas

O backend define `app.clinic_id`, `app.role` e `app.user_id` na mesma transação. Escrita só com `app.role = DENTIST_ADMIN`.

`INSERT` / `UPDATE` em `core.business_hours`:

```json
{
  "id": "uuid (gerado pela aplicação)",
  "clinic_id": "uuid (= app.clinic_id)",
  "weekday": "smallint 1..7 (1 = segunda, 7 = domingo)",
  "is_open": "boolean",
  "opens_at": "time(0), hora LOCAL da clínica; obrigatório se is_open, nulo se não",
  "closes_at": "time(0), > opens_at",
  "lunch_starts_at": "time(0), opcional, opens_at < lunch_starts_at",
  "lunch_ends_at": "time(0), obrigatório com lunch_starts_at, lunch_starts_at < lunch_ends_at < closes_at",
  "created_by": "uuid (= app.user_id)",
  "version": "bigint (@Version)"
}
```

## 3. Saídas

Linhas de `core.business_hours` com as mesmas colunas, mais `created_at`, `updated_at`. No máximo uma por (`clinic_id`, `weekday`), portanto até 7 por clínica.

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `23514` | `CHECK` de expediente, almoço ou `weekday`; coluna imutável | 422 |
| `23505` | Dia da semana já configurado (`ux_bh_clinic_weekday`) | 409 (ou usar `ON CONFLICT DO UPDATE`) |
| `42501` | Recepção tentando gravar; `created_by` ≠ `app.user_id`; `DELETE` | 403 |

`UPDATE` feito pela recepção afeta 0 linhas (a RLS esconde a linha para alterar).

## 4. Regras e limites
- Uma linha por (clínica, dia). Dia **sem linha** = não configurado; o backend o trata como fechado.
- Dia fechado = `is_open = false` com os quatro horários nulos; **não há `DELETE`**.
- Os horários são **locais** da clínica; a conversão para `timestamptz` usa `clinics.timezone` e é do backend.
- Expediente não atravessa a meia-noite (`opens_at < closes_at`); `'24:00:00'` é aceito como fechamento.
- No máximo um intervalo de almoço por dia.
- **O banco não impede agendamento fora do horário comercial**; o backend avisa ou bloqueia (decisão A8).
- Leitura para `DENTIST_ADMIN` e `RECEPTIONIST`; escrita só `DENTIST_ADMIN`.
- Sem auditoria nesta tabela.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 70: dia fechado sem horários passa; fechado com horário falha; aberto sem horário falha; `opens_at >= closes_at` falha; almoço fora do expediente, só uma ponta ou em dia fechado falham; `weekday` 0 e 8 falham; segunda linha do dia falha (`23505`).
- [ ] Teste 69 (parte): recepção lê e não grava (`INSERT` falha; `UPDATE` afeta 0); dentista grava; `crm_app` sem `DELETE`.
- [ ] Teste 59 (parte): isolamento por clínica e sem tenant (F08).
- [ ] Teste 67/68 (parte): `app.role` inválido e autoria (F08).
- [ ] Teste 80 (parte): `ENABLE` + `FORCE`, política por operação e `SELECT TO crm_backup`.
