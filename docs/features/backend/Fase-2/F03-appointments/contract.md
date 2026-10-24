# Contract — F03 Agendamentos

## 1. Resumo
- **Feature:** F03-appointments
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (agenda do dia/semana, criação e remarcação), F04 (transições) e F05 (histórico). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`. Instantes em ISO-8601 com deslocamento; `from`/`to` são datas locais da clínica.

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| POST | `/appointments` | D, R | `{ "patientId": uuid, "startsAt": instant, "endsAt": instant, "notes": "≤500"?, "allowOutsideHours": bool? }` |
| GET | `/appointments` | D, R | `?from=AAAA-MM-DD&to=AAAA-MM-DD&status=… (repetível, opcional)`; janela ≤ 42 dias, `to` inclusivo |
| GET | `/appointments/{id}` | D, R | — |
| PUT | `/appointments/{id}` | D, R | `{ "startsAt", "endsAt", "notes": "≤500|null", "allowOutsideHours": bool?, "version": long }` |

`status` ∈ `SCHEDULED`, `CONFIRMED`, `IN_PROGRESS`, `COMPLETED`, `NO_SHOW`, `CANCELED`. `clinicId`, `createdBy`, `status`, `id`, `procedureTypeId` e `patientId` (no `PUT`) do corpo são ignorados.

## 3. Saídas

### Sucesso
- **Agendamento** (201 com cabeçalho `Location: /api/v1/appointments/{id}`; 200 nas demais):
  ```json
  { "id": "uuid", "patient": { "id": "uuid", "name": "string", "phone": "string|null" },
    "startsAt": "instant", "endsAt": "instant", "status": "SCHEDULED",
    "notes": "string|null", "createdBy": "uuid", "createdAt": "instant", "updatedAt": "instant", "version": 0 }
  ```
- **Agenda** (200, `Cache-Control: no-store`): lista de `AppointmentResponse` ordenada por `startsAt`, `id`, sem paginação.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | Duração fora de 5 min–12 h ou não múltipla de 5; `startsAt` com segundos; `endsAt ≤ startsAt`; `notes` > 500 ou só espaços; `from`/`to`/`status` inválidos; janela > 42 dias |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | R com `allowOutsideHours=true` |
| 404 `not-found` | Paciente ou agendamento inexistente / de outra clínica |
| 409 `conflict` | "Horário já ocupado." (inclusive a transação perdedora de uma corrida: deadlock `40P01`/`40001` do `EXCLUDE`); paciente anonimizado ou inativo; `version` defasada; status não permite `PUT` |
| 422 `business-rule` | Propriedade `reason` ∈ `CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`, `HOLIDAY`, `PAST` |

## 4. Regras e limites
- Intervalo semiaberto `[startsAt, endsAt)`: consecutivos não conflitam. `CANCELED` e `NO_SHOW` não ocupam horário.
- A recepção só agenda no futuro (tolerância de 5 min) e dentro do expediente, fora do almoço e de feriados; o dentista pode encaixar (`allowOutsideHours=true`) e registrar no passado.
- Remarcar só vale para `SCHEDULED`/`CONFIRMED`; mudar o horário de um `CONFIRMED` o devolve a `SCHEDULED`. O paciente de um agendamento não muda.
- O conflito é decidido pelo banco; a resposta 409 não revela o outro agendamento. A agenda (`GET`) mostra a ocupação.
- `notes` é texto claro e **não leva dado clínico**. A recepção lê esse campo e a cifra do prontuário não o protege: a tela deve rotular o campo como "Observação logística (sem dados de saúde)" e orientar o uso das notas clínicas para o resto.

### Interfaces internas
```java
// crm_data.scheduling.service.AppointmentSlotRules (criada na F02; a F03 só a consome)
Optional<Reason> check(Instant start, Instant end, Instant now, ZoneId zone,
                       Map<DayOfWeek, DaySchedule> schedule, Set<LocalDate> holidays);
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `Appointment.Created` | Após o commit da criação | `{appointmentId, patientId, userId}` |
| `Appointment.Rescheduled` | Horário alterado | `{appointmentId, userId}` |
| `Appointment.OutsideHoursBooked` | Dentista encaixou com `allowOutsideHours=true` onde a regra bloquearia | `{appointmentId, userId}` |

## 6. Critérios de aceite (integração)
- [ ] D e R criam (201); sem token, 401; R com `allowOutsideHours=true`, 403.
- [ ] Conflito parcial, total e contido → 409; consecutivo e outra clínica passam; cancelado libera o horário.
- [ ] 20 repetições de duas criações simultâneas: exatamente um 201 e um 409, nenhum 500.
- [ ] R recebe 422 com cada `reason` (`CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`, `HOLIDAY`, `PAST`); D passa com `allowOutsideHours`.
- [ ] Paciente inativo/anonimizado → 409; de outra clínica → 404.
- [ ] A agenda inclui o atendimento que começou antes da janela e entra nela; respeita o fuso da clínica.
- [ ] `PUT` remarca com conflito/expediente/passado; `CONFIRMED` volta a `SCHEDULED`; outros status → 409.
- [ ] Nenhum `notes`, nome ou telefone em log; nenhuma linha de outra clínica (filtro desligado).
- [ ] Agenda da semana p95 ≤ 100 ms com 20 mil agendamentos.
