# Contract — F02 Agendamentos (Fase 2)

## 1. Resumo
- **Feature:** F02-appointments (Fase 2)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo `scheduling`); F05 (FK da nota); F06 (auditoria); F07 (anonimização); `crm_backup`.

## 2. Entradas

O backend define, na **mesma transação**, `app.clinic_id`, `app.role` (`DENTIST_ADMIN` | `RECEPTIONIST`) e `app.user_id` (uuid), por `set_config(..., true)`. Ausentes ou inválidos, tudo é negado.

`INSERT` / `UPDATE` em `core.appointments` como `crm_app`:

```json
{
  "id": "uuid (gerado pela aplicação)",
  "clinic_id": "uuid (= app.clinic_id)",
  "patient_id": "uuid (paciente ativo da mesma clínica)",
  "starts_at": "timestamptz, segundos = 0",
  "ends_at": "timestamptz, > starts_at, duração múltipla de 5 min entre 5 min e 12 h",
  "status": "SCHEDULED | CONFIRMED | IN_PROGRESS | COMPLETED | NO_SHOW | CANCELED (default SCHEDULED)",
  "notes": "varchar(500), opcional, texto claro, SEM dado clínico",
  "procedure_type_id": "uuid, opcional, sem FK (Fase 3)",
  "created_by": "uuid (= app.user_id)",
  "version": "bigint (@Version)"
}
```

Sem `RETURNING` especial: a recepção também tem `SELECT`.

## 3. Saídas

| Coluna | Tipo | Observação |
|---|---|---|
| `id`, `clinic_id`, `patient_id`, `created_by` | `uuid` | `patient_id`, `created_by`, `clinic_id` e `id` imutáveis |
| `starts_at`, `ends_at` | `timestamptz` | Intervalo semiaberto `[starts_at, ends_at)` |
| `status` | `varchar(15)` | Seis valores |
| `notes` | `varchar(500)` | Pode ser `NULL` |
| `procedure_type_id` | `uuid` | `NULL` até a Fase 3 |
| `created_at`, `updated_at` | `timestamptz` | `updated_at` por trigger |
| `version` | `bigint` | `@Version` |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `23P01` | Conflito de horário (`ex_appt_no_overlap`), em `INSERT` ou `UPDATE` | 409 (não logar o `DETAIL`) |
| `23514` | `CHECK` de intervalo, duração, status, `notes`; coluna imutável | 422 |
| `23503` | Paciente inexistente ou de outra clínica; `created_by` de outra clínica | 404 / 500 |
| `CR003` | Paciente anonimizado | 409 |
| `CR005` | Paciente inativo | 409 |
| `42501` | Recepção gravando status proibido; `created_by` ≠ `app.user_id`; `DELETE` | 403 (o backend deve barrar antes) |
| `28000` | Sem `app.user_id` (auditoria, F06) | 500 |

Em `UPDATE` de linha que a recepção não enxerga para alterar (`IN_PROGRESS`, `COMPLETED`, `CANCELED`, `NO_SHOW`), o banco devolve **0 linhas afetadas** sem erro; o backend traduz em 403/404.

## 4. Regras e limites
- Não sobreposição **por clínica**; ocupam horário `SCHEDULED`, `CONFIRMED`, `IN_PROGRESS`, `COMPLETED`; `CANCELED` e `NO_SHOW` liberam.
- **Quem faz o quê (banco):** `DENTIST_ADMIN` grava qualquer status sobre qualquer linha. `RECEPTIONIST` insere só `SCHEDULED` e altera só linhas `SCHEDULED`/`CONFIRMED`, gravando só `SCHEDULED`, `CONFIRMED`, `CANCELED` ou `NO_SHOW`.
- **A ordem das transições é do backend** (spec global, seção 4.5); o banco aceita, por exemplo, `COMPLETED` → `SCHEDULED` para o dentista.
- Reativar cancelado ou faltoso é só do dentista e pode falhar com `23P01`, `CR005` ou `CR003`.
- A recepção cancela e confirma, mas **não reabre**: cria um agendamento novo.
- Permuta de dois horários em uma transação **não** é suportada (constraint não deferrable).
- Agenda do dia/semana: o backend calcula `[início, fim)` a partir de `clinics.timezone` e usa a consulta canônica da spec (recuo de 12 h em `starts_at` e filtro `ends_at > início`).
- `notes` não recebe dado clínico (vai no prontuário cifrado); é zerada na anonimização (F07).
- Sem `DELETE` para `crm_app`; `crm_backup` lê tudo por política nomeada.
- Ao inativar um paciente, o backend cancela antes os agendamentos futuros em aberto (senão a anonimização falha com `CR004`, F07).

## 5. Eventos emitidos
Nenhum no banco. As alterações geram linhas em `audit_log` (F06).

## 6. Critérios de aceite (integração)
- [ ] Teste 61: sobreposições → `23P01`; consecutivos passam; outra clínica passa.
- [ ] Teste 62: `CANCELED`/`NO_SHOW` liberam; reativar sobre ocupado → `23P01`; `IN_PROGRESS`/`COMPLETED` ocupam.
- [ ] Teste 63: duas conexões no mesmo horário, a segunda recebe `23P01`; com `ROLLBACK` da primeira, passa.
- [ ] Teste 64: `CHECK`s de intervalo, duração, início com segundos, status e `notes`.
- [ ] Teste 65: recepção só grava os status permitidos e só sobre `SCHEDULED`/`CONFIRMED`; demais linhas, 0 afetadas.
- [ ] Teste 66: dentista grava qualquer transição (documenta A4).
- [ ] Teste 60 (parte): `patient_id` de outra clínica → `23503`.
- [ ] Teste 74: `CR005` e `CR003` ao agendar; reativar cancelado de paciente inativo → `CR005`; `INSERT` já `CANCELED` passa; agendamento existente de paciente inativado continua legível.
- [ ] Testes 59, 67, 68, 79, 80 (parte): isolamento, `app.role` inválido, autoria, permissões e catálogo (F08).
- [ ] Teste 81 (parte): planos da consulta canônica sem `Seq Scan` (F08).
