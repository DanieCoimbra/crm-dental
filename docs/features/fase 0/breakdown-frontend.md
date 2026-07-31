# Task Breakdown / Decomposição de Módulos (Frontend Flutter)

- **Projeto:** CRM Clínica Odontológica (Solo / SaaS Multi-tenant)
- **Documento Relacionado:** `docs/features/spec-frontend-global.md`
- **Autor:** Arquiteto de Software Flutter
- **Data:** 31/07/2026

---

## 1. Módulos Isolados (Features)

A atualização e alinhamento do app Flutter foi dividida em 5 pacotes de trabalho/features isoladas e coesas:

```
docs/features/front/
├── F01-front-auth-profile/       # Perfil, Login, Registro de Clínica & Interceptor HTTP 401/403
├── F02-front-patients-emr/        # Formulário de Pacientes, PatientSummaryTab e PatientFilesTab
├── F03-front-inventory-dialogs/   # InventoryFormDialog & InventoryTransactionDialog
├── F04-front-schedule-dialogs/    # AppointmentFormDialog, Waitlist & ShiftAssignment Dialogs
└── F05-front-financial-settings/  # TransactionFormDialog, RoleManager, Room & AppointmentType Dialogs
```

---

## 2. Detalhamento por Feature

### F01-front-auth-profile (Auth, Profile & Core Security)
- **Escopo:**
  - Ajustar chamadas aos endpoints `POST /api/v1/auth/register-clinic` e `GET /api/v1/profile`.
  - Atualizar o `AuthInterceptor` em `lib/core/network/api_client.dart` para deslogar em `UNAUTHORIZED` (401) e exibir `SnackBar` em `FORBIDDEN` (403).

### F02-front-patients-emr (Pacientes & Prontuário Eletrônico)
- **Escopo:**
  - Criar `PatientFormDialog` em `lib/features/patients/presentation/widgets/patient_form_dialog.dart`.
  - Criar `PatientSummaryTab` e `PatientFilesTab` em `lib/features/patients/presentation/widgets/`.
  - Conectar as abas no `patient_emr_screen.dart`.

### F03-front-inventory-dialogs (Estoque & Movimentações)
- **Escopo:**
  - Criar `InventoryFormDialog` em `lib/features/inventory/presentation/widgets/inventory_form_dialog.dart`.
  - Criar `InventoryTransactionDialog` em `lib/features/inventory/presentation/widgets/inventory_transaction_dialog.dart`.
  - Restaurar botões de ação e FloatingActionButton em `inventory_screen.dart`.

### F04-front-schedule-dialogs (Agenda & Consultas)
- **Escopo:**
  - Criar `AppointmentFormDialog` em `lib/features/schedule/presentation/widgets/appointment_form_dialog.dart`.
  - Criar `WaitlistFormDialog` em `lib/features/schedule/presentation/widgets/waitlist_form_dialog.dart`.
  - Criar `ShiftAssignmentFormDialog` em `lib/features/schedule/presentation/widgets/shift_assignment_form_dialog.dart`.
  - Restaurar chamadas na `schedule_screen.dart` e `shift_assignments_screen.dart`.

### F05-front-financial-settings (Financeiro & Configurações)
- **Escopo:**
  - Criar `TransactionFormDialog` em `lib/features/financial/presentation/widgets/transaction_form_dialog.dart`.
  - Criar `RoleManagerDialog`, `RoomDialog`, `AppointmentTypeDialog` e `ImportExportDialog` em `lib/features/settings/presentation/widgets/`.
  - Conectar os botões e abas na `financial_dashboard_screen.dart` e `settings_screen.dart`.
