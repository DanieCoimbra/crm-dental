# Integration Contract: F02-authentication-login

## 1. API Endpoints
### POST `/api/v1/auth/login`
- **Auth Required**: False
- **Description**: Authenticates user and returns JWT.

#### Request Payload
```json
{
  "email": "string",
  "password": "string"
}
```

#### Response (Success: 200 OK)
```json
{
  "token": "jwt_string",
  "user": {
    "id": "uuid",
    "clinic_id": "uuid",
    "name": "string",
    "role": "OWNER | ADMIN | DENTIST | RECEPTIONIST"
  }
}
```

#### Response (Error: 401 Unauthorized / 429 Too Many Requests)
```json
{
  "error": "Mensagem de erro",
  "locked_until": "timestamp (optional, only on 429)"
}
```

## 2. Internal Frontend Interfaces (Riverpod)
- **State**: `ref.watch(authProvider)` exposes the user session.
- **Actions**: `ref.read(authProvider.notifier).login(email, password)`
