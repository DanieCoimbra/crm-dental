# Contrato de Repositório Client-Side: Auth & Interceptor HTTP

- **Domínio:** `F01-front-auth-profile`
- **Pacote:** `package:frontend_flutter/features/auth`

---

## 1. Interface do Repositório (`AuthRepository`)

```dart
abstract class IAuthRepository {
  /// Registra uma nova clínica e retorna o token JWT e dados do usuário
  Future<Map<String, dynamic>> registerClinic({
    required String clinicName,
    required String clinicEmail,
    required String adminName,
    required String adminEmail,
    required String password,
  });

  /// Autentica usuário existente
  Future<Map<String, dynamic>> login({
    required String email,
    required String password,
  });

  /// Obtém perfil completo do usuário e clínica
  Future<Map<String, dynamic>> getProfile();
}
```

---

## 2. Mock de Resposta para Testes Unitários

```json
{
  "token": "mock_jwt_token_12345",
  "user": {
    "id": "1",
    "clinic_id": "1",
    "name": "Dr. João Silva",
    "email": "joao@odontosolo.com.br",
    "role": "admin"
  },
  "clinic": {
    "id": "1",
    "name": "Clínica Odonto Solo",
    "trial_ends_at": "2026-08-14T12:00:00Z",
    "status": "active"
  }
}
```
