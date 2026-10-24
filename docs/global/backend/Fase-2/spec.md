# Spec Técnica Global (Backend) — Fase 2: Agenda

**Base:** `docs/global/backend/Fase-2/prd.md` (aprovado em 2026-10-24) e a base entregue nas Fases 0 e 1 (`docs/global/backend/Fase-{0,1}/{prd,spec}.md`; código em `back-java/`).
**Contrato do banco:** `docs/global/banco/Fase-2/spec.md` (seções 2, 3, 4.5, 4.6 e 6) e as migrations `database/migrations/V014…V021`, já aplicadas no Supabase real.
**Escopo:** módulo novo `scheduling` e as mudanças em `patient`, `record`, `clinic`/`auth` e `shared`.
**Regra:** sem código de aplicação. Há apenas contratos, assinaturas, JSON e configuração ilustrativos; as classes são do `dev-engineer-java`.

> **Fronteira com o banco:** o backend não cria nem altera objetos de banco (`ddl-auto=validate`). Os nomes abaixo são os **definitivos** das migrations V014–V021.

---

## 1. Visão técnica

- **Feature:** Agenda (agendamentos com conflito resolvido pelo banco, máquina de status, horário comercial, feriados, fuso da clínica) e a integração com paciente, nota clínica e auditoria.
- **Stack:** Java 21 · Spring Boot 4.1.1 (Spring Framework 7, Hibernate 7) · Spring Data JPA · Spring Security stateless (JWT da Fase 0) · Testcontainers. **Nenhuma dependência nova no `pom.xml`**: datas e fuso usam `java.time` e o `Clock` injetável já existente.
- **Abordagem:** a mesma Clean Architecture por módulo (`web → service → repository`, `domain` sem Spring Web). O controller só traduz HTTP; regra, papel e transação ficam no `service`. O isolamento segue em duas camadas (filtro Hibernate + RLS) e o papel é reforçado também pelo banco. A **autoridade do conflito de horário é a constraint `ex_appt_no_overlap`**; o backend não usa lock, `SELECT … FOR UPDATE` nem pré-checagem por `&&` para isso.

### 1.1 Layout de diretórios (acréscimos e alterações)

```
back-java/src/main/java/crm_data/
├─ shared/
│  ├─ error/       DatabaseErrors (+ exclusão 23P01, CR004, CR005), ConflictMessages (+ mensagens), GlobalExceptionHandler (ALTERADO: tabela 5.1)
│  ├─ config/      SecurityConfig (ALTERADO: rotas da seção 3.2)
│  └─ web/         ClinicalCacheHeaders (ALTERADO: no-store também nas rotas da agenda)
├─ clinic/
│  ├─ service/     ClinicService (ALTERADO: timezone), ClinicZones (NOVO: interface pública, fuso da clínica do token)
│  └─ web/         ClinicRequests, ClinicResponse (ALTERADOS: timezone)
├─ auth/domain/    Clinic (ALTERADO: mapeia timezone)
├─ patient/
│  ├─ service/     PatientLookup (ALTERADO), PatientEvents (+ Deactivating), PatientService (ALTERADO), PatientAnonymizationService (ALTERADO: CR004)
│  └─ web/         sem rota nova
├─ record/
│  ├─ domain/      ClinicalNote (ALTERADO: appointmentId)
│  ├─ repository/  ClinicalNoteRepository (+ existsByAppointmentIdAndIdNot)
│  ├─ service/     ClinicalNoteService (ALTERADO: G3), AuditQueryService (TABLES + "appointments")
│  └─ web/         RecordRequests, NoteResponse (ALTERADOS: appointmentId)
└─ scheduling/                                    (NOVO)
   ├─ web/         AppointmentController, PatientAppointmentController, BusinessHoursController, HolidayController,
   │               ClinicHolidayController, AppointmentRequests, AppointmentResponse, BusinessHoursRequests/Response,
   │               HolidayRequests/Response
   ├─ service/     AppointmentService, AppointmentTransitions, AppointmentSlotRules, SchedulingRules (constantes),
   │               AppointmentLookup (interface pública p/ record), PatientDeactivationListener, BusinessHoursService,
   │               HolidayService, SchedulingEvents, SchedulingAuditLogger
   ├─ domain/      Appointment, AppointmentStatus, BusinessHour, ClinicHoliday, NationalHoliday
   └─ repository/  AppointmentRepository, BusinessHourRepository, ClinicHolidayRepository, NationalHolidayRepository
```

**Direção de dependência (sem ciclo):** `scheduling → patient` (`PatientLookup`), `scheduling → clinic` (`ClinicZones`), `record → scheduling` (`AppointmentLookup`), `record → patient` (`PatientLookup`). `patient` **não** conhece `scheduling`: ele publica `PatientEvents.Deactivating` e `scheduling` escuta. Nenhum módulo usa o repositório de outro. O teste de arquitetura (`Phase*ArchitectureTest`) passa a cobrir `scheduling` e proíbe `patient → scheduling`, `clinic → scheduling` e `shared → scheduling`.

---

## 2. Modelo de dados e estado

### 2.1 Mudanças de banco

**Nenhuma.** O backend consome V001–V021. Se o mapeamento exigir algo que o banco não tem (ver seção 10, itens 3 e 5), a pendência vai para a função `banco`.

### 2.2 Mapeamento das entidades (`ddl-auto=validate`)

As entidades de negócio estendem `TenantAwareEntity` (filtro Hibernate + `clinic_id` do `TenantContext`), com `@Getter`, `@NoArgsConstructor(access = PROTECTED)` e **setters apenas nos campos editáveis**. Proibidos `@Data`, `@ToString` e `@EqualsAndHashCode`. `@Version Long version`. `created_at` e `updated_at` com `insertable = false, updatable = false`, **sem** `@Generated` (mesma razão da Fase 1: evitar `RETURNING`/`SELECT` extra sob RLS). O `id` é atribuído pela aplicação (UUID) e o `save` de entidade nova cai em `persist` (versão nula), sem `SELECT` prévio.

