# PRD Mobile: Atualização & Alinhamento do Frontend Flutter

- **Projeto:** CRM Clínica Odontológica (Solo / SaaS Multi-tenant)
- **Autor:** Arquiteto de Software Flutter
- **Status:** Em Revisão (Aguardando Aprovação)
- **Data:** 31/07/2026

---

## 1. Visão Geral & Objetivos Estratégicos

### 1.1 Problema
O aplicativo cliente Flutter possuía inconsistências e chamadas a componentes e rotas legadas, além de desalinhamentos com os contratos de API recém-padronizados do backend Go (`dental-crm-api`). Isso gerava falhas de compilação, importações para arquivos inexistentes e respostas de erro HTTP 401/403 sem tratamento adequado na camada de apresentação e gerenciamento de estado.

### 1.2 Solução Proposta
Adequar a arquitetura e os componentes do aplicativo Flutter para consumir os novos contratos de API (`POST /api/v1/auth/register-clinic`, `GET /api/v1/profile`), tratar respostas de erro RBAC/Auth padronizadas (`UNAUTHORIZED` e `FORBIDDEN`), e restaurar modais interativas com gerenciamento de estado robusto (Riverpod 3.x) seguindo os princípios de Clean Architecture no client-side.

### 1.3 Objetivos e Métricas de Sucesso (KPIs)
1. **0 Erros de Análise/Compilação:** Garantir 100% de conformidade com `flutter analyze` sem erros de importação ou invocação de membros indefinidos.
2. **Tempo de Resposta de Interface < 100ms:** Transições suaves entre rotas guardadas pelo GoRouter baseadas no papel (`admin` vs `receptionist`).
3. **Tratamento de Exceções 100% Coberto:** Interceptação transparente de HTTP 401/403 e exibição de alertas visuais padronizados ao usuário.

---

## 2. Historias de Usuário (User Stories)

### US-001: Autenticação & Onboarding de Clínica (F01)
**Descrição:** Como dentista proprietário, quero registrar minha clínica e realizar login no aplicativo mobile/web para acessar a gestão da minha clínica com trial ativo.

**Critérios de Aceite:**
- [ ] O formulário de registro deve consumir `POST /api/v1/auth/register-clinic` enviando `clinic_name`, `clinic_email`, `admin_name`, `admin_email` e `password`.
- [ ] O formulário de login deve consumir `POST /api/v1/auth/login`.
- [ ] Armazenar com segurança o JWT Token via `flutter_secure_storage`.
- [ ] Redirecionar automaticamente para `/dashboard` após login/registro bem-sucedido.

### US-002: Perfil do Usuário & Clínica (F03)
**Descrição:** Como usuário autenticado, quero visualizar meus dados de perfil e informações da minha clínica para manter as informações atualizadas.

**Critérios de Aceite:**
- [ ] Consumir `GET /api/v1/profile` enviando o token JWT no header `Authorization: Bearer <token>`.
- [ ] Exibir os dados do usuário (`name`, `email`, `role`, `avatar_url`) e da clínica (`name`, `cnpj_cpf`, `trial_ends_at`, `status`).

### US-003: Modais Interativas de Operações (Cadastros & Lançamentos)
**Descrição:** Como usuário da clínica, quero abrir diálogos limpos e responsivos para cadastrar pacientes, transações financeiras, agendamentos e itens de estoque.

**Critérios de Aceite:**
- [ ] Implementar a modal `InventoryFormDialog` e `InventoryTransactionDialog` para movimentações de estoque.
- [ ] Implementar a modal `TransactionFormDialog` para lançamentos no financeiro.
- [ ] Implementar a modal `PatientFormDialog` para cadastrar novos pacientes.
- [ ] Implementar a modal `AppointmentFormDialog` para criar novos agendamentos na agenda.

### US-004: Tratamento Unificado de Erros HTTP & RBAC (F02)
**Descrição:** Como usuário do sistema, quero receber feedback claro ao tentar acessar recursos para os quais não possuo permissão (ex: recepcionista tentando acessar financeiro).

**Critérios de Aceite:**
- [ ] Interceptador Dio mapeando a chave `"error": "UNAUTHORIZED"` para deslogar o usuário e redirecionar para `/login`.
- [ ] Interceptador Dio mapeando a chave `"error": "FORBIDDEN"` exibindo um `SnackBar` ou alerta visual amigável sem derrubar a sessão.

---

## 3. Requisitos Funcionais (FR)

- **FR-1:** O app deve utilizar `GoRouter` como gerenciador de rotas com rotas guardadas via Riverpod `authProvider`.
- **FR-2:** O pacote `dio` deve utilizar um `AuthInterceptor` que anexa automaticamente o token JWT em todas as requisições privadas.
- **FR-3:** O app deve bloquear telas de financeiro, configurações de papéis e deleção de dados para o papel `receptionist`.
- **FR-4:** Todas as modais de formulário devem possuir validação nativa de campos obrigatórios antes do envio ao backend.

---

## 4. Requisitos Não-Funcionais (NFR)

- **NFR-1 (Clean Architecture):** Separação estrita em `data` (repositories/sources), `domain` (models) e `presentation` (screens/widgets/providers).
- **NFR-2 (Design System):** Uso rigoroso do tema Material 3 com suporte a modo escuro/claro e ícones do `lucide_icons_flutter`.
- **NFR-3 (Performance):** Renderização constante a 60/120fps sem travamentos em listas de pacientes e agendamentos.

---

## 5. Non-Goals (Fora de Escopo)

- Implementação de banco de dados offline completo (offline-first syncing) nesta fase.
- Alterações em schemas ou endpoints do backend Go.

---

## 6. Arquitetura Mapeada no Client-Side

```
lib/
├── core/
│   ├── network/       # Dio Client & AuthInterceptor (trata 401/403)
│   ├── router/        # GoRouter (Auth Guard & Role Guard)
│   └── theme/         # AppTheme (Material 3)
├── features/
│   ├── auth/          # Login, Register, Profile (F01 & F03)
│   ├── dashboard/     # Stats & Quick actions
│   ├── financial/     # Financial Dashboard & TransactionFormDialog
│   ├── inventory/     # InventoryScreen, Form & Transaction Dialogs
│   ├── patients/      # PatientsScreen, PatientEMR & PatientFormDialog
│   ├── schedule/      # ScheduleScreen & AppointmentFormDialog
│   ├── settings/      # SettingsScreen, Room & AppointmentType Dialogs
│   └── trash/         # TrashScreen (LGPD)
└── main.dart
```

---

## 7. Riscos & Questões Abertas

- **Risco 1:** Divergência no formato da data entre o backend (ISO 8601 UTC) e o exibido na interface. *Mitigação: Uso do pacote `intl` para formatação local (pt_BR).*
