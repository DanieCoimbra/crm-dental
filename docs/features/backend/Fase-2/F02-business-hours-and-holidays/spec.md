# Spec — F02 Horário comercial e feriados

Complementa `docs/global/backend/Fase-2/spec.md` (seções 3.2, 4.11 e 4.12).

## 1. Visão técnica
- **Feature:** F02 Horário comercial e feriados.
- **Stack:** Spring Web MVC, Spring Data JPA, Bean Validation, Spring Security.
- **Abordagem:** `BusinessHoursService` e `HolidayService` em `crm_data.scheduling.service`; controllers em `crm_data.scheduling.web`. Sem migrations: usa V016–V018.

## 2. Dados
- **Tabelas:** `core.business_hours` (`UNIQUE (clinic_id, weekday)`), `core.clinic_holidays` (`UNIQUE (clinic_id, holiday_date)`), `core.holidays` (global, leitura). Nenhuma alteração de schema.
- **Repositórios:** `BusinessHourRepository.findAll` (tenant), `ClinicHolidayRepository.findByDateBetween`, `NationalHolidayRepository.findByDateBetween`.
- **Estado em memória:** nenhum.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `BusinessHoursController` | `GET` e `PUT /business-hours` |
| `BusinessHoursService` | `weekSchedule()` (7 dias, mapa por `DayOfWeek`) e `update(days)`; `@PreAuthorize` D na escrita |
| `HolidayController` | `GET /holidays?from&to` |
| `ClinicHolidayController` | `POST`, `PUT` e `DELETE /clinic-holidays` |
| `HolidayService` | `list(from, to)`, `holidayDates(from, to)` (para F03), CRUD; `@PreAuthorize` D nas escritas |
| `AppointmentSlotRules` + `DaySchedule` (`scheduling.service`) | Regra pura de expediente, almoço, dia fechado e feriado (e `PAST`, usado só pela F03). `DaySchedule(open, opensAt, closesAt, lunchStartsAt, lunchEndsAt)` é o valor imutável que desacopla a regra da entidade e permite avaliar uma configuração **proposta** sem alterar entidades gerenciadas. Criada aqui; reutilizada pela F03 |
| `AffectedAppointmentsFinder` (`scheduling.service`) | `find(Map<DayOfWeek, DaySchedule> resultante, ZoneId)` (expediente, sem feriados) e `findOnHoliday(LocalDate, ZoneId)` → `AffectedAppointments(List<Item>, count, truncated)`; só leitura; o "agora" vem do `Clock` injetado |
| `AppointmentRepository` (acréscimo) | `findOpenFuture(now, lower)`: `status` ∈ `SCHEDULED`/`CONFIRMED`, `endsAt > :now`, ordenado por `startsAt, id` (JPQL, filtro de tenant) |
| `BusinessHoursRequests`, `BusinessHoursResponse`, `HolidayRequests`, `HolidayResponse` | DTOs sem `clinicId`, `createdBy`, `id` de entrada |

Regras de rota: `PUT /api/v1/business-hours` e `POST|PUT|DELETE /api/v1/clinic-holidays/**` exigem D.

## 4. Lógica
**`PUT /business-hours`:**
1. Validar de 1 a 7 dias, `weekday` ∈ 1..7 sem repetição, `HH:mm`, regras de aberto/fechado/almoço (acumulando erros).
2. Carregar as linhas da clínica (≤ 7). Dia sem linha → criar (`UUID` novo, `persist`; `version` do corpo deve estar ausente). Dia com linha → `version` do corpo obrigatório e igual ao da entidade, senão 409.
3. `flush`; `23505` em `ux_bh_clinic_weekday` → 409 "Dia já configurado.".
4. Responder 200 com os 7 dias relidos. Fechar um dia zera os quatro horários.

**Aviso de agendamentos afetados (`PUT /business-hours[?dryRun=true]`):**
1. Montar a configuração **resultante** em memória como `Map<DayOfWeek, DaySchedule>`: dias do corpo (já validados) sobre as linhas atuais; dia sem linha e fora do corpo = fechado.
2. `dryRun=true`: validar (inclusive `version` e duplicidade) mas **não** persistir nem tocar nas entidades; sem `dryRun`: gravar (passos 1 a 3 acima) e usar a mesma configuração resultante.
3. `AffectedAppointmentsFinder`: `now` do `Clock`; `lower = now − 12h` apenas para usar o índice; busca `findOpenFuture`; para cada agendamento, `AppointmentSlotRules.check` **sem** a regra `PAST` e com o **conjunto de feriados vazio** (o aviso do expediente trata só de dia fechado, fora do horário e almoço; feriado tem aviso próprio no `clinic-holidays`); mantém os que retornam `CLOSED_DAY`, `OUTSIDE_HOURS` ou `LUNCH`.
4. Limitar a lista a 100 itens (`truncated=true` se houver mais; `affectedCount` é o total). Resumo de pacientes em lote (`briefs`), uma consulta.
5. Resposta: `{ saved, days: [7], affectedAppointments: [...], affectedCount, truncated }`. Publica `SchedulingEvents.BusinessHoursChanged(affectedCount)` após o commit (só se `saved`).

**Aviso em feriados da clínica:** `POST` e `PUT /clinic-holidays` devolvem o feriado e `affectedAppointments` com `reason: HOLIDAY`: agendamentos futuros em aberto cuja data local (início ou término) coincide com a data nova. Mesma limitação de 100 itens; `DELETE` não avisa.

**`GET /business-hours`:** mapear as linhas; completar os dias ausentes com `configured:false`.

**Feriados:** validar janela (`to ≥ from`, ≤ 366 dias inclusive) → união nacional ∪ clínica ordenada por data (nacionais primeiro). `POST`: `name` após `strip` 2–80, data ≥ 2000-01-01; `23505` → 409. `PUT`: `version` obrigatória; `DELETE`: remoção física, 204.

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Recepção em qualquer escrita | 403 pela rota e `@PreAuthorize` |
| `version` ausente ou defasada em dia existente | 409 |
| Dia fechado com horário, aberto sem horário, almoço fora do expediente | 400 com os campos |
| Fechamento às 24:00 | 400 (máximo 23:59) |
| Criação concorrente do mesmo dia | `23505` → 409 |
| Feriado repetido na clínica | 409 |
| Feriado de outra clínica | 404 |
| Janela > 366 dias ou `to < from` | 400 |
| Linha com `24:00:00` gravada por fora | Lida como 23:59; log de aviso sem valores |
| `dryRun=true` com `version` defasada | 409 (o mesmo comportamento da gravação) |
| `dryRun=true` | Nada é gravado, nenhum evento publicado; resposta com `saved:false` |
| Mais de 100 agendamentos afetados | Lista truncada em 100, `affectedCount` com o total, `truncated:true` |
| Agendamento que já violava o expediente antes da mudança (encaixe do dentista) | Entra no aviso se continua violando, pois a avaliação é contra a nova configuração |
| Agendamento `IN_PROGRESS`, `COMPLETED`, `CANCELED`, `NO_SHOW` ou já terminado | Fora do aviso |
| Dois dentistas (ou duas abas) alterando ao mesmo tempo | `version` → 409; o aviso reflete o estado lido na transação |

## 6. Segurança e desempenho
- Só D escreve; RLS reforça o papel; `clinic_id` só do token.
- Consultas indexadas (`ux_bh_clinic_weekday`, `ux_clinic_holidays_date`, PK de `holidays`), ≤ 7 linhas de horário e poucas dezenas de feriados por período.
- Respostas com `Cache-Control: no-store`.
