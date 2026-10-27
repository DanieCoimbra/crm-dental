# Contract — F05 Dashboard

## 1. Resumo
- **Feature:** F05-dashboard
- **Versão:** 1.0.0
- **Consumidores:** Frontend React (tela inicial), F06. Requer `Authorization: Bearer` da Fase 0.

## 2. Entradas

Prefixo `/api/v1`. **D** = `DENTIST_ADMIN`, **R** = `RECEPTIONIST`.

| Método | Rota | Papéis | Query |
|---|---|---|---|
| GET | `/dashboard` | D, R | `weekOf=yyyy-MM-dd` (opcional; padrão = semana de hoje) |

## 3. Saídas

### Sucesso
- **200** (`Cache-Control: no-store`):
  ```json
  {
    "generatedAt": "2026-10-27T15:04:05Z",
    "timezone": "America/Sao_Paulo",
    "totalPatients": 128,
    "appointmentsToday": { "total": 6, "byStatus": { "SCHEDULED": 2, "CONFIRMED": 1, "IN_PROGRESS": 1, "COMPLETED": 2, "NO_SHOW": 0, "CANCELED": 1 } },
    "returnRate": { "month": "2026-10", "attended": 40, "returning": 25, "percent": "62.5" },
    "monthlyRevenue": { "month": "2026-10", "totalAmount": "18450.00", "approvedCount": 9 },
    "weekly": { "weekStart": "2026-10-26", "weekEnd": "2026-11-01",
      "days": [ { "date": "2026-10-26", "total": 5, "byStatus": { "SCHEDULED": 0, "CONFIRMED": 1, "IN_PROGRESS": 0, "COMPLETED": 4, "NO_SHOW": 0, "CANCELED": 1 } } ] }
  }
  ```
- Para **R**, `monthlyRevenue` **não existe** no JSON. `byStatus` sempre traz os 6 status; `days` sempre traz 7 dias (segunda a domingo); `percent` é `null` quando `attended = 0`.

### Erros (formato da Fase 0)
| Status | Quando |
|---|---|
| 400 `validation` | `weekOf` com formato inválido |
| 401 `unauthorized` | Token ausente ou inválido |
| 422 `business-rule` | `reason: WEEK_OUT_OF_RANGE` (fora de 2000-01-01 a hoje + 366 dias) |
| 500 `internal` | Falha de leitura (sem detalhes) |

## 4. Regras e limites
- Datas e janelas no fuso da clínica (`ClinicZones`); semana de segunda a domingo.
- `totalPatients`: pacientes **ativos** da clínica (anonimizados são inativos).
- `appointmentsToday.total` e `days[].total`: exclui `CANCELED` e `NO_SHOW`; `byStatus` traz todos.
- `returnRate`: paciente distinto com `COMPLETED` no mês que tinha `COMPLETED` nos 12 meses anteriores ao início do mês; `percent` com 1 casa, `HALF_UP`, em texto.
- `monthlyRevenue`: por competência (orçamentos aprovados no mês); `totalAmount` em texto com 2 casas.
- A recepção nunca dispara a consulta de faturamento.

### Interfaces internas
```java
// crm_data.scheduling.service.AppointmentStats (pública)
Map<AppointmentStatus, Long> countByStatus(Instant ini, Instant fim);
ReturnStats returnStats(Instant mesIni, Instant mesFim, Instant anoIni);  // (attended, returning)
List<StartAndStatus> startsAndStatus(Instant ini, Instant fim);

// crm_data.patient.service.PatientStats (pública)
long activeCount();

// crm_data.dashboard.service.DashboardWindows (puro)
Window today(); Window month(); Window previousTwelveMonths(); Window week(LocalDate ref);
```

## 5. Eventos emitidos (internos)
Nenhum.

## 6. Critérios de aceite (integração)
- [ ] D recebe os cinco blocos; R recebe tudo menos `monthlyRevenue`; sem token, 401.
- [ ] Massa semeada com todos os status, pacientes ativos e inativos, retornos no mês e orçamentos em meses diferentes: K1 a K5 conferem com contagens independentes.
- [ ] `percent` arredondado em `HALF_UP` com 1 casa; sem atendidos → `null`.
- [ ] `weekOf` de uma semana na virada de mês e de ano; domingo 22h local entra no domingo; `weekOf` inválido → 400; fora do intervalo → 422.
- [ ] Banco sem dados: zeros, `percent: null`, 7 dias e 6 status.
- [ ] Outra clínica com massa própria não altera os números (isolamento); RLS sozinha (filtro desligado) mantém o resultado.
- [ ] Mock de `BudgetLookup` não é chamado para R; `DashboardWindows` com 100% das ramificações; p95 ≤ 150 ms com 4 concorrentes.
