# Contract — F04 Máquina de status do agendamento

## 1. Resumo
- **Feature:** F04-appointment-status
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (botões da agenda). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`. Corpo comum: `{ "version": long }` (obrigatório). Não existe `PATCH status`.

| Método | Rota | Papéis | Efeito |
|---|---|---|---|
| POST | `/appointments/{id}/confirm` | D, R | `SCHEDULED` → `CONFIRMED` |
| POST | `/appointments/{id}/cancel` | D, R | `SCHEDULED`/`CONFIRMED` → `CANCELED` |
| POST | `/appointments/{id}/no-show` | D, R | `SCHEDULED`/`CONFIRMED` → `NO_SHOW` (agora ≥ `startsAt`) |
| POST | `/appointments/{id}/start` | D | `CONFIRMED` → `IN_PROGRESS` (no dia do agendamento) |
| POST | `/appointments/{id}/finish` | D | `IN_PROGRESS` → `COMPLETED` |
| POST | `/appointments/{id}/reactivate` | D | `CANCELED`/`NO_SHOW` → `SCHEDULED` |

## 3. Saídas

### Sucesso
- 200 `AppointmentResponse` (F03) com o `status` e a `version` novos.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | `version` ausente |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R em `start`, `finish` ou `reactivate` |
| 404 `not-found` | Agendamento inexistente ou de outra clínica |
| 409 `conflict` | Transição fora da tabela (com o status atual); `version` defasada; já existe `IN_PROGRESS`; reativação sobre horário ocupado, paciente inativo ou anonimizado |
| 422 `business-rule` | `reason` = `NOT_STARTED` (no-show antes do horário) ou `NOT_TODAY` (start fora do dia local) |

## 4. Regras e limites
- Toda transição é validada no servidor; o banco só valida a lista de status e o que a recepção grava.
- `COMPLETED` é terminal: sem ação e sem `PUT`. `IN_PROGRESS` só sai por `finish`.
- Só um `IN_PROGRESS` por clínica (regra do backend). **Limitação aceita:** não há `UNIQUE` parcial no banco; dois `start` simultâneos de agendamentos diferentes podem passar (dentista solo). Se a clínica passar a ter mais de um profissional, pedir à função `banco` um índice único parcial sobre `status = 'IN_PROGRESS'` e traduzir o `23505` em 409.
- `reactivate` não reaplica expediente, feriado nem passado; reavalia conflito e paciente.
- Cancelar libera o horário imediatamente.

### Interfaces internas
```java
// crm_data.scheduling.service.AppointmentTransitions
enum Action { CONFIRM, CANCEL, NO_SHOW, START, FINISH, REACTIVATE }
Set<AppointmentStatus> sources(Action a);
AppointmentStatus target(Action a);
Set<Role> roles(Action a);
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Appointment.StatusChanged` | Após o commit de cada transição | `{appointmentId, from, to, userId}` |

## 6. Critérios de aceite (integração)
- [ ] Toda a tabela de transições testada, inclusive as proibidas (409 com o status atual).
- [ ] R recebe 403 em `start`, `finish` e `reactivate` sem nenhuma instrução no banco; D executa.
- [ ] `no-show` antes de `startsAt` → 422; `start` em outro dia local → 422; segundo `IN_PROGRESS` → 409.
- [ ] `cancel` libera o horário: novo agendamento no mesmo horário passa.
- [ ] `reactivate` sobre horário ocupado → 409; paciente inativo → 409; anonimizado → 409.
- [ ] Com o filtro Hibernate desligado e `app.role = RECEPTIONIST` forjado, `IN_PROGRESS`/`COMPLETED` não são gravados e linhas em estado do dentista não mudam.
- [ ] `version` defasada → 409 em todas as ações.
