# Spec — F05 Dashboard

## 1. Technical Overview
- **Feature:** `GET /api/v1/dashboard?weekOf=`.
- **Tech Stack:** Spring Web MVC, Spring Data JPA (JPQL), Jackson. Nenhuma dependência nova.
- **Architecture Approach:** módulo `crm_data.dashboard` (`service`, com `DashboardResponse`, e `web`) que consome apenas interfaces públicas de `scheduling`, `patient` e `commercial`.

## 2. Data Models & Schema
- **Banco:** nenhuma mudança (índices K1 a K5 já na V035 e anteriores).
- `DashboardResponse`: `generatedAt` (`Instant`), `timezone`, `totalPatients` (`long`), `appointmentsToday{total, byStatus}`, `returnRate{month, attended, returning, percent}`, `monthlyRevenue{month, totalAmount, approvedCount}` (omitido se nulo), `weekly{weekStart, weekEnd, days[7]{date, total, byStatus}}`. `byStatus` é `Map<String,Long>` (nomes dos status) com os 6 status, sempre completo.
- Estado em memória: nenhum.

## 3. Component Architecture
- `DashboardWindows` (regra pura): recebe `Clock` e `ZoneId`; devolve `Window(Instant ini, Instant fim)` para hoje, mês, 12 meses e semana.
- `AppointmentStats` / `AppointmentStatsService` (`scheduling.service`): `Map<AppointmentStatus,Long> countByStatus(Instant ini, Instant fim)`; `ReturnStats returnStats(Instant mesIni, Instant mesFim, Instant anoIni)`; `List<StartAndStatus> startsAndStatus(Instant ini, Instant fim)`.
- `PatientStats` / `PatientStatsService` (`patient.service`): `long activeCount()`.
- `DashboardService.get(LocalDate weekOf)`; `DashboardController` (`GET /dashboard`).
- Layout: `dashboard/{service,web}`; `DashboardResponse` fica em `service` (a camada service não depende de `web`).

## 4. Core Logic & Algorithms
- **Janelas** (`zone = ClinicZones.zone()`, `today = LocalDate.now(clock.withZone(zone))`): hoje `[today 00:00, today+1 00:00)`; mês `[dia 1, dia 1 do mês seguinte)`; 12 meses `[mês − 12, início do mês)`; semana `ref = weekOf ?? today`, `segunda = ref.with(previousOrSame(MONDAY))`, `[segunda 00:00, segunda+7 00:00)`; tudo convertido a `Instant`.
- **Validação:** `weekOf` fora de `[2000-01-01, today + 366 dias]` → 422 `WEEK_OUT_OF_RANGE`; formato inválido → 400 (conversão do parâmetro).
- **Consultas** (uma transação `readOnly`, sequenciais; `tenantFilter` + RLS):
  - K1: `select count(p) from Patient p where p.active = true`.
  - K2: `select a.status, count(a) from Appointment a where a.startsAt >= :ini and a.startsAt < :fim group by a.status`; `total` = soma sem `CANCELED` e `NO_SHOW`.
  - K4: atendidos = `count(distinct a.patientId)` com `status = COMPLETED` no mês; retornos = os mesmos com `exists (select 1 from Appointment p where p.patientId = a.patientId and p.status = COMPLETED and p.startsAt >= :anoIni and p.startsAt < :mesIni)`; `percent = retornos*100/atendidos` em `BigDecimal` escala 1 `HALF_UP`, texto; `null` se `atendidos = 0`.
  - K3 (só D): `BudgetLookup.approvedTotal(mesIni, mesFim)` e `approvedCount`; R: não chama, omite o campo.
  - K5: `select a.startsAt, a.status from Appointment a where a.startsAt >= :ini and a.startsAt < :fim`; agrupa em Java por `startsAt.atZone(zone).toLocalDate()`; preenche os 7 dias e os 6 status com zero.
- Resposta com `Cache-Control: no-store`.

## 5. Error Handling & Edge Cases
- `weekOf` inválido → 400; fora do intervalo → 422.
- Sem agendamentos: zeros, `percent: null`, 7 dias, 6 status.
- Semana na virada de mês/ano: janelas independentes do mês corrente.
- Atendimento perto da meia-noite local: agrupado pelo dia da clínica.
- Falha de leitura em qualquer consulta → 500 genérico, sem resposta parcial.
- Recepção: nenhum acesso a `BudgetLookup`; campo ausente, não `null`.

## 6. Security & Performance
- Papéis D e R; corte do faturamento no service (autorização) **e** na serialização; o `clinicId` vem só do token.
- Metas: p95 ≤ 150 ms com 4 concorrentes na massa descrita; 5 consultas indexadas por requisição, sem N+1; ocupa 1 conexão do pool por poucos ms.
- Nenhum dado pessoal no dashboard (apenas agregados).
