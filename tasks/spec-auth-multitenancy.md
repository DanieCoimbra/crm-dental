# Technical Specification: Auth, Multitenancy & RBAC

## 1. Technical Overview
- **Feature**: Login, Cadastro, Controle de Acesso (RBAC) e Isolamento de Clínicas (Multitenancy).
- **Tech Stack Used**: 
  - **Backend**: Go 1.26, Fiber v2, GORM (PostgreSQL), `golang.org/x/crypto/bcrypt` para hash de senhas, `github.com/golang-jwt/jwt/v5` para JWT.
  - **Frontend**: Flutter (Dart), `flutter_riverpod`, `go_router`, `dio`, `flutter_secure_storage` para armazenar tokens.
  - **Database**: PostgreSQL hospedado no Supabase.
- **Architecture Approach**: 
  - **Backend**: Clean Architecture (`internal/core` para regras de negócio e interfaces, `internal/adapters` para handlers REST, `internal/database` para os repositórios do GORM). O Multitenancy será garantido via um Middleware no Fiber que extrai o `clinic_id` do JWT e injeta no `c.Locals()`.
  - **Frontend**: Feature-first (`lib/features/auth`). Riverpod gerenciará o estado global da sessão. Interceptors do Dio adicionarão o header de Autorização nas requisições e redirecionarão ao login caso recebam HTTP 401/403.

---

## 2. Data Models & Schema

### Database Changes (PostgreSQL / GORM)

**Enum: `user_role`**
```sql
CREATE TYPE user_role AS ENUM ('OWNER', 'ADMIN', 'DENTIST', 'RECEPTIONIST');
```

**Table: `clinics`**
- `id`: UUID (Primary Key, gerado via `gen_random_uuid()`)
- `name`: VARCHAR(255) NOT NULL
- `cnpj`: VARCHAR(20) UNIQUE NOT NULL
- `created_at`: TIMESTAMPTZ DEFAULT NOW()

**Table: `users`**
- `id`: UUID (Primary Key, gerado via `gen_random_uuid()`)
- `clinic_id`: UUID (Foreign Key referenciando `clinics(id)`, ON DELETE CASCADE)
- `name`: VARCHAR(255) NOT NULL
- `email`: VARCHAR(255) UNIQUE NOT NULL
- `password_hash`: VARCHAR(255) NOT NULL
- `role`: `user_role` NOT NULL DEFAULT 'RECEPTIONIST'
- `failed_attempts`: INT DEFAULT 0
- `locked_until`: TIMESTAMPTZ NULL
- `created_at`: TIMESTAMPTZ DEFAULT NOW()

### State Management (Flutter / Riverpod)
- **`AuthNotifier` (StateNotifier / AsyncNotifier)**: Mantém o estado atual da autenticação (`initial`, `loading`, `authenticated`, `unauthenticated`).
- **`AuthState`**: Conterá o objeto do Usuário Logado (com sua Role) e o ID da Clínica, além do token JWT para uso imediato em UI.

---

## 3. Component Architecture (Frontend - Flutter)

### Feature: `auth` (`lib/features/auth/`)

- **`LoginScreen` (`presentation/login_screen.dart`)**
  - **Responsibility**: Renderiza o formulário de login. Possui TextFields de email e senha.
  - **State**: `FormState` local para as validações inline. Observa o `AuthNotifier` para mostrar o status de "loading" no botão.
  - **Events**: Submete dados on-press do botão ou `onFieldSubmitted` (Enter).
  
- **`RegisterScreen` (`presentation/register_screen.dart`)**
  - **Responsibility**: Renderiza formulário de cadastro da clínica (Owner, Nome, CNPJ, Email, Senha).
  - **State**: `FormState` local para a validação inline.
  - **Events**: Redireciona via `context.go('/login')` em caso de sucesso.

- **`AuthDioInterceptor` (`infrastructure/auth_interceptor.dart`)**
  - **Responsibility**: Classe interceptora para anexar o header `Authorization: Bearer <token>` lido do Secure Storage. Caso uma request retorne 401, limpa o token e despacha um evento de logout global, mandando o usuário para o Login.

---

## 4. Core Logic & Algorithms

### Operação: Cadastro de Clínica (Owner)
1. Recebe Payload (Nome, CNPJ, OwnerName, Email, Senha).
2. Valida payload no handler do Fiber.
3. Inicia transação no GORM (`db.Begin()`).
4. Gera Hash Bcrypt da senha.
5. Insere a Clínica na tabela `clinics`. Retorna o ID gerado.
6. Insere o Owner na tabela `users` com o `clinic_id` atrelado, `role = 'OWNER'` e a senha em hash.
7. Comita a transação (`tx.Commit()`).

### Operação: Autenticação (Login) & Rate Limiting
1. Busca usuário pelo email. Se não existir, retorna 401.
2. Checa `locked_until`. Se for > AGORA, retorna `429 Too Many Requests` com a data do desbloqueio.
3. Compara bcrypt hash.
   - **Se incorreta**: Incrementa `failed_attempts` no DB. Se `failed_attempts` chegar a 5, seta `locked_until = NOW() + 15 minutos`. Retorna 401.
   - **Se correta**: Zera `failed_attempts` e anula `locked_until`.
4. Gera token JWT assinando os claims: `user_id`, `clinic_id`, `role`, `exp` (expiração).
5. Retorna token no body da resposta HTTP.

### Operação: Isolamento Multitenant (Backend Middleware)
1. Middleware `RequireAuth` lê o cabeçalho HTTP `Authorization`.
2. Faz o parse e verifica a assinatura do JWT.
3. Se válido, extrai `clinic_id` e `role` dos claims e salva em `c.Locals("clinic_id")`.
4. Em qualquer Repositório GORM protegido, os métodos vão exigir `clinicId` como parâmetro, forçando o `db.Where("clinic_id = ?", clinicId)` na query SQL de forma estrita.

---

## 5. Error Handling & Edge Cases

- **Scenario 1**: Conta bloqueada por excesso de tentativas (Rate Limiting).
  - **Handling**: Backend retorna `429 Too Many Requests`. Frontend exibe um Toast: "Sua conta está temporariamente bloqueada. Tente novamente em X minutos".
- **Scenario 2**: Tentativa de acesso de dados de outra clínica.
  - **Handling**: Backend usa a cláusula obrigatória do tenant `clinic_id = ?` vinda do token. Uma tentativa maliciosa de acessar o `paciente 55` que pertence a outra clínica simplesmente retornará `404 Not Found`, pois o escopo do WHERE não baterá.
- **Scenario 3**: Token JWT expira no meio do uso.
  - **Handling**: Backend retorna `401 Unauthorized`. `AuthDioInterceptor` no Flutter intercepta, apaga os dados locais, exibe "Sessão Expirada" no Toast e chama `go_router` para `/login`.

---

## 6. Security & Performance
- **Security Check**:
  - Hash de senhas deve usar `bcrypt` com custo mínimo de 10.
  - O secret do JWT (`JWT_SECRET`) nunca deve ser "hardcoded", sendo carregado obrigatoriamente do arquivo `.env`.
  - Rate limiting direto no DB previne ataques de dicionário lentos ou rápidos nas contas locais.
- **Performance Targets**:
  - A consulta de login deve ser rápida: Criação de Index na tabela `users` coluna `email` (`CREATE UNIQUE INDEX idx_users_email ON users(email);`).
  - O cadastro de clínica envolve escrita relacional, a transação GORM deve completar em < 100ms.
