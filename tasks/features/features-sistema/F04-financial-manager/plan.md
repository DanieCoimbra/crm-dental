# Action Plan: F04-Financial Manager

## 1. Local Scope
- **Derivation**: Extracted from [PRD Fullstack MVP](../../prd-dental-crm-fullstack.md).
- **Responsibility**: Prover ferramentas financeiras completas para a operação interna da clínica: controle de receitas, despesas, divisão de parcelamentos (`installments`), emissão em PDF de recibos de tratamentos e exibição de relatórios (Dashboards) de fluxo de caixa.

## 2. External Dependencies (Before starting)
- Requer `F01-multi-tenant-core`.
- Depende indiretamente de `F02-patient-emr` pois receitas são atreladas aos pacientes.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] 
  - Modelar transações financeiras garantindo precisão (campos `int64` para centavos).
  - Contrato para formatação de parcelas e recibos.
- **Phase 2**: [Local Spec & Logic]
  - UseCase de fracionamento de parcelas (ex: 1200 em 3x).
  - Geração Nativa de PDF no Flutter (`printing`).
  - Implementar gráficos de `fl_chart` para relatórios mensais.
- **Phase 3**: [Integration]
  - Garantir que baixa de pagamentos atualize dashboards reativos.

## 4. Next Steps
- Run the `/spec-write` and `/contract` skills specifically for this folder to design the receipt PDF layout and the exact math precision rules.
