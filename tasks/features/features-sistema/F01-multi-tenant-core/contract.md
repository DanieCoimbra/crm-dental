# Feature Contract: F01-Multi-tenant Core

## 1. Contract Summary
- **Feature**: F01-Multi-tenant Core
- **Version**: 1.0.0
- **Primary Consumer(s)**: Frontend Flutter (SPA Cliente), e Módulos Backend Subsequentes (F02 a F06) que dependem da injeção de contexto.

## 2. Inputs (What this feature requires)

### Autenticação HTTP (REST API)
- **Endpoint/Method**: `POST /api/v1/auth/login`
- **Headers Requeridos**: `Content-Type: application/json`
- **Payload Schema**:
  ```json
  {
    "email": "string (required, format: email)",
    "password": "string (required, minLength: 6)"
  }
  ```

### Proteção de Rotas (Middleware Request)
- **Endpoint/Method**: Qualque rota iniciada por `/api/v1/*` (exceto públicas).
- **Headers Requeridos**:
  ```http
  Authorization: Bearer <JWT_STRING>
  ```

## 3. Outputs (What this feature returns)

### Resposta de Sucesso de Autenticação
- **Success Response (HTTP 200 OK)**:
  ```json
  {
    "token": "string (JWT)",
    "user": {
      "id": "integer (BIGINT)",
      "clinic_id": "integer (BIGINT)",
      "name": "string",
      "email": "string",
      "role": "string (enum: admin, doctor, receptionist)"
    }
  }
  ```

### Padrão de Erros de Autenticação e Autorização
- **Expected Errors (HTTP 401 / 400)**:
  ```json
  {
    "error_code": "string (ex: INVALID_CREDENTIALS, MISSING_TOKEN, EXPIRED_TOKEN)",
    "message": "string (mensagem legível)"
  }
  ```

## 4. Business Rules & Limits
- **Rate Limits**: Máximo de 5 tentativas de login incorretas consecutivas por minuto por endereço IP para evitar ataques de força bruta.
- **Validation Rules**: 
  - O e-mail deve respeitar uma regex válida (formato padrão RFC 5322).
  - O cabeçalho `Authorization` deve conter o prefixo obrigatório `Bearer `.
- **State Prerequisites**: O registro atrelado ao `email` deve existir no banco de dados.
- **Data Encapsulation**: A senha crua NUNCA deve ser retornada em nenhuma resposta da API.

## 5. Events Emitted (Side Effects)

- **Event Name**: `Context.TenantInjected` (Emissão Interna no Go)
- **Trigger**: Emitido para cada requisição HTTP que passa pelo `TenantMiddleware` com um JWT válido.
- **Event Payload (Fiber Locals)**: 
  O middleware anexa as seguintes chaves no contexto da requisição (`c.Locals`):
  - `"clinic_id"`: `integer` (ID extraído da claim do JWT).
  - `"user_id"`: `integer` (ID do usuário solicitante).
  - `"role"`: `string` (Nível de permissão).
- **Consumo Esperado**: Todos os outros módulos do backend usarão `c.Locals("clinic_id").(uint)` para acionar o Helper GORM `WithTenant(id)`.

## 6. Acceptance Criteria (Integration)
- [ ] Consumer (Flutter) envia payload vazio ou email inválido -> Retorna 400 Bad Request com payload de erro mapeado.
- [ ] Consumer (Flutter) envia credenciais erradas repetidas vezes -> Após 5 tentativas, retorna 429 Too Many Requests (Rate limit).
- [ ] Consumer requisita uma rota autenticada com JWT expirado -> Retorna 401 Unauthorized (`error_code: EXPIRED_TOKEN`).
- [ ] Módulos internos (ex: Cadastro de Pacientes) buscam `clinic_id` do Locals no Fiber e recebem um valor numérico estritamente idêntico ao do JWT originador.
