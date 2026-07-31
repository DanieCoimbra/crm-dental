# Contrato de API: Profile & Tenant Status

## 1. Resumo do Contrato
- **Domínio:** `F03-go-profile-tenant`
- **Versão:** 1.0.0
- **Consumidores:** Frontend Flutter

## 2. Endpoints HTTP

### `GET /api/v1/profile`
- **Headers Exigidos:** `Authorization: Bearer <jwt_token>`
- **Resposta Sucesso (200 OK):**
  ```json
  {
    "user": {
      "id": "uuid",
      "name": "Dr. João Silva",
      "email": "joao@odontosolo.com.br",
      "role": "admin",
      "avatar_url": null,
      "is_active": true
    },
    "clinic": {
      "id": "uuid",
      "name": "Clínica Odonto Solo",
      "cnpj_cpf": "12.345.678/0001-90",
      "phone": "(11) 99999-9999",
      "email": "contato@odontosolo.com.br",
      "trial_ends_at": "2026-08-14T12:00:00Z",
      "status": "active"
    }
  }
  ```