| Entidade (`core.<tabela>`) | Atributos e restrições de mapeamento |
|---|---|
| `Appointment` (`appointments`) | `id UUID`; `patientId UUID` (`updatable = false`, **sem** `@ManyToOne`); `startsAt`, `endsAt` (`Instant`, editáveis); `status` (`@Enumerated(STRING)`, `AppointmentStatus`: `SCHEDULED`, `CONFIRMED`, `IN_PROGRESS`, `COMPLETED`, `NO_SHOW`, `CANCELED`); `notes` (`String`, **sem** conversor de cifra: texto claro, trade-off P2); `createdBy` (`updatable = false`). **`procedure_type_id` não é mapeada** (não entra na entrada nem na saída; a coluna é anulável e `validate` não exige mapear todas as colunas) |
| `BusinessHour` (`business_hours`) | `weekday` (`Short`, `updatable = false`; getter devolve `DayOfWeek`), `open` (coluna `is_open`), `opensAt`, `closesAt`, `lunchStartsAt`, `lunchEndsAt` (`LocalTime`, mapeando `time(0)`), `createdBy` (`updatable = false`) |
| `ClinicHoliday` (`clinic_holidays`) | `date` (coluna `holiday_date`, `LocalDate`, editável), `name` (editável), `createdBy` (`updatable = false`) |
| `NationalHoliday` (`holidays`) | `@Immutable`; `date` (`@Id`, coluna `holiday_date`), `name`. **Não** estende `TenantAwareEntity` (dado global, sem `clinic_id`); repositório só com leitura |
| `Clinic` (`clinics`, alterada) | `timezone String` (`nullable = false`). **Valor inicial no construtor/campo `America/Sao_Paulo`**: o Hibernate envia todas as colunas mapeadas no `INSERT` e enviar `NULL` violaria o `NOT NULL`; assim `/auth/register` segue funcionando. Setter usado só por `ClinicService` |
| `ClinicalNote` (alterada) | `appointmentId UUID` (coluna `appointment_id`, **editável**, sem `@ManyToOne`) |

Pontos de validação do mapeamento:
- `timestamptz` → `Instant`; `time(0)` → `LocalTime`; `date` → `LocalDate`; `smallint` → `Short`; `bigint version` → `Long`.
- `clinics.timezone` é reenviado em todo `UPDATE` de `clinics`; o trigger do banco deixa passar quando o valor não muda (curto-circuito, teste 73 do banco), então a edição da clínica por `DENTIST_ADMIN` não conflita.
- `business_hours` pode conter `'24:00:00'` gravado fora da API (o `time` aceita; `LocalTime` não). A API só aceita até 23:59; a leitura defensiva está na seção 5.2.

### 2.3 Estado em memória

- **Stateless**, como nas fases anteriores. Nenhum cache de horário comercial, feriado ou fuso: o fuso e o expediente podem ser alterados a qualquer momento e a leitura custa uma consulta indexada de ≤ 7 linhas.
- Constantes em `SchedulingRules`: tolerância do passado (5 min), janela máxima da agenda (42 dias), janela máxima de feriados (366 dias), recuo de busca (12 h, ligado ao teto de duração S10 do banco), duração mínima/máxima/passo (5 min, 12 h, 5 min), tamanho máximo de `notes` (500). Não há propriedade nova de configuração.
- O "agora" vem sempre do `Clock` injetável (`ClockConfig`), nunca de `Instant.now()`.

---

## 3. Arquitetura de componentes (backend)

### 3.1 Componentes

| Componente | Responsabilidade |
|---|---|
| `AppointmentService` | Criar, consultar, remarcar e transicionar agendamentos; traduz `23P01`/`CR003`/`CR005` com `flush` explícito; chama `PatientLookup`, `ClinicZones`, `BusinessHoursService` e `HolidayService` |
| `AppointmentTransitions` | Tabela pura da máquina de estados (ação → origens permitidas, destino, papéis); sem Spring, 100% testável em unidade |
| `AppointmentSlotRules` | Regra pura de expediente, almoço, dia fechado, feriado e passado: recebe `Instant`s, `ZoneId`, os 7 `BusinessHour` e o conjunto de datas de feriado; devolve a `reason` ou vazio |
| `BusinessHoursService` | Leitura dos 7 dias e `PUT` em lote (apenas `DENTIST_ADMIN`), com `dryRun` e o aviso de agendamentos afetados |
| `AffectedAppointmentsFinder` | Só leitura: agendamentos futuros em aberto que caem fora da configuração resultante (expediente) ou na data de um feriado novo da clínica |
| `HolidayService` | Lista nacionais ∪ da clínica; CRUD dos feriados da clínica |
| `AppointmentLookup` (interface pública) | Para `record`: `requireOfPatient(appointmentId, patientId)` (404 se inexistente na clínica, 409 se de outro paciente) e `lockOfPatient(...)`, que faz o mesmo sob trava de linha (seção 4.9) |
| `PatientDeactivationListener` | `@EventListener` **síncrono** de `PatientEvents.Deactivating`; roda na transação do publicador (seção 4.8) |
| `ClinicZones` (interface pública em `clinic.service`) | `ZoneId zone()` da clínica do token (lê `clinics.timezone`; fuso inválido gravado por fora → 500 `internal`) |
| `PatientLookup` (alterado) | `requireSchedulable(id)` (404 se não existe na clínica, 409 se anonimizado, 409 se inativo) e `briefs(Collection<UUID>)` → `Map<UUID, PatientBrief(id, name, phone)>` em **uma** consulta |
| `SchedulingAuditLogger` | `@TransactionalEventListener(AFTER_COMMIT)` de `SchedulingEvents`; só identificadores, status e contagens (padrão do `ClinicalAuditLogger`) |
| `GlobalExceptionHandler` / `DatabaseErrors` (alterados) | Tabela da seção 5.1; nunca logam a mensagem nem o `DETAIL` do driver |

