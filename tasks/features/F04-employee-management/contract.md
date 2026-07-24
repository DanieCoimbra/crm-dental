# Integration Contract: F04-employee-management

## 1. API Endpoints
### POST `/api/v1/employees`
- **Auth Required**: True
- **Role Required**: `OWNER` (or `ADMIN`, depending on business logic, for now `OWNER` only)
- **Description**: Creates a new employee linked to the authenticated user's clinic.

#### Request Payload
```json
{
  "name": "string",
  "email": "string (email)",
  "password": "string (min 8 chars)",
  "role": "ADMIN | DENTIST | RECEPTIONIST"
}
```

#### Response (Success: 201 Created)
```json
{
  "id": "uuid",
  "name": "string",
  "email": "string",
  "role": "string",
  "created_at": "timestamp"
}
```

#### Response (Error: 400/409/403)
```json
{
  "error": "Descrição do erro (ex: E-mail já utilizado)"
}
```

### GET `/api/v1/employees`
- **Auth Required**: True
- **Role Required**: `OWNER` or `ADMIN`
- **Description**: Lists all employees of the clinic.

## 2. Inter-Feature Boundaries
- **F03-multitenancy-rbac-core**: This feature heavily relies on the `RequireAuth` and `RequireRole` middlewares provided by F03.
