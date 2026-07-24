# Contract: F07-dashboard

## 1. Overview
Define a interface do Dashboard Gerencial, permitindo acesso aos dados agregados de métricas financeiras e clínicas.

## 2. Inputs (API Requests)
- `GET /api/v1/dashboard/stats`
- Headers: `Authorization: Bearer <token>`
- Query Params: `month` (opcional), `year` (opcional)

## 3. Outputs (API Responses)
```json
{
  "total_revenue_cents": 1500000,
  "total_appointments": 120,
  "canceled_appointments": 5,
  "cancellation_rate": 4.16,
  "chart_data": [
    {"day": 1, "revenue": 50000},
    {"day": 2, "revenue": 100000}
  ]
}
```

## 4. Integration Rules
- A UI não vai processar a matemática. O backend entregará todos os cálculos práticos e formatados para consumo direto pelo `fl_chart`.
- Falta de permissão retorna `403 Forbidden`.

## 5. Boundaries
- O Dashboard NÃO suporta relatórios exportáveis avançados em PDF ou Excel por enquanto.
- Limita-se apenas ao mês corrente e anterior, sem filtros históricos extensivos complexos.
