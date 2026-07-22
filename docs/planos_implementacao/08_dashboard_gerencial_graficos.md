# Plano de Implementação: Dashboard Gerencial com Gráficos

## 1. Visão Geral
Construir a tela inicial (Dashboard Executive Gerencial) do CRM Clínico, composta por indicadores chave de desempenho (KPIs), estatísticas de atendimento em tempo real, taxa de faltas (No-Show Rate), tempo médio de consulta e faturamento mensal. A interface oferece gráficos interativos e seletores de período flexíveis.

---

## 2. Tecnologias Aplicáveis & Skills Utilizadas
- **Backend:** Go 1.26 + Fiber v2, PostgreSQL (GORM + SQL aggregations de alta performance), caching em memória ou Redis para métricas de alto tráfego.
- **Frontend:** Flutter + Riverpod, biblioteca `fl_chart` para renderização vetorial de alta performance (LineChart, BarChart, PieChart), seletor de data customizado.
- **Visual Excellence & UX:** Design responsivo com cards elevados, gradientes sutis, micro-animações e suporte nativo ao Modo Claro e Escuro.

---

## 3. Regras de Negócio
1. **Filtro Temporal Dinâmico:**
   - O usuário pode selecionar o período de análise: `Hoje`, `Esta Semana`, `Este Mês` (Padrão), `Ano Atual` ou `Intervalo Customizado`.
2. **Estatísticas de Atendimento:**
   - **Total de Consultas:** Quantidade agendada no período.
   - **Atendimentos Realizados:** Consultas concluídas com sucesso.
   - **Taxa de Faltas (No-Show Rate):** `(Consultas Faltou / Total Agendado) * 100`.
   - **Tempo Médio de Atendimento:** Calculado a partir do Timer de Atendimento (`actual_end_time - actual_start_time`).
3. **Métricas Financeiras da Clínica:**
   - **Faturamento Bruto:** Soma dos recebimentos confirmados no período.
   - **Ticket Médio por Paciente:** `Faturamento Bruto / Pacientes Atendidos`.
4. **Ranking de Procedimentos:**
   - Gráfico de pizza / rosca mostrando os 5 procedimentos mais realizados (ex: Limpeza 35%, Restauração 25%, Ortodontia 20%, Outros 20%).

---

## 4. Integração Total (Backend + Frontend)

### 4.1. Backend (Go)

#### DTOs de Resposta (`internal/core/domain/dashboard.go`)
```go
type DashboardMetricsDTO struct {
    Period              string              `json:"period"`
    StartDate           time.Time           `json:"start_date"`
    EndDate             time.Time           `json:"end_date"`
    TotalAppointments   int64               `json:"total_appointments"`
    CompletedCount      int64               `json:"completed_count"`
    CanceledCount       int64               `json:"canceled_count"`
    NoShowCount         int64               `json:"no_show_count"`
    NoShowRatePercent   float64             `json:"no_show_rate_percent"`
    AvgDurationMinutes  float64             `json:"avg_duration_minutes"`
    TotalRevenueCents   int64               `json:"total_revenue_cents"`
    AverageTicketCents  int64               `json:"average_ticket_cents"`
    DailyChartData      []DailyStats        `json:"daily_chart_data"`
    TopProcedures       []ProcedureShare    `json:"top_procedures"`
}

type DailyStats struct {
    Date         string `json:"date"`          // "2026-07-21"
    Appointments int    `json:"appointments"`
    RevenueCents int64  `json:"revenue_cents"`
}

type ProcedureShare struct {
    Name       string  `json:"name"`
    Count      int     `json:"count"`
    Percentage float64 `json:"percentage"`
}
```

#### SQL Aggregation Queries (`internal/database/dashboard_repository.go`)
- Consultas otimizadas com agregações `COUNT(*)`, `SUM()`, `AVG()` utilizando índices em `(clinic_id, start_time)` e `(clinic_id, status)`.

---

### 4.2. Frontend (Flutter)

#### Módulo de Dashboard (`lib/features/dashboard/presentation/`)
- **Tela Principal (`dashboard_screen.dart`):**
  - Header com Seletor de Período (`SegmentedButton` ou `Dropdown`).
  - Grid de **KPI Cards** (`kpi_card_widget.dart`):
    - Total de Consultas.
    - Taxa de Faltas (com badge vermelho de atenção se > 15%).
    - Faturamento do Período.
    - Tempo Médio de Consulta.
- **Gráfico de Evolução (`daily_revenue_appointments_chart.dart`):**
  - Implementado com `fl_chart` (`LineChart` com gradiente sob a curva).
  - Exibe eixo Y duplo (Consultas x Faturamento R$).
- **Gráfico de Rosca de Procedimentos (`top_procedures_pie_chart.dart`):**
  - Implementado com `fl_chart` (`PieChart` com legendas interativas ao passar o cursor/toque).

---

## 5. Plano de Testes & Validação
1. **Teste de Performance de Query:** Executar a consulta SQL do dashboard em um banco populado com 50.000 agendamentos e validar tempo de resposta < 50ms.
2. **Teste de Arredondamento e Porcentagens:** Garantir que a soma das porcentagens do gráfico de procedimentos resulte em 100%.
3. **Teste Responsivo UI (Flutter):** Testar renderização do Dashboard em telas Desktop (1920x1080), Tablet (1024x768) e Mobile (390x844).
