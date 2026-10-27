# PRD — F05 Dashboard

**Derivação:** PRD global da Fase 4, US-P4-10; FR-14 e FR-15; decisões I4 e I8 a I10 (um `GET /dashboard`, recepção sem faturamento, competência, semana de segunda a domingo). Spec global, seções 4.7, 5, 6 e 7.
**Dependências externas:** F01 (regra de rota de `/dashboard` para D e R), `BudgetLookup.approvedTotal/approvedCount` (Fase 3, sem mudança), `ClinicZones`, `Clock`, `TenantContext.role()`, entidades `Appointment` e `Patient`, banco V035 (`ix_appt_completed_patient`) e índices existentes. Requer o access token da Fase 0.

## 1. Introdução

- **Problema:** a tela inicial precisa de números do dia, do mês e da semana, e a recepção não pode ver faturamento.
- **Solução:** um único `GET /dashboard` que o backend calcula (janelas no fuso da clínica, consultas JPQL numa transação `readOnly`) e que corta o faturamento para a recepção.
- **Metas:**
  1. `GET /dashboard` p95 ≤ 150 ms com 4 requisições concorrentes, 100 mil agendamentos, 20 mil pacientes e 1,5 mil orçamentos aprovados.
  2. Os cinco indicadores batem com contagens independentes em massa semeada com todos os status.
  3. A recepção nunca dispara a consulta de faturamento nem recebe o campo.
  4. Cobertura ≥ 80% em `DashboardService` e 100% das ramificações de `DashboardWindows`.

## 2. User stories

### US-001: Indicadores do dia e do mês
**Descrição:** Como dentista ou recepcionista, quero ver pacientes ativos, consultas de hoje e taxa de retorno.

**Critérios de aceite:**
- [ ] `GET /api/v1/dashboard` (D, R) devolve `totalPatients` (pacientes ativos), `appointmentsToday` (`total` sem `CANCELED` e `NO_SHOW`, `byStatus` com os 6 status) e `returnRate` (`month`, `attended`, `returning`, `percent` com 1 casa `HALF_UP`, `null` se `attended = 0`).
- [ ] "Retorno" = paciente com consulta `COMPLETED` no mês que tinha outra `COMPLETED` nos 12 meses anteriores ao início do mês; contado por paciente distinto.
- [ ] Hoje, mês e 12 meses são calculados no fuso da clínica, inclusive na virada de mês e de ano.

### US-002: Faturamento do mês (só dentista)
**Descrição:** Como dentista, quero ver o faturamento aprovado do mês.

**Critérios de aceite:**
- [ ] `monthlyRevenue` (`month`, `totalAmount` em texto com 2 casas, `approvedCount`) por competência (orçamentos aprovados no mês, via `BudgetLookup`).
- [ ] Para R o campo é **omitido** do JSON e `BudgetLookup` não é chamado.

### US-003: Semana
**Descrição:** Como usuário, quero ver os atendimentos da semana por dia e status.

**Critérios de aceite:**
- [ ] `weekly` com `weekStart` (segunda), `weekEnd` (domingo) e **7** `days` (`date`, `total` sem `CANCELED`/`NO_SHOW`, `byStatus` com os 6 status); `?weekOf=YYYY-MM-DD` escolhe a semana (padrão: a de hoje).
- [ ] Agrupamento pelo dia local da clínica; atendimento às 22h de domingo local (01h UTC de segunda) cai no domingo.
- [ ] `weekOf` com formato inválido → 400; fora de `[2000-01-01, hoje + 366 dias]` → 422 `WEEK_OUT_OF_RANGE`.
- [ ] Resposta com `generatedAt`, `timezone` e `Cache-Control: no-store`; sem dados → zeros, `percent: null`, 7 dias presentes.

## 3. Requisitos funcionais

- **FR-1:** `DashboardWindows` puro (hoje, mês, 12 meses, semana) com `Clock` e `ZoneId`.
- **FR-2:** `AppointmentStats` (interface pública em `scheduling.service`): `countByStatus`, `returnStats`, `startsAndStatus`; `PatientStats.activeCount()` em `patient.service`.
- **FR-3:** `DashboardService` `readOnly`, `@PreAuthorize("hasAnyRole('DENTIST_ADMIN','RECEPTIONIST')")`, consultas sequenciais numa conexão.
- **FR-4:** `DashboardController` e `DashboardResponse` (`@JsonInclude(NON_NULL)` para `monthlyRevenue`).
- **FR-5:** Nenhuma tabela, índice ou módulo `commercial` alterado.

## 4. Fora do escopo

- Gráficos por período livre, metas, comparação com o mês anterior, exportação e cache.
- Indicadores clínicos individuais; dashboard por profissional.
- Inadimplência e caixa por forma de pagamento (relatórios financeiros da Fase 3).

## 5. Considerações técnicas e riscos

- O agrupamento por dia é em Java porque a semana tem poucas dezenas de linhas; SQL nativo de fuso fica de fora.
- O índice condicional de K1 não foi necessário no banco (K1 = 7,2 ms com 100 mil linhas); manter a consulta como está.
- `monthlyRevenue` usa competência; recebimentos de parcelas ficam nos relatórios da Fase 3.
- O retorno depende de `ix_appt_completed_patient`; `EXPLAIN` já provado na Fase 4 do banco.
