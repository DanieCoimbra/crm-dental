# 🔨 Breakdown de Features — Migração GORM ➔ SQLC + pgx/v5

**Documento:** Breakdown e Fracionamento de Escopo  
**PRD de Origem:** `docs/global/prd-sqlc-migration.md`  
**Spec de Origem:** `docs/global/spec-sqlc-migration.md`  
**Projeto:** Dental Clinic CRM (`backend-go`)  
**Autor:** Go Architect Agent  
**Versão:** 1.0  
**Status:** Aguardando Aprovação Humana (Etapa 3 de 5)  

---

## 1. Mapeamento dos Pacientes de Migração (Features Isoladas)

O escopo da migração do ORM para SQLC é fracionado em 5 pacotes independentes e coesos:

```
docs/features/sqlc/
├── F01-sqlc-auth-tenancy/
│   ├── spec.md
│   └── contract.md
├── F02-sqlc-patients-emr/
│   ├── spec.md
│   └── contract.md
├── F03-sqlc-appointments-schedule/
│   ├── spec.md
│   └── contract.md
├── F04-sqlc-financial-budgets/
│   ├── spec.md
│   └── contract.md
└── F05-sqlc-inventory-saas/
    ├── spec.md
    └── contract.md
```

---

## 2. Detalhamento dos Pacientes de Feature

### 📦 F01: Autenticação, Clínicas & Multi-Tenancy (`F01-sqlc-auth-tenancy`)
- **Tabelas Envolvidas:** `clinics`, `roles`, `users`, `used_checkout_sessions`.
- **Arquivos SQL:** `db/queries/clinics.sql`, `db/queries/users.sql`, `db/queries/roles.sql`.
- **Repositórios a Refatorar:** `UserRepository`, `ClinicRepository`, `RoleRepository`.

### 📦 F02: Prontuário Eletrônico & Odontograma (`F02-sqlc-patients-emr`)
- **Tabelas Envolvidas:** `patients`, `clinical_evolutions`, `clinical_notes`, `medical_documents`, `teeth_statuses`, `patient_files`.
- **Arquivos SQL:** `db/queries/patients.sql`, `db/queries/clinical_records.sql`, `db/queries/teeth.sql`.
- **Repositórios a Refatorar:** `PatientRepository`, `ClinicalEvolutionRepository`, `MedicalDocumentRepository`, `TeethRepository`.

### 📦 F03: Agendamento, Salas & Fila de Espera (`F03-sqlc-appointments-schedule`)
- **Tabelas Envolvidas:** `appointments`, `appointment_types`, `rooms`, `waitlists`, `shift_assignments`.
- **Arquivos SQL:** `db/queries/appointments.sql`, `db/queries/rooms.sql`, `db/queries/waitlist.sql`.
- **Repositórios a Refatorar:** `AppointmentRepository`, `RoomRepository`, `WaitlistRepository`.

### 📦 F04: Financeiro, Procedimentos & Orçamentos (`F04-sqlc-financial-budgets`)
- **Tabelas Envolvidas:** `clinic_transactions`, `procedures`, `budgets`, `budget_items`.
- **Arquivos SQL:** `db/queries/financial.sql`, `db/queries/procedures.sql`, `db/queries/budgets.sql`.
- **Repositórios a Refatorar:** `FinancialRepository`, `ProcedureRepository`, `BudgetRepository`.

### 📦 F05: Estoque, SaaS Assinaturas & Auditoria (`F05-sqlc-inventory-saas`)
- **Tabelas Envolvidas:** `inventory_items`, `inventory_transactions`, `procedure_materials`, `subscriptions`, `audit_logs`.
- **Arquivos SQL:** `db/queries/inventory.sql`, `db/queries/saas.sql`, `db/queries/audit.sql`.
- **Repositórios a Refatorar:** `InventoryRepository`, `SubscriptionRepository`, `AuditRepository`.

---

## 3. Próximos Passos (Workflow Go Architect)

- **[CONCLUÍDO] Etapa 1:** PRD Global (`docs/global/prd-sqlc-migration.md`).
- **[CONCLUÍDO] Etapa 2:** Especificação Técnica Global (`docs/global/spec-sqlc-migration.md`).
- **[ATUAL] Etapa 3:** Aprovação Humana deste Breakdown de Features (`docs/features/breakdown-sqlc-migration.md`).
- **Etapa 4:** Especificação Técnica Detalhada por Feature (`spec.md` por pacote).
- **Etapa 5:** Definição dos Contratos de Repositórios (`contract.md` por pacote).
