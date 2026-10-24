# Rastreabilidade — Fase 2 (Agenda)

Cada história do PRD global (`docs/global/backend/Fase-2/prd.md`, seção 4) ligada à feature que a implementa e aos testes que a provam. Caminhos relativos a `back-java/src/test/java/crm_data/`. `IT` = integração contra PostgreSQL real (Testcontainers, V001–V021).

| História | Feature | Testes que provam |
|---|---|---|
| **US-P2-01** Fuso da clínica | F01 | `phase2/SchedulingPlatformIT` (default, troca pelo dentista, 400, 403, trigger do banco), `clinic/service/ClinicZonesServiceTest`, `clinic/service/ClinicServiceTest`, `clinic/web/ClinicUsersWebTest`, `scheduling` `AppointmentsIT.theWindowFollowsTheClinicTimezone` |
| **US-P2-02** Horário comercial | F02 | `phase2/BusinessHoursHolidaysIT` (cria/atualiza, 409 de versão, atomicidade, leitura de 24:00, valores cru no banco, isolamento), `scheduling/service/BusinessHoursServiceTest`, `scheduling/web/SchedulingConfigWebTest`, `scheduling/service/DayScheduleTest` |
| **US-P2-02** Aviso de impacto e `dryRun` | F02 | `BusinessHoursHolidaysIT.changingTheHoursWarns…`, `…dryRunWithAStaleVersion…`, `scheduling/service/AffectedAppointmentsFinderTest`, `AppointmentSlotRulesTest` |
| **US-P2-03** Feriados | F02 | `BusinessHoursHolidaysIT` (união, janela, CRUD, duplicidade, aviso `HOLIDAY`, seed de 2026), `scheduling/service/HolidayServiceTest`, `SchedulingConfigWebTest` |
| **US-P2-04** Criar agendamento | F03 | `phase2/AppointmentsIT` (papéis, timestamps, conflitos, **corrida de 20 rodadas**, razões 422, encaixe, passado, estados do paciente), `scheduling/service/AppointmentServiceTest`, `scheduling/web/AppointmentWebTest`, `AppointmentSlotRulesTest` |
| **US-P2-05** Consultar a agenda | F03 | `AppointmentsIT` (janela local, ordem, atendimento que atravessa a meia-noite, filtro de status, limites de 42 dias, outra clínica), `AppointmentWebTest`, `AppointmentServiceTest` |
| **US-P2-06** Remarcar e editar observação | F03 | `AppointmentsIT.reschedule…`, `aConfirmedAppointment…`, `editingOnlyTheNotes…`, `AppointmentServiceTest` |
| **US-P2-07** Confirmar, cancelar, falta | F04 | `phase2/AppointmentStatusIT` (recepção, liberação do horário, `NOT_STARTED`, tabela completa), `scheduling/service/AppointmentTransitionsTest`, `AppointmentStatusServiceTest`, `scheduling/web/AppointmentStatusWebTest` |
| **US-P2-08** Iniciar e finalizar | F04 | `AppointmentStatusIT.startAndFinish…` (dia local, único em andamento, terminal), `…startOutsideTheLocalDay…`, `AppointmentStatusServiceTest` |
| **US-P2-09** Reativar | F04 | `AppointmentStatusIT.reactivateReevaluates…`, `…twoSimultaneousReactivations…`, `…aMissedAppointmentIsReactivated…` |
| **US-P2-10** Histórico do paciente | F05 | `phase2/ClinicalIntegrationIT.theHistoryIsPaged…`, `…anInactiveOrAnonymizedPatient…`, `scheduling/web/PatientAppointmentWebTest`, `AppointmentServiceTest.history…` |
| **US-P2-11** Inativar e anonimizar com a agenda | F05 | `ClinicalIntegrationIT` (cancelamento dos futuros, auditoria por agendamento, `IN_PROGRESS` recusa, idempotência, `CR004`, `notes` nulos), `scheduling/service/PatientDeactivationListenerTest`, `patient/service/PatientServiceTest` |
| **US-P2-12** Nota × atendimento | F05 | `ClinicalIntegrationIT` (vincular, 409, troca, desvincular, **disputa de notas em 10 rodadas**), `record/service/ClinicalNoteServiceTest`, `record/web/RecordControllersWebTest`, `scheduling/service/AppointmentLookupServiceTest` |
| **US-P2-13** Auditoria da agenda | F01, F04, F06 | `SchedulingPlatformIT.auditLogAcceptsAppointments…`, `AppointmentStatusIT.everyTransitionLeavesATrail…`, `phase2/SchedulingForgedRoleIT` (`theScheduleAndTheHolidaysAreNotAudited…`, `withoutAUserTheWriteIsRefused…` com `28000`) |
| **US-P2-14** Isolamento e papel provados | F06 | `phase2/SchedulingRoleMatrixWebTest` (24 rotas × papéis), `SchedulingRoleMatrixIT` (zero instruções SQL nas negações), `SchedulingForgedRoleIT` (RLS com SQL direto, papel forjado/ausente/inválido, isolamento entre clínicas), `Phase2SchemaValidateIT` (`ddl-auto=validate`, RLS forçada) |

