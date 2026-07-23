# Action Plan: F07-dashboard

## 1. Local Scope
- **Derivation**: Extracted from [PRD Dental CRM Fullstack](../../prd-dental-crm-fullstack.md) (US-006) and [Spec](../../spec-dental-crm-fullstack.md).
- **Responsibility**: Prover métricas agregadas visuais (KPIs e Gráficos de faturamento e agendamentos) para usuários administradores e donos de clínicas avaliarem a saúde do negócio.

## 2. External Dependencies (Before starting)
- Requires `F01-multi-tenant-core` para restrição de tenant (`clinic_id`) e papéis (`role == 'admin'`).
- Requires `F04-financial-manager` para consultas à tabela de `clinic_transactions`.
- Requires `F03-smart-agenda` para contagem de status dos `appointments`.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] 
  - Definir estrutura do JSON de resposta (receita, quantidade de consultas, taxa de faltas).
  - Configurar interface do Repositório (Queries SQL).
- **Phase 2**: [Local Spec & Logic]
  - Construir endpoint `GET /api/v1/dashboard/stats` no Go.
  - Implementar queries otimizadas com Group By e Extrato de Mês no PostgreSQL.
  - Desenvolver Provider/Notifier (`DashboardNotifier`) no Flutter.
- **Phase 3**: [Integration]
  - Montar a UI `DashboardScreen` utilizando `fl_chart` para os gráficos.
  - Ligar a UI ao estado consumindo os KPIs.

## 4. Next Steps
- Run `/spec-write` in this folder to detail the technical specs (UI mapping and SQL queries).
- Run `/contract` to define the JSON interface boundary between Go and Flutter.