### 3.2 Endpoints

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`. Nenhum DTO aceita `id`, `clinicId`, `createdBy`, `role`, `status` nem `procedureTypeId` do corpo (campos extras do JSON são ignorados, como na Fase 1). Todo `{id}` de rota é resolvido dentro do tenant (outra clínica → 404). Respostas da agenda levam `Cache-Control: no-store`. Instantes em ISO-8601 com deslocamento; datas `from`/`to` e horários `HH:mm` são locais da clínica.

| Método | Rota | Papéis | Request | Response | Erros |
|---|---|---|---|---|---|
| GET | `/clinic` | D, R | — | 200 `ClinicResponse` (+ `timezone`) | 401 |
| PUT | `/clinic` | D | `UpdateClinicRequest` (+ `timezone?`, `version`) | 200 `ClinicResponse` | 400, 401, 403, 409 |
| GET | `/business-hours` | D, R | — | 200 lista de 7 `BusinessDayResponse` | 401 |
| PUT | `/business-hours?dryRun` | D | `{days:[BusinessDayRequest]}` (1 a 7 dias distintos); `dryRun=true` valida e avisa sem gravar | 200 `{saved, days[7], affectedAppointments[≤100], affectedCount, truncated}` | 400, 401, 403, 409 |
| GET | `/holidays?from&to` | D, R | janela ≤ 366 dias | 200 lista `HolidayResponse` | 400, 401 |
| POST | `/clinic-holidays` | D | `{date, name}` | 201 `{holiday, affectedAppointments[≤100], affectedCount, truncated}` | 400, 401, 403, 409 |
| PUT | `/clinic-holidays/{id}` | D | `{date, name, version}` | 200 `{holiday, affectedAppointments[≤100], affectedCount, truncated}` | 400, 401, 403, 404, 409 |
| DELETE | `/clinic-holidays/{id}` | D | — | 204 | 401, 403, 404 |
| POST | `/appointments` | D, R | `CreateAppointmentRequest` | 201 `AppointmentResponse` | 400, 401, 403, 404, 409, 422 |
| GET | `/appointments?from&to&status` | D, R | `from`, `to` datas locais (`to` inclusivo), janela ≤ 42 dias, `status` repetível | 200 lista `AppointmentResponse` (sem paginação) | 400, 401 |
| GET | `/appointments/{id}` | D, R | — | 200 `AppointmentResponse` | 401, 404 |
| PUT | `/appointments/{id}` | D, R | `RescheduleRequest` (inclui `version`) | 200 `AppointmentResponse` | 400, 401, 403, 404, 409, 422 |
| POST | `/appointments/{id}/confirm`, `/cancel`, `/no-show` | D, R | `{version}` | 200 `AppointmentResponse` | 400, 401, 404, 409, 422 |
| POST | `/appointments/{id}/start`, `/finish`, `/reactivate` | D | `{version}` | 200 `AppointmentResponse` | 400, 401, 403, 404, 409, 422 |
| GET | `/patients/{id}/appointments?page&size` | D, R | `size` ≤ 50 | 200 `PageResponse<AppointmentResponse>` | 401, 404 |

Rotas existentes alteradas: `POST/PUT /patients/{id}/notes[/{noteId}]` (+ `appointmentId`), `GET /patients/{id}/notes` (resposta com `appointmentId`), `PATCH /patients/{id}/active`, `POST /patients/{id}/anonymize` e `GET /audit-log` (`tableName=appointments`).

Contratos de corpo (ilustrativos):

```json
// POST /appointments
{ "patientId": "…", "startsAt": "2026-11-03T13:00:00Z", "endsAt": "2026-11-03T13:45:00Z",
  "notes": "Retorno de canal", "allowOutsideHours": false }

// PUT /appointments/{id}   (startsAt e endsAt obrigatórios; notes substitui o valor, null limpa)
{ "startsAt": "2026-11-03T14:00:00Z", "endsAt": "2026-11-03T14:45:00Z", "notes": null,
  "allowOutsideHours": false, "version": 2 }

// Resposta
{ "id": "…", "patient": { "id": "…", "name": "Maria Souza", "phone": "11987654321" },
  "startsAt": "2026-11-03T13:00:00Z", "endsAt": "2026-11-03T13:45:00Z", "status": "SCHEDULED",
  "notes": "Retorno de canal", "createdBy": "…", "createdAt": "…", "updatedAt": "…", "version": 0 }

// PUT /business-hours   (version ausente só ao criar o dia; presente ao atualizar)
{ "days": [ { "weekday": 1, "isOpen": true, "opensAt": "08:00", "closesAt": "18:00",
              "lunchStartsAt": "12:00", "lunchEndsAt": "13:00", "version": 3 },
            { "weekday": 7, "isOpen": false } ] }

// GET /business-hours (dia sem linha)
{ "weekday": 6, "configured": false, "isOpen": false }

// GET /holidays
{ "date": "2026-11-20", "name": "Dia da Consciência Negra", "source": "NATIONAL" }
{ "date": "2026-12-24", "name": "Recesso da clínica", "source": "CLINIC", "id": "…", "version": 0 }