## Metas do PRD global (seção 1)

| Meta | Resultado | Onde |
|---|---|---|
| 1. Matriz papel × endpoint 100% das rotas novas/alteradas | 24 rotas × {sem token, R, D} | `SchedulingRoleMatrixWebTest`, `SchedulingRoleMatrixIT` |
| 2. Concorrência: um 201 e um 409, 20 rodadas, sem 500; clínicas diferentes passam | Verde (3 execuções seguidas = 60 rodadas na F03). Achado: o `EXCLUDE` aborta a perdedora com deadlock `40P01`, traduzido para 409 | `AppointmentsIT.twoSimultaneousBookings…` |
| 3. Filtro desligado + papel forjado: RLS sozinha segura | Verde | `SchedulingForgedRoleIT` |
| 4. Agenda da semana p95 ≤ 100 ms (20 mil, 4 concorrentes); criação p95 ≤ 100 ms | **22 ms** e **39 ms**; plano sem `Seq Scan` (`ix_appt_clinic_start`, `ix_appt_patient`) | `SchedulingPerformanceIT` |
| 5. `ddl-auto=validate` contra V001–V021; `mvn verify` verde | Verde | `Phase2SchemaValidateIT`, suíte completa |
| 6. Nenhum `notes`, nome ou telefone em log, `toString` ou erro | Verde; o detector é validado por controles de mutação | `SchedulingLogHygieneIT`, `PatientBrief`/`AffectedAppointments.Item` com `toString` seguro |
| 7. Cobertura ≥ 80% nos services de `scheduling` e nos alterados de `patient`/`record` | **99,7%** (`scheduling.service`, 357/358 linhas), **99,4%** (`patient.service`), **97,8%** (`record.service`), 100% (`clinic.service`); `scheduling.web` 94,9% | JaCoCo por linha de comando (seção abaixo) |

## Validação da higiene de log por mutação (2026-10-24)

1. **Detector:** `SchedulingLogHygieneIT.control…` injeta observação, telefone, nome e CPF numa linha de log e uma exceção anexada, e exige que o detector os acuse.
2. **Mutação do código de produção:** foi injetado em `AppointmentService.create` um `log.info("… observacao={} paciente={}", notes, nome)`. O teste `noSecretReachesTheLogsOrTheErrorResponsesInAnyAgendaFlow` **falhou** acusando a observação e o nome do paciente; com a mutação revertida (0 ocorrências no código) o teste voltou a passar.
3. **Sentinelas:** observação, nome, telefone, texto de nota clínica e **CPF** (o paciente do cenário é criado com CPF).

## Testes sensíveis à data

Os ITs escolhem semanas **sem feriado nacional de data fixa** (`AgendaDates.monday()`, `mondays(n)` e `pastMonday()`), porque o dentista também é bloqueado por feriado sem o encaixe. Antes, `previous(MONDAY)` dava falha quando a segunda anterior era feriado (por exemplo, 3–9/11/2026, com 02/11 Finados).

## Cobertura (2026-10-24)

Medida com o JaCoCo pela linha de comando, sem alterar o `pom.xml` (não há gate de cobertura no projeto):

```
./mvnw org.jacoco:jacoco-maven-plugin:0.8.13:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.13:report   "-Dit.test=!LoginPerformanceIT,!SchedulingPerformanceIT,!Phase1SearchPerformanceIT,!Phase1UploadPerformanceIT"
```

Os testes de desempenho ficam de fora porque o agente do JaCoCo os deixa mais lentos. Único serviço abaixo de 80% por classe: o tipo interno `PatientAnonymizationService.DatabasePhase` (1/2 linhas), que não pertence ao escopo desta fase.

## Achados da implementação que valem registro

1. **`hibernate.jdbc.time_zone: UTC` deslocava `LocalTime`** pelo fuso da JVM (08:00 gravado como 11:00; `24:00:00` lido como 21:00). Corrigido com `hibernate.type.java_time_use_direct_jdbc: true` (`application.yml`). Provado no IT com leitura crua do banco.
2. **Deadlock `40P01` no `EXCLUDE`** quando duas inserções disputam o mesmo horário. Traduzido para 409 "Horário já ocupado." no serviço e como rede de segurança global no handler.
3. **Feriados do seed (2026–2030)** caem em dias comuns de teste (por exemplo 02/11): os ITs escolhem semanas sem feriado (`AgendaDates`).
4. **Teste flaky por UUID aleatório** em `toString` (corrigido: sentinela que um UUID não contém).

## Limitações aceitas (registradas nos contratos)

- Dois `start` simultâneos de agendamentos diferentes podem passar (sem `UNIQUE` parcial no banco; dentista solo) — `F04/contract.md`.
- Janela de corrida entre criar um agendamento e inativar o paciente (o guarda do banco não conflita com o `UPDATE` do paciente) — F05, achado Baixo do QA.
- Aviso de impacto carrega todos os futuros em aberto da clínica na memória (volume de dentista solo) — F02, achado Baixo do QA.
