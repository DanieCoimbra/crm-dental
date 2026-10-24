# Contract — F02 Horário comercial e feriados

## 1. Resumo
- **Feature:** F02-business-hours-and-holidays
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (configuração da clínica e agenda) e F03 (regra de expediente). Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Corpo / query |
|---|---|---|---|
| GET | `/business-hours` | D, R | — |
| PUT | `/business-hours` | D | `?dryRun=true (opcional; valida e avisa sem gravar)`; corpo `{ "days": [ { "weekday": 1-7, "isOpen": bool, "opensAt": "HH:mm"?, "closesAt": "HH:mm"?, "lunchStartsAt": "HH:mm"?, "lunchEndsAt": "HH:mm"?, "version": long? } ] }` (1 a 7 dias distintos) |
| GET | `/holidays` | D, R | `?from=AAAA-MM-DD&to=AAAA-MM-DD` (janela ≤ 366 dias) |
| POST | `/clinic-holidays` | D | `{ "date": "AAAA-MM-DD", "name": "2-80" }` |
| PUT | `/clinic-holidays/{id}` | D | `{ "date", "name", "version" }` |
| DELETE | `/clinic-holidays/{id}` | D | — |

Horários são locais da clínica (`clinics.timezone`). A clínica vem só do token.

## 3. Saídas

### Sucesso
- **Horário comercial** (200): lista de 7 itens `{ "weekday", "configured", "isOpen", "opensAt", "closesAt", "lunchStartsAt", "lunchEndsAt", "version" }`; dia sem linha: `configured:false, isOpen:false`, horários e `version` nulos.
- **`PUT /business-hours`** (200):
  ```json
  { "saved": true,
    "days": [ /* 7 itens, como no GET */ ],
    "affectedAppointments": [
      { "id": "uuid", "patient": { "id": "uuid", "name": "string", "phone": "string|null" },
        "startsAt": "instant", "endsAt": "instant", "status": "SCHEDULED|CONFIRMED",
        "reason": "CLOSED_DAY|OUTSIDE_HOURS|LUNCH" } ],
    "affectedCount": 0, "truncated": false }
  ```
  Com `?dryRun=true`: `saved:false` e nada é gravado. `affectedAppointments` traz no máximo 100 itens ordenados por `startsAt`; `affectedCount` é o total.
- **Feriados** (200): lista `{ "date", "name", "source": "NATIONAL|CLINIC", "id"?, "version"? }` ordenada por data.
- **Feriado da clínica** (`POST` 201, `PUT` 200): `{ "holiday": { …item com source "CLINIC" }, "affectedAppointments": [ … reason "HOLIDAY" ], "affectedCount", "truncated" }`. `DELETE` → 204, sem aviso.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | Dia repetido, `weekday` fora de 1–7, horários incoerentes, 24:00, nome fora de 2–80, data < 2000-01-01, janela inválida |
| 401 `unauthorized` | Token ausente ou inválido |
| 403 `forbidden` | `RECEPTIONIST` em qualquer escrita |
| 404 `not-found` | Feriado inexistente ou de outra clínica |
| 409 `conflict` | `version` defasada/ausente em dia existente; dia ou data de feriado duplicados |

## 4. Regras e limites
- Dia sem linha = fechado. Fechar um dia é `isOpen=false`; não há `DELETE` de horário.
- Aberto exige `opensAt < closesAt` (máx. 23:59); almoço com as duas pontas e `opensAt < lunchStartsAt < lunchEndsAt < closesAt`; fechado não aceita horários.
- Feriado nacional é só leitura; repetir uma data nacional como feriado da clínica é aceito.
- O `PUT` em lote é atômico: se um dia falha, nenhum é gravado.
- **O aviso é informativo:** a mudança é gravada mesmo com agendamentos afetados; nada é cancelado, remarcado ou bloqueado. Entram no aviso só os agendamentos `SCHEDULED`/`CONFIRMED` com `endsAt` posterior a agora; a avaliação usa a configuração resultante completa (dias do corpo + dias atuais; dia sem linha = fechado).
- O aviso do expediente cobre dia fechado, fora do horário e almoço; feriado tem aviso próprio em `clinic-holidays`.
- A lista traz nome e telefone de pacientes só para `DENTIST_ADMIN` (as rotas de escrita são D-only); nunca vai a log.

### Interfaces internas
```java
// crm_data.scheduling.service.BusinessHoursService
Map<DayOfWeek, DaySchedule> weekSchedule();

// crm_data.scheduling.service.HolidayService
Set<LocalDate> holidayDates(LocalDate from, LocalDate to);   // nacionais ∪ clínica

// crm_data.scheduling.service.DaySchedule (valor imutável)
record DaySchedule(boolean open, LocalTime opensAt, LocalTime closesAt,
                   LocalTime lunchStartsAt, LocalTime lunchEndsAt) {}

// crm_data.scheduling.service.AppointmentSlotRules (criada na F02, reutilizada pela F03)
Optional<Reason> check(Instant start, Instant end, Instant now, ZoneId zone,
                       Map<DayOfWeek, DaySchedule> schedule, Set<LocalDate> holidays);

// crm_data.scheduling.service.AffectedAppointmentsFinder
AffectedAppointments find(Map<DayOfWeek, DaySchedule> resulting, ZoneId zone);        // expediente; só leitura, "agora" vem do Clock
AffectedAppointments findOnHoliday(LocalDate date, ZoneId zone);                       // feriado novo ou movido; só leitura
```

## 5. Eventos emitidos (internos)
| Evento | Gatilho | Payload |
|---|---|---|
| `BusinessHours.Changed` | Após o commit de um `PUT` que gravou (não em `dryRun`) | `{userId, affectedCount}` (sem nomes) |

## 6. Critérios de aceite (integração)
- [ ] R recebe 403 em `PUT /business-hours` e nas três escritas de `clinic-holidays`; D e R leem.
- [ ] `GET /business-hours` devolve 7 itens; dia ausente vem `configured:false`.
- [ ] `PUT` cria e depois atualiza com `version`; versão defasada → 409; falha em um dia não grava os outros.
- [ ] Validações do contrato retornam 400 com todos os campos inválidos.
- [ ] Com agendamentos futuros marcados, encurtar o expediente, fechar um dia ou criar um almoço devolve exatamente os agendamentos afetados, com `reason` correto, `affectedCount` e `truncated`; agendamentos terminados, cancelados, faltosos, `IN_PROGRESS` e passados não aparecem.
- [ ] `?dryRun=true` devolve o mesmo aviso, `saved:false` e **não** altera nenhuma linha (conferido por leitura posterior e sem linhas novas de auditoria); `version` defasada em `dryRun` → 409.
- [ ] Mais de 100 afetados: 100 itens, `affectedCount` com o total, `truncated:true`.
- [ ] Criar ou mover um feriado da clínica sobre dias com agendamentos futuros devolve esses agendamentos com `reason: HOLIDAY`; `DELETE` não avisa.
- [ ] Nenhum nome ou telefone de paciente em log; o aviso nunca altera um agendamento.
- [ ] `GET /holidays` devolve a união (seed de 2026 confere); janela de 367 dias → 400.
- [ ] Feriado repetido → 409; a mesma data em outra clínica passa; `DELETE` → 204; de outra clínica → 404.
- [ ] Nenhuma linha de outra clínica em nenhuma resposta (filtro desligado nos testes de isolamento).
