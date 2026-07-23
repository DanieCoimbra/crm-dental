# Feature Technical Specification: F01-Multi-tenant Core

## 1. Technical Overview
- **Feature**: F01 - Multi-tenant Core (Autenticação e Isolamento B2B)
- **Tech Stack Used**: 
  - **Backend**: Go 1.26, Fiber v2, `golang-jwt/jwt/v5`, GORM (PostgreSQL), `golang.org/x/crypto/bcrypt`.
  - **Frontend**: Flutter (Dart ^3.12.2), Riverpod (State Management), `flutter_secure_storage`, Dio (Client HTTP).
- **Architecture Approach**: 
  - Segurança Baseada em Contexto: Extração do tenant via JWT em nível de Middleware Web e injeção em Scopes da camada de Banco de Dados.
  - Gerenciamento Global Reativo: O estado de autenticação guia todo o roteamento seguro da SPA no Flutter.

---

## 2. Data Models & Schema

### Database Changes
Estruturas iniciais requeridas no PostgreSQL via GORM AutoMigrate:

- **Tabela `clinics` (Tenants)**:
  - `id`: BIGSERIAL PRIMARY KEY
  - `name`: VARCHAR(255) NOT NULL
  - `cnpj`: VARCHAR(20) UNIQUE
  - `created_at`, `updated_at`: TIMESTAMPTZ

- **Tabela `users` (Funcionários/Médicos)**:
  - `id`: BIGSERIAL PRIMARY KEY
  - `clinic_id`: BIGINT NOT NULL (Foreign Key -> clinics.id)
  - `name`: VARCHAR(255) NOT NULL
  - `email`: VARCHAR(255) UNIQUE NOT NULL (Indexado)
  - `password_hash`: VARCHAR(255) NOT NULL
  - `role`: VARCHAR(50) DEFAULT 'receptionist' (ex: 'admin', 'doctor', 'receptionist')

### State Management (Flutter)
- **`AuthState`**: Classe imutável (freezed/equatable) para gerenciar o estado da sessão:
  - `status`: Enum (`initial`, `authenticated`, `unauthenticated`)
  - `token`: String? (O JWT)
  - `clinicId`: int?
- **`AuthNotifier`**: StateNotifier no Riverpod que orquestra login, logout e persistência do token.

---

## 3. Component Architecture (UI)

- **`LoginScreen`**:
  - **Responsibility**: Renderizar o formulário inicial de acesso, aplicar validação de campos (email regex, min-length) e disparar a submissão via AuthNotifier.
  - **State**: `emailText`, `passwordText`, `isLoading` (bool), `isPasswordVisible` (bool - para o recurso de 'olho' (👁️) nas senhas).
- **`AuthInterceptor` (Core System)**:
  - **Responsibility**: Extensão da classe `Interceptor` do pacote Dio. 
  - **Função**: Intercepta o pipeline de requisições (`onRequest`). Lê o token do cache em memória e anexa o cabeçalho `Authorization: Bearer <token>`.

---

## 4. Core Logic & Algorithms

### Operation: Autenticação e Emissão de JWT
- **Step 1**: Frontend submete credenciais via POST para `/api/v1/auth/login`.
- **Step 2**: Backend busca usuário pelo email usando GORM. Se não achar, aborta.
- **Step 3**: Compara a senha crua com `password_hash` usando `bcrypt.CompareHashAndPassword`.
- **Step 4**: Gera o token JWT via `golang-jwt`. O Payload (`Claims`) contém:
  - `sub`: `user.id`
  - `clinic_id`: `user.clinic_id`
  - `role`: `user.role`
  - `exp`: `time.Now().Add(time.Hour * 24).Unix()`
- **Step 5**: Assina o token com o `JWT_SECRET` e retorna ao cliente. Frontend grava no `flutter_secure_storage`.

### Operation: Isolamento de Dados (Tenant Scopes)
- **Step 1**: Requisições chegam às rotas protegidas e atingem o `TenantMiddleware` do Fiber.
- **Step 2**: O middleware valida a assinatura do JWT. Se válido, lê o claim `clinic_id`.
- **Step 3**: Injeta o ID no contexto de requisição: `c.Locals("clinic_id", claims["clinic_id"])`.
- **Step 4**: O controller resgata o ID e passa ao Repository. O Repository inicia todas as queries usando a função auxiliadora:
  ```go
  func WithTenant(clinicID uint) func(db *gorm.DB) *gorm.DB {
      return func(db *gorm.DB) *gorm.DB {
          return db.Where("clinic_id = ?", clinicID)
      }
  }
  ```
- **Step 5**: GORM compila dinamicamente a query: `SELECT * FROM patients WHERE clinic_id = 15 AND ...` garantindo que o tenant nunca vaze para outros registros.

---

## 5. Error Handling & Edge Cases

- **Scenario 1**: Token Expirado ou Inválido (Modificação de Assinatura)
  - **Handling**: A API Go retornará HTTP `401 Unauthorized`. O `AuthInterceptor` no Flutter capturará o erro no método `onError`, atualizará o `AuthNotifier` para `unauthenticated`, forçando o roteador (`go_router`) a expulsar o usuário para a `LoginScreen` (limpando o Secure Storage).
- **Scenario 2**: Requisição em Tabela sem suporte a Tenant
  - **Handling**: Se o desenvolvedor tentar aplicar `Scopes(WithTenant(1))` em uma tabela de metadados genérica sem a coluna `clinic_id`, o PostgreSQL gerará erro sintático. Isso é esperado (Fail Fast) para apontar erros de modelagem de domínio no ambiente de desenvolvimento.

---

## 6. Security & Performance

- **Security Check**:
  - As senhas jamais trafegam ou são expostas em logs (Loggers middleware devem ofuscar campos `password`).
  - `bcrypt` configurado com `DefaultCost` (geralmente 10) para balancear segurança contra ataques de força bruta x tempo de CPU de login.
  - O arquivo `.env` detém o `JWT_SECRET`, e não será comitado no versionamento (`.gitignore`).
- **Performance Targets**:
  - O overhead de decodificação do JWT e injeção no middleware do Fiber não deve ultrapassar **2ms**. A arquitetura sem sessões estaduais (Stateless API) garante consumo mínimo de RAM no Backend.
