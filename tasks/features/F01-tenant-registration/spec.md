# Technical Specification: Tenant Registration

## 1. Technical Overview
- **Feature**: F01 - Tenant Registration
- **Tech Stack Used**: Go (Fiber, GORM), Flutter (Riverpod, Dio), PostgreSQL.
- **Architecture Approach**: Single database transaction handling two inserts (Clinic + Owner) in Go. Flutter screen with form validation.

## 2. Data Models & Schema
- **Database Changes**: 
  - `user_role` ENUM (`OWNER`, `ADMIN`, `DENTIST`, `RECEPTIONIST`).
  - `clinics` table (id, name, cnpj).
  - `users` table (id, clinic_id, name, email, password_hash, role, failed_attempts, locked_until).
- **State Management**:
  - Flutter `FormState` for local validation. No global state needed until login.

## 3. Component Architecture
- `RegisterScreen`: 
  - **Props**: None
  - **Responsibility**: Render registration form. Call API via Dio. Redirect to login on success.
  - **State**: Loading boolean, text controllers.

## 4. Core Logic & Algorithms
- **Operation**: Register Clinic
  - Step 1: Validate payload (non-empty, valid email, strong password).
  - Step 2: Begin DB Tx. Hash password with bcrypt.
  - Step 3: Insert Clinic. Retrieve UUID.
  - Step 4: Insert User with `clinic_id` and role `OWNER`.
  - Step 5: Commit Tx.

## 5. Error Handling & Edge Cases
- **Scenario 1**: CNPJ or Email already exists.
  - **Handling**: Return 409 Conflict. Show inline error "Email/CNPJ já cadastrado".
- **Scenario 2**: Transaction fails halfway.
  - **Handling**: Rollback DB Tx. Return 500 Internal Server Error.

## 6. Security & Performance
- **Security Check**: Hash password via Bcrypt cost 10. Avoid SQL injection via GORM parameters.
- **Performance Targets**: Tx completion < 100ms.
