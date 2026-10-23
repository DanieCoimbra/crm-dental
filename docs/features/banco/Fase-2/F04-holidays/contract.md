# Contract — F04 Feriados (Fase 2)

## 1. Resumo
- **Feature:** F04-holidays (Fase 2)
- **Versão:** 1.0.0
- **Consumidores:** backend (módulo `scheduling`, aviso de feriado e configurações); `crm_backup`; operação (migrations de seed).

## 2. Entradas

**`core.holidays`:** a aplicação **não escreve**. Atualizações são migrations de seed executadas como `crm_owner`.

**`core.clinic_holidays`:** o backend define `app.clinic_id`, `app.role`, `app.user_id` na mesma transação; escrita só com `DENTIST_ADMIN`.

```json
{
  "id": "uuid (gerado pela aplicação)",
  "clinic_id": "uuid (= app.clinic_id)",
  "holiday_date": "date >= 2000-01-01, único por clínica",
  "name": "varchar(80), mínimo 2 caracteres",
  "created_by": "uuid (= app.user_id)",
  "version": "bigint (@Version)"
}
```
`DELETE` permitido ao dentista da clínica.

## 3. Saídas

| Tabela | Colunas | Quem lê |
|---|---|---|
| `core.holidays` | `holiday_date date` (PK), `name varchar(80)` | `crm_app` (qualquer sessão), `crm_backup` |
| `core.clinic_holidays` | `id`, `clinic_id`, `holiday_date`, `name`, `created_by`, `created_at`, `updated_at`, `version` | `DENTIST_ADMIN` e `RECEPTIONIST` da clínica; `crm_backup` |

### Erros
| SQLSTATE | Causa | Resposta sugerida |
|---|---|---|
| `23505` | Data já cadastrada na clínica (`ux_clinic_holidays_date`) | 409 |
| `23514` | Data anterior a 2000 ou nome curto; coluna imutável | 422 |
| `42501` | Recepção gravando; escrita em `holidays` por `crm_app`; `created_by` ≠ `app.user_id` | 403 |

`UPDATE`/`DELETE` pela recepção, ou de feriado de outra clínica, afetam 0 linhas.

## 4. Regras e limites
- Seed nacional: **50 linhas**, 2026–2030, nove datas fixas por ano (01/01, 21/04, 01/05, 07/09, 12/10, 02/11, 15/11, 20/11, 25/12) mais a Sexta-feira Santa (2026-04-03, 2027-03-26, 2028-04-14, 2029-03-30, 2030-04-19). Carnaval e Corpus Christi não entram; a clínica os cadastra se quiser.
- O backend **une** `holidays` e `clinic_holidays` e decide avisar ou bloquear; o banco não impede agendar em feriado.
- `holidays` é legível mesmo sem `app.clinic_id` (dado global); `clinic_holidays` exige sessão válida.
- `clinic_holidays` é a única tabela da aplicação com `DELETE` físico (S5).
- Anos depois de 2030 exigem nova migration de seed.
- Sem auditoria.

## 5. Eventos emitidos
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] Teste 72: 50 linhas; as cinco Sextas-feiras Santas presentes; rodar duas vezes mantém 50; datas únicas.
- [ ] Teste 69 (parte): `crm_app` não faz `INSERT/UPDATE/DELETE` em `holidays`; `crm_owner` consegue `INSERT` sob `FORCE`; recepção lê e não grava `clinic_holidays`; só o dentista remove.
- [ ] Teste 71: data repetida na mesma clínica falha (`23505`); em outra clínica passa; data anterior a 2000 falha.
- [ ] Teste 59 (parte): isolamento de `clinic_holidays`; `holidays` legível sem sessão (F08).
- [ ] Teste 79/80 (parte): `DELETE` só em `clinic_holidays`; `ENABLE` + `FORCE` e `SELECT TO crm_backup` (F08).
