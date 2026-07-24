# Technical Specification: Employee Management

## 1. Technical Overview
- **Feature**: F04 - Employee Management
- **Tech Stack Used**: Go (Fiber, GORM), Flutter (Riverpod).
- **Architecture Approach**: Protected endpoint (requires OWNER role). Inserts new user linked to the Owner's `clinic_id`.

## 2. Data Models & Schema
- **Database Changes**: No new tables. Inserts into existing `users` table.
- **State Management**: `EmployeeListNotifier` in Flutter to manage the list of employees.

## 3. Component Architecture
- `EmployeeListScreen`: Lists current employees.
- `CreateEmployeeDialog`: Form to add a new employee (Name, Email, Pass, Role).

## 4. Core Logic & Algorithms
- **Operation**: Create Employee
  - Step 1: Backend validates request (Role must be Admin, Dentist, or Receptionist).
  - Step 2: Hash password.
  - Step 3: Insert user setting `clinic_id` from `c.Locals("clinic_id")`.
  - Step 4: Return success.

## 5. Error Handling & Edge Cases
- **Scenario 1**: Email already registered in the system (even in another clinic).
  - **Handling**: Return 409 Conflict.
- **Scenario 2**: Non-owner tries to create employee.
  - **Handling**: Blocked by `RequireRole("OWNER")` middleware. Returns 403.

## 6. Security & Performance
- **Security Check**: Crucial to NOT trust the `clinic_id` from the payload, ALWAYS use the one extracted from the JWT token.
- **Performance Targets**: Creation < 100ms.
