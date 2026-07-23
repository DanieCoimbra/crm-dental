# Análise de Paridade: Backend (Go) vs Frontend (Flutter)

Este documento atesta a análise de equivalência de 100% do código base, comparando as rotas expostas pela API em Golang (`routes.go`) com os repositórios e interfaces criadas no Flutter.

O resultado do mapeamento mostra uma paridade de 1 para 1. Nenhuma funcionalidade de backend está isolada (código morto), e todas possuem representação visual.

### 1. Autenticação e Perfil (`/auth`, `/user`, `/profile`)
- **Backend:** `POST /auth/login`, `POST /auth/register`, `GET /user`, `PUT /profile`, `POST /profile/avatar`
- **Frontend:** Consumidos pelo `auth_provider.dart`. Acessíveis nas telas `LoginScreen`, `RegisterScreen` e no `ProfileDialog` (menu da Topbar).

### 2. Controle de Permissões e Equipe (`/roles`, `/team`)
- **Backend:** CRUD completo de `/roles` e `/team`.
- **Frontend:** Consumidos em `settings_repository.dart`. Na tela de **Configurações**, na aba "Equipe" (`_TeamTab`), com o `InviteMemberDialog` e o `RoleManagerDialog`.

### 3. Pacientes e Prontuário EMR (`/patients`, `/patients/:id/emr`, `/evolutions`, `/files`)
- **Backend:** CRUD de pacientes, upload de arquivos (`/files`), evoluções (`/evolutions`) e importação/exportação.
- **Frontend:** Consumidos em `patients_repository.dart`. A gestão ocorre em `PatientsScreen` e a parte clínica na `PatientEmrScreen`. Utiliza o `import_export_dialog.dart`.

### 4. Agenda e Listas de Espera (`/appointments`, `/waitlists`, `/shift-assignments`)
- **Backend:** CRUD de agendamentos, verificação de fila (CheckMatches) e atribuição de turnos (Escalas).
- **Frontend:** Consumidos em `schedule_repository.dart`. Acessíveis pela `ScheduleScreen`. Lista de espera (`waitlist_form_dialog.dart` e `smart_booking_dialog.dart`) e escalas na `ShiftAssignmentsScreen`.

### 5. Configurações e Infraestrutura (`/settings`, `/rooms`, `/appointment-types`)
- **Backend:** Configurações globais, Salas de atendimento e Tipos de Agendamentos.
- **Frontend:** Consumidos em `settings_repository.dart`. Tela de Configurações possui as abas `_RoomsTab` (`room_dialog.dart`) e `_AppointmentTypesTab` (`appointment_type_dialog.dart`).

### 6. Módulo Financeiro (`/financial`)
- **Backend:** `GET /financial/transactions` e `PUT /installments/:id/pay`.
- **Frontend:** Consumidos por `financial_repository.dart`. Tela `FinancialDashboardScreen` (Apenas Plano Premium).

### 7. Módulo de Estoque (`/inventory`)
- **Backend:** CRUD de itens de estoque e registro de transações (`/transactions`).
- **Frontend:** Consumidos por `inventory_repository.dart`. Visual em `InventoryScreen` e alertas via `inventory_alert_badge.dart` na Topbar.

### 8. Lixeira e Auditoria LGPD (`/trash`, `/audit-logs`)
- **Backend:** Soft delete (recuperação) e hard delete (`/force`), além da emissão de logs (`/audit-logs`).
- **Frontend:** Tela independente `TrashScreen` para lixeira e aba `_AuditTab` dentro de Configurações para rastreabilidade de ações.

### 9. SaaS, Marketing e Cupons (`/saas`, `/marketing`)
- **Backend:** Validação de cupons, alteração de plano e marketing de clínica (promo codes).
- **Frontend:** `marketing_screen.dart` (captação de pacientes pela clínica) e a tela raiz `SaasCheckoutScreen` (contratação do CRM pela clínica).

**Conclusão:** 
O ecossistema CRM apresenta consistência sistêmica perfeita. O front-end é um reflexo exato e integral dos microsserviços fornecidos pela API Go.
