# Action Plan: F05-Inventory Control

## 1. Local Scope
- **Derivation**: Extracted from [PRD Fullstack MVP](../../prd-dental-crm-fullstack.md).
- **Responsibility**: Gerenciar catálogo de materiais odontológicos. Funcionalidade principal é o abatimento autônomo (baixa de estoque) de "receitas de materiais" atreladas a procedimentos sempre que uma consulta é finalizada, além de disparar notificações de estoque crítico na UI.

## 2. External Dependencies (Before starting)
- Requer `F01-multi-tenant-core`.
- Fortemente acoplado ao gatilho de "Finish Appointment" do módulo `F03-smart-agenda`.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] 
  - Definir tabelas `products`, `procedure_materials` e logs `stock_movements`.
  - Definir evento assíncrono ou transacional que une `F03` e `F05`.
- **Phase 2**: [Local Spec & Logic]
  - Lógica de transação no Go que previne estoque negativo de quebrar a finalização da consulta (apenas avisa/loga).
  - StateNotifier (`InventoryAlertProvider`) no Flutter para os Badges vermelhos na TopBar.
- **Phase 3**: [Integration]
  - Teste End-to-End: Finalizar consulta no Flutter -> Verifica decréscimo de item -> Badge pisca no Header.

## 4. Next Steps
- Run the `/spec-write` and `/contract` skills specifically for this folder to define the internal Event/Hook between the Agenda and the Inventory systems.
