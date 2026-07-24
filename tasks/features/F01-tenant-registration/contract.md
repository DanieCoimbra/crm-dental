# Integration Contract: F01-tenant-registration

## 1. API Endpoints
### POST `/api/v1/auth/register-clinic`
- **Auth Required**: False
- **Description**: Registers a new clinic and its owner.

#### Request Payload
```json
{
  "clinic_name": "string",
  "cnpj": "string",
  "owner_name": "string",
  "email": "string (email)",
  "password": "string (min 8 chars, alphanumeric)"
}
```

#### Response (Success: 201 Created)
```json
{
  "message": "Clínica registrada com sucesso"
}
```

#### Response (Error: 400/409/500)
```json
{
  "error": "Descrição do erro (ex: CNPJ já existe)"
}
```

## 2. Inter-Feature Boundaries
- **F02-authentication-login**: Upon successful registration (201), the frontend will route the user to `/login` (owned by F02).
