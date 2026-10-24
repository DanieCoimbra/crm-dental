# Spec — F03 Agendamentos

Complementa `docs/global/backend/Fase-2/spec.md` (seções 3.2, 4.2 a 4.6 e 5).

## 1. Visão técnica
- **Feature:** F03 Agendamentos.
- **Stack:** Spring Web MVC, Spring Data JPA (JPQL), Bean Validation, `java.time`, `Clock`, Spring Security.
- **Abordagem:** `AppointmentService` em `crm_data.scheduling.service` (a `AppointmentSlotRules` vem da F02); `AppointmentController` em `web`. Sem migrations: usa V015 e V020.

## 2. Dados
- **Tabela:** `core.appointments` (`ex_appt_no_overlap`, `ix_appt_clinic_start`, `ix_appt_patient`). Nenhuma alteração de schema.
- **`Appointment`:** campos editáveis `startsAt`, `endsAt`, `status`, `notes`; `patientId` e `createdBy` imutáveis; `@Version`.
- **Repositório:** `findWindow(from, to, ini)` e `findWindowByStatus(from, to, ini, statuses)` em JPQL: `startsAt >= :lower AND startsAt < :to AND endsAt > :from ORDER BY startsAt, id`.
- **Estado em memória:** nenhum; "agora" via `Clock`.

## 3. Componentes
| Componente | Responsabilidade |
|---|---|
| `AppointmentController` | `POST /appointments`, `GET /appointments`, `GET /appointments/{id}`, `PUT /appointments/{id}` |
| `AppointmentService` | Orquestra validação, paciente, regra de horário, persistência e resposta |
| `AppointmentSlotRules` (F02) | Regra pura reutilizada: `Optional<Reason> check(start, end, now, zone, Map<DayOfWeek, DaySchedule>, holidays)`; aqui só é chamada |
| `SchedulingRules` | Constantes: 5 min de tolerância, 42 dias, recuo de 12 h, passo de 5 min, 500 caracteres |
| `SchedulingEvents` / `SchedulingAuditLogger` | `Created`, `Rescheduled`, `OutsideHoursBooked`; log após commit só com ids |
| `AppointmentRequests`, `AppointmentResponse` | DTOs; resposta com `patient {id,name,phone}` vindo de `PatientLookup.briefs` |

## 4. Lógica
**Criar:** (1) validar entrada → 400; (2) R com `allowOutsideHours=true` → 403; (3) `requireSchedulable` → 404/409; (4) quando a regra se aplica, ler fuso, `weekSchedule` e `holidayDates(dia−1, dia+1)`; (5) `AppointmentSlotRules`: `PAST` só bloqueia R; `HOLIDAY`, `CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH` bloqueiam R e D sem `allowOutsideHours` → 422 com `reason`; (6) `persist` + `flush`, traduzindo `23P01`/`CR003`/`CR005`; (7) eventos; (8) 201.

**Regra de horário:** converter para o fuso; feriado se a data inicial (ou a final, se diferente e após 00:00) está no conjunto; início e fim em datas locais diferentes → `OUTSIDE_HOURS`; dia ausente/fechado → `CLOSED_DAY`; fora de `[opensAt, closesAt]` → `OUTSIDE_HOURS`; interseção com `[lunchStartsAt, lunchEndsAt)` → `LUNCH`. Prioridade: `PAST`, `HOLIDAY`, `CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`.

**Listar:** validar `from`/`to`/`status`; `ini`/`fim` no fuso da clínica; consulta com recuo de 12 h; `briefs` em lote; `no-store`.

**Remarcar:** validar; carregar (404); `version` ≠ → 409; status ≠ `SCHEDULED`/`CONFIRMED` → 409; se o horário mudou, aplicar `PAST` (R) e regras de horário, e `CONFIRMED` → `SCHEDULED`; `notes` substitui; `flush` com tradução de `23P01` e de 0 linhas/`OptimisticLockException` (409).

## 5. Erros e bordas
| Cenário | Tratamento |
|---|---|
| Criações simultâneas no mesmo horário | Uma 201 e uma 409 (`23P01`); sem 500 |
| `23P01` só no commit | Handler global traduz pela causa |
| Paciente de outra clínica / inexistente | 404 |
| Paciente anonimizado ou inativo | 409 |
| Recepção com `allowOutsideHours=true` | 403 antes das demais regras |
| Término exatamente à meia-noite ou em outro dia local | 422 `OUTSIDE_HOURS` |
| `from`/`to` mal formados, janela > 42 dias, `status` inválido | 400 |
| Agendamento de outra clínica | 404 |
| `PUT` em `IN_PROGRESS`/`COMPLETED`/`CANCELED`/`NO_SHOW` | 409 com o status atual |
| Linha alterada por outra transação | 409 |

## 6. Segurança e desempenho
- D e R acessam; encaixe e passado só D, checados no servidor; `clinicId`/`createdBy` só do token; `procedureTypeId` nunca exposto.
- Log só com identificadores; `notes`, nome e telefone nunca em log nem em `toString`.
- Agenda da semana p95 ≤ 100 ms (banco ≤ 10 ms, sem `Seq Scan`); criação p95 ≤ 100 ms; uma consulta de resumo de pacientes por listagem.
