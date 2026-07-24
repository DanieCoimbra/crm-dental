# Technical Specification: Multitenancy & RBAC Core

## 1. Technical Overview
- **Feature**: F03 - Multitenancy & RBAC Core
- **Tech Stack Used**: Go (Fiber Middlewares, GORM Scopes), Flutter (Dio Interceptors).
- **Architecture Approach**: Go Middleware intercepts requests, validates JWT, and injects `clinic_id` into context. Flutter Interceptor injects token on outgoing requests and handles 401 globally.

## 2. Data Models & Schema
- **Database Changes**: No schema changes. Enforces `WHERE clinic_id = ?` on ALL read/write queries via GORM Scopes.
- **State Management**: Uses existing `AuthNotifier` to clear state on logout/401.

## 3. Component Architecture
- `AuthDioInterceptor`: 
  - **Responsibility**: Adds `Authorization: Bearer <token>` header. Listens for 401/403 to trigger global logout.
- `RoleGuard` (Widget): 
  - **Responsibility**: Conditionally renders children based on `allowedRoles`.

## 4. Core Logic & Algorithms
- **Operation**: Backend Auth Middleware
  - Step 1: Read `Authorization` header.
  - Step 2: Validate JWT signature.
  - Step 3: Extract `clinic_id` and `role`. Set in `c.Locals("clinic_id")`.
  - Step 4: Next().
- **Operation**: GORM Tenant Scope
  - Step 1: Function `WithTenant(c *fiber.Ctx)` reads `c.Locals("clinic_id")`.
  - Step 2: Modifies GORM DB instance: `db.Where("clinic_id = ?", clinic_id)`.

## 5. Error Handling & Edge Cases
- **Scenario 1**: Missing or expired JWT.
  - **Handling**: Middleware returns 401. Flutter Interceptor clears local storage and routes to `/login`.
- **Scenario 2**: User tries to access endpoint requiring 'OWNER' role, but is 'DENTIST'.
  - **Handling**: RBAC Middleware returns 403 Forbidden.

## 6. Security & Performance
- **Security Check**: Enforce Tenant Scope on ALL endpoints except public ones.
- **Performance Targets**: JWT validation < 5ms.
