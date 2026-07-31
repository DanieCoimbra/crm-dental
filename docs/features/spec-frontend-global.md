# Especificação Técnica Global: Frontend Flutter (Mobile & Web)

- **Projeto:** CRM Clínica Odontológica (Solo / SaaS Multi-tenant)
- **Documento Relacionado:** `docs/features/prd-frontend-updates.md`
- **Autor:** Arquiteto de Software Flutter
- **Data:** 31/07/2026

---

## 1. Visão Geral da Arquitetura Client-Side

O aplicativo Flutter adota os princípios de **Clean Architecture** combinados com o gerenciamento de estado moderno via **Riverpod 3.x** e roteamento declarativo com **GoRouter**.

### Camadas de Código:
1. **Presentation Layer (`lib/features/<feature>/presentation`)**:
   - `screens/`: Telas principais da aplicação.
   - `widgets/`: Componentes visuais e modais interativas.
   - `providers/`: StateNotifiers, AsyncNotifiers ou FutureProviders que gerenciam o estado da UI.
2. **Domain Layer (`lib/features/<feature>/domain` ou `data/*_model.dart`)**:
   - Mapeamento de objetos de domínio e entidades serializáveis via `factory .fromJson()`.
3. **Data Layer (`lib/features/<feature>/data`)**:
   - `repositories/`: Classes de repositório que utilizam o `ApiClient` (Dio) para comunicação com o backend Go.

---

## 2. Roteamento & Guardas de Acesso (`AppRouter`)

Mapeamento centralizado no arquivo `lib/core/router/app_router.dart`:

```dart
// Matriz de Rotas Protegidas:
// /login & /register-clinic -> Rotas Públicas
// /dashboard -> Autenticado (Ambos)
// /patients -> Autenticado (Ambos, prontuário restrito no EMR)
// /schedule -> Autenticado (Ambos)
// /financial -> Autenticado (Apenas ADMIN/OWNER)
// /settings -> Autenticado (Apenas ADMIN/OWNER)
// /trash -> Autenticado (Apenas ADMIN/OWNER/MANAGER)
```

---

## 3. Comunicação de Rede & Interceptação de Erros (`ApiClient`)

O cliente HTTP Dio está configurado em `lib/core/network/api_client.dart` com um `AuthInterceptor` customizado:

- **Token Injection:** Adiciona `Authorization: Bearer <jwt_token>` em todas as requisições privadas.
- **Tratamento HTTP 401 (`UNAUTHORIZED`):**
  - Limpa os dados do `flutter_secure_storage`.
  - Invalida o `authProvider`.
  - Redireciona imediatamente para `/login`.
- **Tratamento HTTP 403 (`FORBIDDEN`):**
  - Captura o payload `{"error": "FORBIDDEN", "message": "..."}`.
  - Exibe um `SnackBar` vermelho com mensagem clara ao usuário informando ausência de permissão.

---

## 4. Especificação de Módulos & Modais Faltantes

### 4.1 Módulo `inventory`
- **Componentes:**
  - `InventoryFormDialog`: Formulário modal para criação e edição de itens do estoque (nome, categoria, quantidade, estoque mínimo).
  - `InventoryTransactionDialog`: Modal para registrar entradas e saídas de estoque.

### 4.2 Módulo `financial`
- **Componentes:**
  - `TransactionFormDialog`: Modal para cadastro de receitas e despesas (descrição, valor, categoria, vencimento, tipo).

### 4.3 Módulo `patients`
- **Componentes:**
  - `PatientFormDialog`: Modal para novo paciente (nome, cpf, telefone, email, data nascimento).
  - `PatientSummaryTab` & `PatientFilesTab`: Abas do prontuário eletrônico (`patient_emr_screen.dart`).

### 4.4 Módulo `schedule`
- **Componentes:**
  - `AppointmentFormDialog`: Modal para agendar consultas (paciente, sala, dentista, data/horário, tipo).
  - `WaitlistFormDialog`: Modal para lista de espera.
  - `ShiftAssignmentFormDialog`: Modal para alocação de turnos dos dentistas/funcionários.

---

## 5. Matriz de Integração de Endpoints (Backend Go)

| Tela / Modal | Endpoint Backend | Método HTTP | Roles Permite |
|---|---|---|---|
| Registro Clínica | `/api/v1/auth/register-clinic` | `POST` | Público |
| Login | `/api/v1/auth/login` | `POST` | Público |
| Perfil | `/api/v1/profile` | `GET` | Autenticado |
| Pacientes | `/api/v1/patients` | `GET`, `POST`, `PUT`, `DELETE` | `DELETE` apenas ADMIN |
| Agenda | `/api/v1/appointments` | `GET`, `POST`, `PUT`, `DELETE` | Autenticado |
| Financeiro | `/api/v1/financial/transactions` | `GET`, `POST`, `PUT` | ADMIN / OWNER |
| Estoque | `/api/v1/inventory` | `GET`, `POST`, `PUT`, `DELETE` | Autenticado |
| Lixeira | `/api/v1/trash` | `GET`, `POST`, `DELETE` | ADMIN / MANAGER / OWNER |
