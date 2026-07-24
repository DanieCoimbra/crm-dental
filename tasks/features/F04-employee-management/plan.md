# Action Plan: Employee Management

## 1. Local Scope
- **Derivation**: Extracted from [prd-auth-multitenancy.md](../../prd-auth-multitenancy.md).
- **Responsibility**: Allow an authenticated Owner to create new employees (Admin, Dentist, Receptionist) linked strictly to their `clinic_id` with an initial password.

## 2. External Dependencies (Before starting)
- Requires `F03-multitenancy-rbac-core` to ensure the endpoint correctly extracts the Owner's `clinic_id` to bind the new employee.

## 3. Execution Phases
- **Phase 1**: [Setup & Contracts] Define Employee creation API contract.
- **Phase 2**: [Local Spec & Logic] Go handler and usecase for inserting an employee record with hashed password and selected role.
- **Phase 3**: [Integration] Flutter UI for Owner dashboard to invite/create employees.

## 4. Next Steps
- Run `/plan` on this folder or request the agent to start Phase 1.