// 422 de agenda (ProblemDetail da Fase 0 + propriedade reason)
{ "type": "https://crm.local/problems/business-rule", "status": 422, "reason": "LUNCH", "detail": "…" }
```

O `reason` do 422 (`CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`, `HOLIDAY`, `PAST`, `NOT_TODAY`, `NOT_STARTED`) viaja como propriedade extra do `ProblemDetail`; para isso `BusinessException` ganha um construtor opcional com `reason` (mudança compatível, o handler só o copia para a resposta).

---

## 4. Lógica central e algoritmos

### 4.1 Sessão do banco

Sem mudança: o ponto único da Fase 1 (`TenantHibernateJpaDialect`) fixa `app.clinic_id`, `app.role` e `app.user_id` em toda transação. Toda gravação de `appointments` precisa de `app.user_id` (a auditoria falha com `28000` sem ele) e usa `created_by = app.user_id`, valor lido do `TenantContext` pelo service ao criar.

### 4.2 Validação de entrada (antes do banco)

Aplicada em criação e remarcação, devolvendo **todos** os erros de uma vez (400 `validation` com `errors[]`):
- `startsAt` e `endsAt` obrigatórios; `endsAt > startsAt`;
- `startsAt` sem segundos nem fração (`getEpochSecond() % 60 == 0` e nano = 0);
- duração `Duration.between` múltipla de 5 min, entre 5 min e 12 h;
- `notes` ≤ 500 caracteres e, se presente, não só espaços (vazio ou branco vira `null`);
- `patientId` obrigatório na criação e **ignorado** na remarcação.

### 4.3 Regra de horário (`AppointmentSlotRules`)

Entradas: `startsAt`, `endsAt`, `now`, `ZoneId`, `Map<DayOfWeek, DaySchedule>` (valor imutável derivado de `BusinessHour`, que também permite avaliar uma configuração proposta), `Set<LocalDate>` de feriados nacionais ∪ da clínica. Saída: `Optional<Reason>`.

1. Passado (G4): `startsAt < now − 5 min` → `PAST`.
2. `ls = startsAt` no fuso, `le = endsAt` no fuso (`ZonedDateTime`, tratando DST pelo `java.time`).
3. Feriado: `ls.toLocalDate()` ou, se `le.toLocalDate()` for outra data e `le` > 00:00, a data de término está no conjunto → `HOLIDAY`.
4. Se `ls.toLocalDate() ≠ le.toLocalDate()` → `OUTSIDE_HOURS` (o expediente nunca atravessa a meia-noite; término exatamente à meia-noite também cai aqui).
5. Linha do dia da semana ausente ou `isOpen = false` → `CLOSED_DAY`.
6. `ls.toLocalTime() < opensAt` ou `le.toLocalTime() > closesAt` → `OUTSIDE_HOURS`.
7. Almoço configurado e o intervalo `[ls, le)` intersecta `[lunchStartsAt, lunchEndsAt)` → `LUNCH`.

Ordem de prioridade da `reason` quando mais de uma vale: `PAST`, `HOLIDAY`, `CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`. **Quem é isento:** `PAST` só para o `DENTIST_ADMIN` (sempre); os demais (`HOLIDAY`…`LUNCH`) são ignorados quando o `DENTIST_ADMIN` envia `allowOutsideHours=true`. A recepção que envia `allowOutsideHours=true` recebe 403 antes de qualquer outra regra de negócio.

### 4.4 Criar agendamento (`POST /appointments`, D e R)

1. `@PreAuthorize` (D ou R). Role vem do `TenantContext`.
2. Validar entrada (4.2) → 400.
3. Se R e `allowOutsideHours=true` → 403 `forbidden`.
4. `PatientLookup.requireSchedulable(patientId)` → 404/409.
5. Ler fuso (`ClinicZones`), `business_hours` (7 linhas) e feriados do intervalo `[data inicial − 1 dia, data final + 1 dia]` (uma consulta nacional + uma da clínica) **só quando** a regra for aplicável (R, ou D sem `allowOutsideHours`); caso contrário pular as consultas.
6. `AppointmentSlotRules`: para R, `PAST` e as demais; para D, `PAST` nunca bloqueia e as demais só sem `allowOutsideHours` → 422 com `reason`.
7. Criar `Appointment(status = SCHEDULED, createdBy = userId)` com `UUID` novo, `save` (persist) e **`flush`** dentro de `try/catch`: `23P01` → 409 "Horário já ocupado."; `CR005` → 409; `CR003` → 409.
8. Publicar `SchedulingEvents.Created`; se D com `allowOutsideHours=true` **e** a regra o teria bloqueado (cálculo da etapa 6 sem a isenção), publicar também `OutsideHoursBooked` (rastreio do encaixe: log após commit, só `appointmentId`/`userId`).
9. Montar `AppointmentResponse` com `PatientLookup.briefs` (uma consulta) e devolver 201 com `Location`.

### 4.5 Consultar agenda (`GET /appointments`)

1. Validar `from`, `to` (formato `AAAA-MM-DD`), `to ≥ from`, janela ≤ 42 dias contados incluindo as duas pontas; `status` repetível e restrito ao enum → 400.
2. `zone = ClinicZones.zone()`; `ini = from.atStartOfDay(zone)`, `fim = to.plusDays(1).atStartOfDay(zone)` como `Instant`.
3. Repositório (JPQL, o filtro do tenant acrescenta `clinic_id`): `startsAt >= :ini − 12h AND startsAt < :fim AND endsAt > :ini ORDER BY startsAt, id`. Duas consultas (com e sem filtro `status IN :statuses`) para não depender de parâmetro de lista vazia. O recuo de 12 h é calculado em Java e **vale enquanto o teto de duração for 12 h** (S10).
4. Resumos dos pacientes em lote (`briefs`); montar a resposta sem N+1.
5. `Cache-Control: no-store`.

Detalhe (`GET /appointments/{id}`): `findById` dentro do tenant → 404.

### 4.6 Remarcar e editar observação (`PUT /appointments/{id}`)

1. Validar entrada (4.2) e checar o papel de `allowOutsideHours` como em 4.4.
2. Carregar o agendamento (404). Se `version` do corpo ≠ `version` da entidade → 409. Se o status não for `SCHEDULED`/`CONFIRMED` → 409 com o status atual.
3. `horarioMudou = startsAt ≠ atual || endsAt ≠ atual`. Se mudou: aplicar `PAST` (R) e as regras de horário (4.3) com o mesmo critério de isenção da criação; se `status = CONFIRMED`, voltar a `SCHEDULED`.
4. `notes` substitui o valor. `patientId` do corpo é ignorado.
5. `flush` com tradução de `23P01` (409), `OptimisticLockException`/0 linhas (409, seção 5.2).
6. Eventos: `Rescheduled` (só se o horário mudou) e, quando for o caso, `OutsideHoursBooked`.

### 4.7 Transições (`confirm`, `cancel`, `no-show`, `start`, `finish`, `reactivate`)

`AppointmentTransitions` define, por ação: origens permitidas, destino e papéis (seção 5 do PRD). `start`, `finish` e `reactivate` têm `@PreAuthorize("hasRole('DENTIST_ADMIN')")` em métodos próprios do service (o 403 sai **antes** de abrir transação no banco, e o `SecurityConfig` reforça por rota).

Algoritmo comum:
1. Papel (403).
2. Carregar (404); `version` do corpo ≠ da entidade → 409.
3. Status atual fora das origens da ação → 409 com o status atual.
4. Regra extra:
   - `no-show`: `now ≥ startsAt`, senão 422 `NOT_STARTED`;
   - `start`: data local de `startsAt` = data local de hoje no fuso da clínica, senão 422 `NOT_TODAY`; e **não** existir outro agendamento `IN_PROGRESS` na clínica (`existsByStatusAndIdNot`), senão 409 "Já existe um atendimento em andamento.";
   - `reactivate`: nenhuma regra de horário nem de passado.
5. Aplicar o destino e `flush`: `reactivate` traduz `23P01` (409), `CR005` (409) e `CR003` (409); demais ações traduzem o 0 linhas da RLS como 409.
6. Evento `StatusChanged(appointmentId, from, to)` e resposta 200 com o recurso.

A recepção só envia ao banco `SCHEDULED`, `CONFIRMED`, `CANCELED` e `NO_SHOW`. Um `42501` num `UPDATE` de status é defeito do backend e vira 500 `internal` com log de erro.

### 4.8 Inativar paciente cancela os futuros (G2)

1. `PatientService.setActive(id, false)` (D, rota já restrita) publica **antes de gravar** `PatientEvents.Deactivating(clinicId, patientId)` com `ApplicationEventPublisher`, **só quando o paciente estava ativo** (inativar de novo é idempotente e não publica).
2. `PatientDeactivationListener` (`@EventListener`, **não** `@TransactionalEventListener`: precisa rodar dentro da transação do publicador e propagar a exceção):
   - Se existe agendamento `IN_PROGRESS` do paciente → `BusinessException(CONFLICT, "Finalize o atendimento antes de inativar o paciente.")`. A exceção reverte a inativação.
   - Carrega os agendamentos do paciente com `status ∈ {SCHEDULED, CONFIRMED}` e `endsAt > now`, marca `CANCELED` entidade a entidade (cada `UPDATE` gera sua linha de auditoria com o autor do token) e faz `flush`.
   - Publica `SchedulingEvents.AutoCanceled(patientId, quantidade)` (log após commit, só contagem).
3. Qualquer falha na etapa 2 propaga: a inativação não persiste. Agendamentos passados e `IN_PROGRESS`/`COMPLETED` nunca são tocados.
4. Reativar o paciente não restaura nada.

### 4.9 Nota clínica × atendimento (G3)

`ClinicalNoteService` (só D), em `POST` e em `PUT` quando `appointmentId` vier preenchido:
1. `PatientLookup.requireWritable(patientId)` (como hoje).
2. `AppointmentLookup.lockOfPatient(appointmentId, patientId)`: carrega o agendamento com `PESSIMISTIC_WRITE` (trava a linha até o commit da nota); inexistente na clínica → 404; `patientId` diferente → 409 "Atendimento de outro paciente.".
3. `ClinicalNoteRepository.existsByAppointmentIdAndIdNot(appointmentId, noteId-ou-UUID-zero)`: se já existe outra nota → 409 "Já existe nota para este atendimento.". Na edição, a própria nota é excluída da checagem.
4. Gravar a nota. A trava da etapa 2 serializa duas gravações simultâneas para o mesmo atendimento: a segunda vê a primeira depois do commit e recebe 409.

No `PUT`, `appointmentId` **substitui** o vínculo: preenchido vincula ou troca (mesmas regras), `null` ou ausente **desvincula** (grava `NULL`, sem trava nem checagem de unicidade). O cliente que quer manter o vínculo reenvia o valor atual. O conteúdo continua cifrado e fora de log.

### 4.10 Anonimização de paciente

`PatientAnonymizationService` passa a traduzir `CR004` (agendamento futuro em aberto, incluindo `IN_PROGRESS`) em 409 "Cancele os agendamentos futuros antes de anonimizar.". Depois da função do banco os `notes` dos agendamentos ficam nulos e `startsAt`, `endsAt`, `status` permanecem (o backend não faz nada além de conferir isso em IT). Como o paciente precisa estar inativo antes de anonimizar, os futuros já foram cancelados em 4.8; o `CR004` só aparece se alguém criou agendamento depois da inativação por via que o `CR005` deveria barrar (defesa em profundidade) ou por `IN_PROGRESS` concorrente.

### 4.11 Horário comercial (`PUT /business-hours`)

1. Receber de 1 a 7 dias, `weekday` ∈ 1..7 sem repetição.
2. Validar cada dia, acumulando erros: `isOpen=true` exige `opensAt < closesAt` (`HH:mm`, máx. 23:59); almoço opcional com as duas pontas, `opensAt < lunchStartsAt < lunchEndsAt < closesAt`; `isOpen=false` não aceita nenhum horário.
3. Carregar as linhas existentes da clínica (≤ 7). Para cada dia do corpo:
   - sem linha: criar (`version` do corpo deve estar ausente); com `UUID` novo e `persist`;
   - com linha: `version` do corpo obrigatório e igual ao da entidade, senão 409 (ausente também é 409: o cliente acredita que o dia não está configurado).
4. Tudo na mesma transação; `flush`. `23505` em `ux_bh_clinic_weekday` (criação concorrente) → 409 "Dia já configurado.".
5. Responder 200 com os 7 dias (relendo o estado final). Fechar um dia zera todos os horários dele.

**Aviso de agendamentos afetados (informativo, não bloqueia):** a configuração **resultante** (dias do corpo + dias atuais; dia sem linha = fechado) é montada em memória como `Map<DayOfWeek, DaySchedule>`. Com `?dryRun=true` o `PUT` valida (inclusive `version`) e calcula, sem gravar nem publicar evento (`saved:false`). `AffectedAppointmentsFinder` lê os agendamentos `SCHEDULED`/`CONFIRMED` com `endsAt > agora` e aplica `AppointmentSlotRules` sem a regra `PAST` e sem feriados; os que retornam `CLOSED_DAY`, `OUTSIDE_HOURS` ou `LUNCH` entram em `affectedAppointments` (paciente resumido em lote, `reason`), limitados a 100 itens com `affectedCount` e `truncated`. Nada é cancelado ou remarcado.

### 4.12 Feriados

- `GET /holidays?from&to`: `from`/`to` datas, `to ≥ from`, janela ≤ 366 dias (inclusive) → 400. União de `NationalHolidayRepository.findByDateBetween` e `ClinicHolidayRepository.findByDateBetween`, ordenada por data (nacionais antes dos da clínica na mesma data).
- `POST /clinic-holidays`: `name` 2–80 após `strip`, `date ≥ 2000-01-01`; `23505` em `ux_clinic_holidays_date` → 409 "Já existe feriado nesta data.". A mesma data em outra clínica e a repetição de data nacional são aceitas.
- `PUT /clinic-holidays/{id}`: `version` obrigatória; trocar a data pode gerar `23505` → 409.
- `POST` e `PUT` devolvem também `affectedAppointments` (mesmo formato, `reason: HOLIDAY`): agendamentos `SCHEDULED`/`CONFIRMED` futuros cuja data local de início ou término é a data do feriado criado ou movido; `DELETE` não avisa.
- `DELETE /clinic-holidays/{id}`: remoção física, 204; inexistente ou de outra clínica → 404. Não há escrita em feriado nacional.

### 4.13 Fuso da clínica

- `GET /clinic` devolve `timezone`. `PUT /clinic` (só D) aceita `timezone` opcional; ausente mantém o atual.
- Validação no backend: `ZoneId.getAvailableZoneIds()` precisa conter o texto (rejeita deslocamentos como `+03:00` e nomes inventados) → 400 com o campo. O trigger do banco (`23514`) é a segunda barreira (seção 10, item 2).
- `ClinicZones.zone()` é a **única** fonte de fuso para dia, semana, expediente e "iniciar no dia". `PatientRules.CLINIC_ZONE` permanece como está (fora de escopo).

### 4.14 Isolamento de tenant e papel (duas camadas, três barreiras)

Mesmo desenho da Fase 1: filtro Hibernate nas consultas JPQL + RLS no banco + (novo) papel reforçado pelo banco. Todas as consultas novas são JPQL/Criteria ou derivadas; **nenhum SQL nativo** em tabelas com `clinic_id`. `EntityManager.find` por id não aplica o filtro: onde isso for usado, a RLS protege e o teste de isolamento com filtro desligado prova.

---

## 5. Erros e casos de borda

Formato `application/problem+json` da Fase 0. Nenhuma mensagem contém nome de paciente, `notes`, CPF, UUID de outra clínica nem o `DETAIL` do driver.

### 5.1 Tradução de erros do banco e de regras

| Origem | Quando | HTTP / tipo | Mensagem |
|---|---|---|---|
| `23P01` (`ex_appt_no_overlap`) | Conflito de horário em `INSERT` ou `UPDATE` | 409 `conflict` | "Horário já ocupado." (sem dados do outro agendamento) |
| `CR003` | Agendar/reativar para paciente anonimizado | 409 `conflict` | "Paciente anonimizado." |
| `CR004` | Anonimizar com agendamento futuro em aberto | 409 `conflict` | "Cancele os agendamentos futuros antes de anonimizar." |
| `CR005` | Agendar/reativar para paciente inativo | 409 `conflict` | "Paciente inativo." |
| `23505` + `ux_bh_clinic_weekday` | Dia criado em duplicidade | 409 `conflict` | "Dia já configurado." |
| `23505` + `ux_clinic_holidays_date` | Feriado repetido na clínica | 409 `conflict` | "Já existe feriado nesta data." |
| `42501` | Papel/política negou | 403 `forbidden` quando o backend não barrou antes; em `UPDATE` de status da recepção é defeito → 500 `internal` com log | texto padrão |
| `28000` | Gravação sem `app.user_id` | 500 `internal` | genérica |
| `23514` / `23503` | Validação do backend deixou passar | 500 `internal` | genérica; log com o **nome** da constraint |
| 0 linhas por RLS ou `OptimisticLockException` | Linha mudou de estado/versão entre leitura e gravação | 409 `conflict` | "O agendamento foi alterado. Recarregue." |
| Regra de agenda | `reason` ∈ {`CLOSED_DAY`, `OUTSIDE_HOURS`, `LUNCH`, `HOLIDAY`, `PAST`, `NOT_TODAY`, `NOT_STARTED`} | 422 `business-rule` | frase por `reason` |

A tradução do `23P01`/`CR*` fica no **service** (mensagem específica, `flush` explícito); o handler global cobre os mesmos códigos quando a exceção só estoura no commit (cadeia `JpaSystemException`/`TransactionSystemException` com `SQLException` na causa).

### 5.2 Cenários

| Cenário | Tratamento |
|---|---|
| Duas criações simultâneas no mesmo horário e clínica | Uma recebe 201 e a outra 409; sem 500; clínicas diferentes passam as duas |
| `23P01` estoura só no commit | Coberto pelo `flush` explícito no service e, em segunda linha, pelo handler global |
| Recepção chama `start`/`finish`/`reactivate`/`PUT /business-hours`/escrita de feriado/`PUT /clinic` | 403 pela regra de rota e por `@PreAuthorize`, sem tocar o banco |
| Recepção envia `allowOutsideHours=true` | 403, antes de qualquer outra regra de negócio |
| Recepção em agendamento `IN_PROGRESS`/`COMPLETED`/`CANCELED`/`NO_SHOW` | 409 com o status atual (checagem prévia); com papel forjado no banco, a RLS afeta 0 linhas |
| Transição fora da tabela | 409 com o status atual |
| `version` defasada | 409 |
| Agendamento ou paciente de outra clínica | 404 |
| Paciente anonimizado: agendamentos continuam listados | Listagem normal; resumo traz o nome anonimizado do banco |
| `COMPLETED`/`IN_PROGRESS` recebe `PUT` | 409 |
| Inativar com `IN_PROGRESS` | 409, nada alterado |
| Inativar com falha no cancelamento | Exceção propaga; paciente continua ativo |
| `start` fora do dia local, `no-show` antes do horário | 422 `NOT_TODAY` / `NOT_STARTED` |
| Segundo `IN_PROGRESS` na clínica | 409 |
| Dois `start` simultâneos de agendamentos diferentes | Sem constraint no banco: a corrida é **aceita** (dentista solo) e fica registrada como limitação (seção 10) |
| Fuso inválido gravado por fora | `ClinicZones` lança 500 `internal` com log; agenda indisponível até corrigir |
| `business_hours` com `24:00:00` gravado por fora | Leitura defensiva: tratado como `23:59` na regra e registrado em log de aviso (sem valores); a API nunca grava `24:00` |
| `from`/`to` ausentes, mal formatados, janela > 42 dias, `status` desconhecido | 400 `validation` com o campo |
| `notes` > 500 ou só espaços | 400 `validation` |
| Auditoria falha (`28000`) | Operação inteira reverte; 500 sem detalhes |
| Nota vinculada a atendimento de outro paciente | 409; de outra clínica ou inexistente, 404 |
| Duas notas simultâneas para o mesmo atendimento | A trava de linha serializa; a segunda recebe 409 |
| Reeditar a nota já vinculada | Passa (a própria nota é excluída da checagem) |
| Banco lento/indisponível | 503 `unavailable` (handler da Fase 0) |

---

## 6. Segurança e desempenho

### 6.1 Segurança

- **Papel só do JWT**; `clinic_id` nunca vem do corpo, da URL ou do header. `SecurityConfig` ganha as regras por rota da matriz do PRD (seção 3): `POST /appointments/*/{start,finish,reactivate}`, `PUT /business-hours`, `POST|PUT|DELETE /clinic-holidays/**` e `PUT /clinic` só `DENTIST_ADMIN`; as demais rotas novas exigem autenticação (D ou R). Mais específico antes do mais geral; validar o casamento dos *matchers* no primeiro build (seção 9).
- **`@PreAuthorize` nos services** como segunda barreira (padrão da Fase 1), inclusive em `BusinessHoursService.update`, `HolidayService` (escritas), `AppointmentService.start/finish/reactivate` e `ClinicService.update`.
- **DTOs** sem `clinicId`, `createdBy`, `role`, `status`, `id` nem `procedureTypeId` na entrada; `clinicId` e `procedureTypeId` nunca na saída.
- **Encaixe e passado** são prerrogativas do `DENTIST_ADMIN`, checadas no servidor; o banco não distingue (limite da arquitetura de papel único `crm_app`).
- **Log:** `SchedulingAuditLogger` só registra `appointmentId`, `patientId`, `userId`, status e contagens. `notes`, nome do paciente, telefone e CPF não aparecem em log, `toString`, mensagem de erro nem evento; o `DETAIL` do `23P01` nunca é logado nem devolvido.
- **Cache:** `Cache-Control: no-store` em todas as rotas da agenda, de horário comercial e de feriados (estender o componente `ClinicalCacheHeaders`).
- **Notes** é texto claro: a documentação da API (OpenAPI/README do módulo) avisa que não leva dado clínico; o banco zera `notes` na anonimização.
- **Defesa em profundidade provada** em Testcontainers (filtro Hibernate desligado, papel forjado), como na Fase 1.

### 6.2 Desempenho

- Agenda da semana pela API: p95 ≤ 100 ms com 20 mil agendamentos na clínica e 4 requisições concorrentes (banco: ≤ 10 ms; sem `Seq Scan` no plano da consulta de 4.5).
- Criação: p95 ≤ 100 ms (200 ms com `CI`, via `support/PerfLimits`, sobrescrevível por `-Dperf.limit-ms`).
- Sem N+1: resumo de pacientes em lote; `business_hours` (≤ 7 linhas) e feriados (≤ 2 consultas) lidos só quando a regra se aplica.
- Sem `SELECT … FOR UPDATE` no caminho de criação (a única trava é a da nota, seção 4.9).
- Cada escrita em `appointments` custa uma linha de auditoria; inativar paciente com N futuros gera N linhas, dentro da mesma transação.

---

## 7. Configuração

**Nenhuma propriedade nem variável de ambiente nova.** `application.yml`, `.env.example`, `Dockerfile` e `docker-compose.yml` não mudam. O `Clock` vem de `ClockConfig`. O `StartupConfigValidator` não muda.

Observação operacional: o Testcontainers dos ITs aplica `database/bootstrap` (agora com `btree_gist`) e `database/migrations` **V001–V021**; o auxiliar de IT (`Phase1ItSupport` ou sucessor `Phase2ItSupport`) deve apontar para o conjunto completo.

---

## 8. Estratégia de testes (para o `dev-engineer-java`)

| Camada | Ferramenta | Casos críticos |
|---|---|---|
| Unitário | JUnit 5 + Mockito | `AppointmentSlotRules` (tabela: dia fechado, antes/depois do expediente, fim exatamente no `closesAt`, almoço contendo, parcial e contíguo, atravessa meia-noite, feriado nacional e da clínica, DST do fuso, prioridade das `reason`, isenção do dentista); `AppointmentTransitions` (toda a tabela da seção 5 do PRD, inclusive as proibidas); validações de 4.2 (múltiplos de 5, segundos, 12 h, `notes`); `AppointmentService` (ordem 400→403→404/409→422→persist, tradução de `23P01`/`CR003`/`CR005` com `flush`, `CONFIRMED`→`SCHEDULED` na remarcação, `version` defasada); `PatientDeactivationListener` (cancela só os futuros em aberto, `IN_PROGRESS` → 409, contagem); `ClinicalNoteService` (G3); `BusinessHoursService`/`HolidayService` (validações, `version`, janela de 366 dias); `DatabaseErrors` (+ `23P01`, `CR004`, `CR005`) |
| Web | `@WebMvcTest` + `spring-security-test` | **Matriz papel × endpoint** completa (401/403/2xx) para as 16 rotas novas e as alteradas; DTOs ignoram `clinicId`/`createdBy`/`status`/`procedureTypeId`; `allowOutsideHours` da recepção → 403; `reason` no 422; `Cache-Control: no-store`; formato `ProblemDetail`; `from`/`to`/`status` inválidos → 400 |
| Integração | Testcontainers PostgreSQL com `database/bootstrap` e `database/migrations` (V001–V021) | (1) `ddl-auto=validate` passa com as entidades novas e alteradas (fecha o teste 84 do banco); (2) **concorrência**: duas threads em transações separadas criando o mesmo horário, 20 repetições, exatamente um 201 e um 409, nenhum 500; clínicas diferentes passam; (3) conflito: parcial, total, contido, consecutivo, outra clínica, `CANCELED`/`NO_SHOW` não ocupam, cancelar libera, reativar sobre horário ocupado → 409; (4) **isolamento com filtro desligado**: duas clínicas, 0 linhas da outra em agenda, `business_hours`, `clinic_holidays` e histórico, inclusive por SQL nativo; (5) **papel forjado** (`app.role = RECEPTIONIST`, filtro desligado): não grava `IN_PROGRESS`/`COMPLETED`, não altera linha em estado do dentista, não escreve `business_hours`/`clinic_holidays`; papel ausente ou inválido nega tudo; (6) inativar paciente cancela os futuros, preserva os passados, `IN_PROGRESS` → 409, auditoria por agendamento, reativar não restaura; (7) anonimização: `CR004` → 409, `notes` nulos, horário/status mantidos; (8) nota × atendimento: mesmo paciente, 1 por atendimento (inclusive concorrente), reedição, vínculo tardio; (9) `GET /audit-log?tableName=appointments`; (10) fuso: default, troca pelo D, fuso inválido → 400, recepção → 403; (11) feriados: união, janela, `23505`, `DELETE`; (12) higiene de log (nenhum `notes`, nome ou CPF em log, `toString` ou erro), validada por mutação; (13) desempenho: agenda da semana e criação com 20 mil agendamentos, p95 ≤ 100 ms com 4 concorrentes, plano sem `Seq Scan`; (14) a suíte da Fase 1 reexecutada (testes que inativam paciente passam a considerar o cancelamento) |
| Arquitetura | ArchUnit (`Phase*ArchitectureTest`) | `scheduling` sem acesso a repositório alheio; `patient`, `clinic` e `shared` não dependem de `scheduling`; `record → scheduling` só por `AppointmentLookup`; sem `@Data`/`@ToString`/`@EqualsAndHashCode` nas entidades novas |

**Critérios de saída da Fase 2:** `mvn verify` verde em máquina limpa (Docker ligado); matriz de papéis 100%; teste de concorrência verde; testes de isolamento e de papel forjado verdes; cobertura ≥ 80% nas classes de serviço de `scheduling` e nas classes de serviço alteradas de `patient` e `record`; nenhum `notes`, nome ou CPF em log; histórias US-P2-01 a US-P2-14 atendidas.

---

## 9. Pontos a validar antes de codar

1. **`flush` e o `23P01`:** confirmar no primeiro IT que a exceção sobe do `flush` (e não só do commit) e que `DatabaseErrors` a encontra na cadeia de causas do Hibernate 7.
2. **Nomes de fuso Java × `pg_timezone_names`:** alguns IDs de `ZoneId.getAvailableZoneIds()` (por exemplo `SystemV/…`, `EST5EDT`) podem não existir no catálogo do Postgres, e o contrário também ocorre; decidir se a lista aceita é restrita a `Region/City` e `UTC`.
3. **`isNew` do Spring Data:** conferir que `save` de entidade com `id` atribuído e `version` nula faz `persist` sem `SELECT` (risco 2 da Fase 1) também para `Appointment`, `BusinessHour` e `ClinicHoliday`.
4. **Trava da nota:** `PESSIMISTIC_WRITE` em `appointments` sob RLS (`FOR UPDATE` exige a política de `UPDATE` visível para a linha). Funciona para `DENTIST_ADMIN`; confirmar no IT e que não gera linha de auditoria.
5. **`@EventListener` síncrono:** confirmar que `PatientEvents.Deactivating` roda na transação de `setActive` e que a exceção do listener reverte a inativação (e não é engolida).
6. ~~**`time(0)` e `24:00:00`**~~ **Resolvido na F02:** o `hibernate.jdbc.time_zone: UTC` deslocava `LocalTime` pelo fuso da JVM (08:00 gravado como 11:00; `24:00:00` lido como 21:00). Corrigido com `hibernate.type.java_time_use_direct_jdbc: true`; `DaySchedule` limita a leitura a 23:59.
7. **Consulta da agenda:** o Hibernate 7 aplica o filtro de tenant ao JPQL sem reescrever os predicados de tempo; conferir o plano (sem `Seq Scan`) com 20 mil linhas.
8. **Spring Security 7:** casamento dos *matchers* de `/appointments/*/start` etc. e `/clinic-holidays/**`; ordem das regras por método.
9. **Propriedade extra no `ProblemDetail`:** `reason` deve ser serializado por `GlobalExceptionHandler` sem alterar o formato das demais respostas.
10. **Rede de segurança do banco real:** repetir o IT de papel forjado contra o Supabase real quando possível (V014–V021 já aplicadas).

---

## 10. Divergências e lacunas em relação aos documentos anteriores

| # | Item | Documento anterior | Esta spec |
|---|---|---|---|
| 1 | Único `IN_PROGRESS` por clínica | PRD: regra do backend | Não há índice único parcial no banco; dois `start` simultâneos podem passar. A corrida é aceita (dentista solo). Se houver mais de um profissional, pedir `UNIQUE … WHERE status='IN_PROGRESS'` à função `banco` |
| 2 | Desvincular nota do atendimento | PRD: só "vincular depois" | `PUT` substitui o vínculo: `null` ou ausente desvincula, preenchido vincula ou troca. Acrescentado em 2026-10-24 a pedido do usuário (sem isso o dentista não corrige um vínculo errado). Custo: o cliente precisa reenviar o `appointmentId` para mantê-lo |
| 3 | `procedure_type_id` | Banco: coluna existe | Não mapeada na entidade nem exposta |
| 4 | `notes` em `PUT /appointments` | PRD: "aceita `notes`" | `PUT` substitui o valor; `null` ou branco limpa |
| 5 | `reason` do 422 | PRD: campo `reason` | Propriedade extra do `ProblemDetail` (`BusinessException` ganha `reason` opcional) |
| 6 | Pré-checagem de conflito | Banco 4.6 oferece consulta por `&&` | Não usada (pendência da função `banco` com o plano sob RLS); o front vê a ocupação pela própria listagem |
| 7 | `PatientRules.CLINIC_ZONE` | Constante `America/Sao_Paulo` | Mantida (fora de escopo alinhar ao fuso da clínica) |
| 8 | Schema `scheduling` | Visão das Fases 0 e 1 | Obsoleto pela decisão A1 do banco: tudo em `core`; o pacote Java `scheduling` permanece |
| 9 | `AGENTS.md` | Vazio | Segue vazio; premissas lidas dos PRDs e specs |

---

## 11. Fatiamento para o `breakdown` (referência)

| Feature | Conteúdo | Depende de |
|---|---|---|
| F01 | Plataforma da agenda e fuso: `Clinic.timezone`, `ClinicZones`, `GET/PUT /clinic`, módulo `scheduling` esqueleto, mapeamento `validate`, tradução de `23P01`/`CR004`/`CR005`, `BusinessException.reason`, `PatientLookup` estendido, `audit-log` com `appointments`, `no-store`, teste de arquitetura | Fases 0 e 1 |
| F02 | Horário comercial e feriados (`business-hours`, `holidays`, `clinic-holidays`), `AppointmentSlotRules`/`DaySchedule` e o aviso de agendamentos afetados (`dryRun`) | F01 |
| F03 | Agendamentos: criar, consultar, remarcar, conflito, aplicação da `AppointmentSlotRules` (G1 e G4) | F01, F02 |
| F04 | Máquina de status: confirmar, cancelar, falta, iniciar, finalizar, reativar | F03 |
| F05 | Integração com o núcleo clínico: histórico do paciente, inativar cancela futuros, anonimização com `CR004`, nota × atendimento | F03, F04 |
| F06 | Verificação da fase: matriz de papéis, isolamento com filtro desligado, papel forjado, concorrência, desempenho, higiene de log, rastreabilidade | F01–F05 |

Ordem: F01 → F02 → F03 → F04 → F05 → F06.
