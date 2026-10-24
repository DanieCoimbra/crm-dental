# PRD — F03 Agendamentos

**Derivação:** PRD global da Fase 2, US-P2-04, US-P2-05 e US-P2-06, FR-1 (criação e remarcação), FR-2, FR-4 a FR-6, FR-8 e FR-12; decisões G1 e G4. Spec global, seções 4.2 a 4.6.
**Dependências externas:** F01 (entidades, `ClinicZones`, `PatientLookup.requireSchedulable/briefs`, tradução de erros), F02 (`weekSchedule`, `holidayDates` e a regra pura `AppointmentSlotRules`/`DaySchedule`, criada lá), migrations V015 e V020. Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** a clínica ainda não consegue marcar atendimentos; o dentista precisa de uma agenda em que dois atendimentos nunca ocupem o mesmo horário, mesmo com recepção e dentista marcando ao mesmo tempo.
- **Solução:** criar, consultar e remarcar agendamentos com o conflito resolvido pela constraint do banco, validação de duração, expediente, feriado e passado no servidor, e listagem por janela local.
- **Metas:**
  1. Duas criações simultâneas para o mesmo horário e clínica resultam em exatamente um 201 e um 409, em 20 repetições, sem 500.
  2. Agenda da semana p95 ≤ 100 ms com 20 mil agendamentos e 4 requisições concorrentes; criação p95 ≤ 100 ms.
  3. A recepção nunca agenda fora do expediente, em feriado ou no passado; o dentista pode (encaixe e passado).
  4. Cobertura ≥ 80% em `AppointmentService` e `AppointmentSlotRules`.

## 2. User stories

### US-001: Criar agendamento
**Descrição:** Como recepcionista ou dentista, quero marcar um atendimento para um paciente num horário livre.

**Critérios de aceite:**
- [ ] `POST /api/v1/appointments` (D, R) recebe `patientId`, `startsAt`, `endsAt`, `notes?`, `allowOutsideHours?`; cria `SCHEDULED` e devolve 201. `clinicId`, `createdBy`, `status`, `id` e `procedureTypeId` nunca vêm do corpo.
- [ ] 400 com `errors[]`: duração múltipla de 5 min entre 5 min e 12 h, `startsAt` sem segundos, `endsAt > startsAt`, `notes` ≤ 500 e não só espaços.
- [ ] Conflito (parcial, total ou contido) → 409 "Horário já ocupado."; consecutivos passam; outra clínica passa; `CANCELED`/`NO_SHOW` não ocupam.
- [ ] Paciente inexistente → 404; anonimizado ou inativo → 409.
- [ ] Recepção: fora do expediente, dia fechado, almoço ou feriado → 422 com `reason` (`CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`, `HOLIDAY`); passado (início < agora − 5 min) → 422 `PAST`. Recepção enviando `allowOutsideHours=true` → 403.
- [ ] Dentista com `allowOutsideHours=true` ignora expediente e feriado; passado é sempre aceito para ele; o encaixe gera evento de log (só identificadores).

### US-002: Consultar a agenda
**Descrição:** Como recepcionista ou dentista, quero ver a agenda do dia ou da semana e abrir um agendamento.

**Critérios de aceite:**
- [ ] `GET /api/v1/appointments?from&to&status` (D, R) devolve os agendamentos que intersectam `[from 00:00, to+1 00:00)` no fuso da clínica, ordenados por `startsAt` e `id`; todos os status por padrão; `status` repetível filtra.
- [ ] `from`/`to` ausentes ou inválidos, `to < from`, janela > 42 dias ou `status` desconhecido → 400.
- [ ] Cada item traz `id`, `patient {id, name, phone}`, `startsAt`, `endsAt`, `status`, `notes`, `createdBy`, `createdAt`, `updatedAt`, `version`; nunca `clinicId` nem `procedureTypeId`.
- [ ] `GET /appointments/{id}` devolve um agendamento; de outra clínica → 404. Resposta com `Cache-Control: no-store`.
- [ ] Agendamento de paciente anonimizado continua listado, com o nome anonimizado.

### US-003: Remarcar e editar observação
**Descrição:** Como recepcionista ou dentista, quero mudar o horário ou a observação de um agendamento que ainda não começou.

**Critérios de aceite:**
- [ ] `PUT /api/v1/appointments/{id}` (D, R) com `startsAt`, `endsAt`, `notes` (substitui; `null` limpa), `allowOutsideHours?` e `version`; versão defasada → 409.
- [ ] Só `SCHEDULED` e `CONFIRMED`; outro status → 409 com o status atual. Mudar o horário de um `CONFIRMED` o devolve a `SCHEDULED`.
- [ ] Conflito, expediente, feriado e passado valem para o novo horário apenas quando `startsAt`/`endsAt` mudam.
- [ ] `patientId` no corpo é ignorado. Linha alterada por outra transação (0 linhas) → 409, nunca 500.

## 3. Requisitos funcionais

- **FR-1:** `AppointmentService` com criação, consulta, listagem e remarcação; `flush` explícito para traduzir `23P01`, `CR003` e `CR005`.
- **FR-2:** Aplicar a `AppointmentSlotRules` da F02 (expediente, almoço, dia fechado, feriado, passado) e `allowOutsideHours` só para D.
- **FR-3:** Listagem por janela local com recuo de 12 h e dois métodos de repositório (com e sem filtro de status).
- **FR-4:** Resumo do paciente em lote, sem N+1.
- **FR-5:** Eventos `Created`, `Rescheduled` e `OutsideHoursBooked` para log após o commit.
- **FR-6:** Sem `SELECT … FOR UPDATE`, lock do backend nem pré-checagem por `&&`.

## 4. Fora do escopo

- Mudanças de status (F04); histórico do paciente (F05).
- `procedure_type_id`, recorrência, lembretes, múltiplos profissionais.
- Paginação da agenda (volume de dentista solo).

## 5. Considerações técnicas e riscos

- `23P01` aparece no `flush`/commit: o `flush` explícito no service evita o 500.
- O recuo de 12 h só vale enquanto o teto de duração for 12 h.
- `notes` é texto claro: a documentação da API avisa que não leva dado clínico.
