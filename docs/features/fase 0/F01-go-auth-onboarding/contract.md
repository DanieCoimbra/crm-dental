# Contrato de API: Auth & Tenant Onboarding

## 1. Resumo do Contrato
- **Domínio:** `F01-go-auth-onboarding`
- **Versão:** 1.0.0
- **Consumidores:** Frontend Flutter (Mobile & Web)

## 2. Endpoints HTTP

### 2.1 `POST /api/v1/auth/register-clinic`
- **Payload de Entrada:**
  ```json
  {
    "clinic_name": "Clínica Odonto Solo",
    "clinic_email": "contato@odontosolo.com.br",
    "admin_name": "Dr. João Silva",
    "admin_email": "joao@odontosolo.com.br",
    "password": "senhaSegura123!"
  }
  ```
- **Resposta Sucesso (201 Created):**
  ```json
  {
    "token": "jwt_token_string",
    "user": {
      "id": "uuid",
      "name": "Dr. João Silva",
      "email": "joao@odontosolo.com.br",
      "role": "admin"
    },
    "clinic": {
      "id": "uuid",
      "name": "Clínica Odonto Solo",
      "trial_ends_at": "2026-08-14T12:00:00Z",
      "status": "active"
    }
  }
  ```

### 2.2 `POST /api/v1/auth/login`
- **Payload de Entrada:**
  ```json
  {
    "email": "joao@odontosolo.com.br",
    "password": "senhaSegura123!"
  }
  ```
- **Resposta Sucesso (200 OK):** Mesma estrutura do retorno de cadastro (`token`, `user`, `clinic`).
