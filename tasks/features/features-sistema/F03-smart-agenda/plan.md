# Action Plan: F03-Smart Agenda

## 1. Local Scope
- **Derivation**: Extracted from PRD Fullstack MVP.
- **Responsibility**: Orquestrar o calendário de consultas da clínica de forma autônoma e inteligente. Inclui o timer de atendimento ("Start/Finish"), e a inteligência heurística de "Smart Booking" (encaixe de pacientes da fila de espera em horários ociosos gerados por cancelamentos ou término antecipado).

## 2. External Dependencies (Before starting)
- Requer `F01-multi-tenant-core` (Autenticação e estrutura Multi-tenant).
- Requer `F02-patient-emr` (As consultas são atreladas a um `patient_id` existente).

## 3. Execution Phases
- **Phase 1**: [Setup & Data Models] 
  - Estruturação dos modelos `appointments` e `waitlists` via GORM.
  - Implementação do painel de Fila de Espera no Flutter.
- **Phase 2**: [Core Features]
  - Lógica do Timer: Gravar `actual_start_time` e `actual_end_time` ao iniciar/finalizar.
  - Heurística de Smart Booking: Algoritmo (`WaitlistService.CheckMatches`) que avalia o tempo ocioso vs tempo estimado de fila.
  - Renderização do Calendário Interativo Drag-and-drop no Flutter (`calendar_view`).
- **Phase 3**: [Internal Integration & Polish]
  - Criação do pub/sub interno no Go para disparar o evento `Appointment.Completed` (Será consumido no futuro pela `F05-inventory-control` para baixa automática de estoque).
  - Testes de concorrência para evitar Overbooking (Double Booking).

## 4. Current Status
- Fase 1 e Fase 2 estão praticamente concluídas na base de código. O foco restante para finalizar a F03 será refinar os tratamentos de erro de concorrência na agenda e preparar o gancho de eventos internos (Appointment.Completed) para integração futura com o módulo de Estoque.
