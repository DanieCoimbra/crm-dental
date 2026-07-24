# Integration Contract: F03-multitenancy-rbac-core

## 1. Backend Middlewares
### `RequireAuth()`
- **Input**: HTTP Request with `Authorization: Bearer <token>` header.
- **Output**: 401 Unauthorized if invalid. Otherwise, sets `c.Locals("clinic_id")`, `c.Locals("user_id")`, `c.Locals("role")`.

### `RequireRole(roles ...string)`
- **Input**: HTTP Request (must run after `RequireAuth`).
- **Output**: 403 Forbidden if user role is not in the allowed list.

## 2. Database Scopes (GORM)
### `WithTenant(c *fiber.Ctx)`
- **Behavior**: Appends `WHERE clinic_id = ?` to the query automatically.

## 3. Frontend Interceptors (Dio)
- **Outgoing**: Injects `Authorization: Bearer <token>`.
- **Incoming**: On 401 response, calls `logout()` and clears Secure Storage.

## 4. Inter-Feature Boundaries
- All future features (F04, etc.) MUST use `RequireAuth` and `WithTenant` for their endpoints.
