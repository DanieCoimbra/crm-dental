# Technical Specification: Authentication & Login

## 1. Technical Overview
- **Feature**: F02 - Authentication & Login
- **Tech Stack Used**: Go (Fiber, golang-jwt), Flutter (Riverpod, flutter_secure_storage).
- **Architecture Approach**: Stateful Riverpod AuthNotifier holding JWT and User data. Fiber handler verifying Bcrypt and issuing JWT.

## 2. Data Models & Schema
- **Database Changes**: Modifies `failed_attempts` and `locked_until` on `users` table.
- **State Management**:
  - `AuthNotifier` (Riverpod) -> `AuthState` (initial, loading, authenticated(user, token), unauthenticated).

## 3. Component Architecture
- `LoginScreen`: 
  - **Props**: None
  - **Responsibility**: Render login form, trigger `AuthNotifier.login()`.
  - **State**: FormState, email/pass controllers.

## 4. Core Logic & Algorithms
- **Operation**: Login
  - Step 1: Find user by email.
  - Step 2: Check `locked_until`. If > now, return 429.
  - Step 3: Compare bcrypt password.
  - Step 4: On error, increment `failed_attempts`. If 5, set `locked_until = now + 15m`.
  - Step 5: On success, reset attempts. Generate JWT with `user_id`, `clinic_id`, `role`.
  
## 5. Error Handling & Edge Cases
- **Scenario 1**: 5 wrong passwords.
  - **Handling**: Return 429. Show toast with lock time.
- **Scenario 2**: Invalid email/password.
  - **Handling**: Return 401. Generic "Credenciais inválidas" message.

## 6. Security & Performance
- **Security Check**: Rate limiting at DB level. JWT Secret in .env.
- **Performance Targets**: Bcrypt comparison < 200ms.
