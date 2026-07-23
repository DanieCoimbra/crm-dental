# Documentação Completa da Arquitetura (CRM Clínico)

Este documento centraliza a inteligência arquitetural, padrões de código e mapeamento de funcionalidades do sistema CRM Clínico, servindo como mapa para desenvolvimento e manutenção futura.

---

## 1. Visão Geral e Tech Stack

O software foi projetado como um **SaaS Multi-tenant**, preparado para abrigar múltiplas clínicas (`clinics`) rodando de forma isolada no mesmo banco de dados. 

- **Backend:** Go 1.26 operando estritamente como API RESTful.
- **Framework Web:** Fiber (v2) de alta performance.
- **Frontend:** Flutter com Dart (Web e Mobile), utilizando Clean Architecture para a organização das features.
- **Gerenciamento de Estado (Front):** Riverpod para reatividade e injeção de dependências global.
- **Banco de Dados & Storage:** Supabase (PostgreSQL) com GORM como ORM relacional no Backend.
- **Autenticação:** JWT (golang-jwt) integrado ao Fiber via middlewares.

---

## 2. Mapa de Funcionalidades (Features e Código)

### F01: Core Multi-tenant e Autenticação
- **Como funciona:** Toda a arquitetura restringe as requisições para a clínica (`clinic_id`) do usuário logado. Utiliza o `auth_middleware` no Fiber para decodificar o token JWT e injetar o escopo local no `fiber.Ctx`.
- **Backend:** `internal/middleware/auth_middleware.go` e `internal/core/services/auth_service.go`
- **Frontend:** `lib/features/auth/providers/auth_provider.dart`

### F02: Prontuário Eletrônico (EMR)
- **Como funciona:** Gestão de pacientes e seus registros clínicos completos. Inclui Evoluções, Arquivos (Uploads/Imagens) e gestão de histórico.
- **Backend:** `PatientController/Handler`, `ClinicalEvolutionHandler`, `PatientFileHandler`.
- **Frontend:** `lib/features/patients/presentation/patient_emr_screen.dart` (UI robusta dividida em abas).

### F03: Agenda Inteligente (Smart Booking e Waitlist)
- **Como funciona:** Sistema de calendário avançado. Registra eventos, status da consulta e incorpora um timer automático ("Iniciar" e "Finalizar" consultas). Trabalha atrelado a uma fila de espera (Waitlist) que cruza vagas.
- **Backend:** `AppointmentHandler.go`, `AppointmentTypeHandler.go`.
- **Frontend:** `lib/features/schedule/presentation/schedule_screen.dart` acoplado ao `syncfusion_flutter_calendar`.

### F04: Módulo Financeiro
- **Como funciona:** Transações de receita/despesa, recebimentos de pacientes (parcelas), fluxo de caixa, tudo vinculado à clínica. Trabalha com `int64` (centavos) para evitar problemas de arredondamento de float.
- **Backend:** `internal/core/domain/financial.go`, `internal/adapters/handlers/financial_handler.go`.
- **Frontend:** `lib/features/financial/presentation/financial_dashboard_screen.dart`.

### F05: Controle de Estoque Interligado
- **Como funciona:** Produtos, Entradas/Saídas (Transactions), Alerta de Baixo Estoque. O estoque é interligado à Agenda (Smart Agenda): cada procedimento pode consumir N materiais automaticamente ao finalizar a consulta via rotinas assíncronas no Go (Goroutines).
- **Backend:** `InventoryService.ProcessAppointmentMaterials` e repositórios.
- **Frontend:** `InventoryScreen` e Badge de alerta no Topbar (`lib/shared/widgets/topbar.dart`).

### F06: SaaS Billing (Controle de Assinaturas)
- **Como funciona:** Controle dos planos da clínica (Trial, Pro, Premium). Um `TenantGuardOverlay` no Frontend força uma barreira UI caso o plano esteja inadimplente. O Backend intercepta endpoints mutáveis (POST, PUT, DELETE) com HTTP 402, e webhooks interligados com a Stripe destravam tudo automaticamente.
- **Backend:** `internal/middleware/saas_middleware.go`, `webhook_handler.go`.
- **Frontend:** `TenantGuardOverlay` e `SaasCheckoutScreen`.

### F07: Dashboard Gerencial
- **Como funciona:** Fornece métricas consolidadas (receitas, ticket médio, total de consultas, taxa de faltas). Protegido por restrições de nível Administrativo (AdminOnly).
- **Backend:** `internal/adapters/handlers/dashboard_handler.go` (com cache leve).
- **Frontend:** `lib/features/dashboard/presentation/dashboard_screen.dart`.

### F08: Sistema de Cupons e Afiliados (SaaS)
- **Como funciona:** Clínicas inserem códigos promocionais no momento da assinatura. O Webhook de sucesso de pagamento do Stripe avalia a Assinatura (Subscription), extrai o desconto, e credita de forma automática e atômica o saldo (Balance) na conta do Afiliado.
- **Backend:** `webhook_handler.go` (Logics no evento `invoice.payment_succeeded`), `saas_billing.go`.
- **Frontend:** `PromoCodeInput` dinâmico em `SaasCheckoutScreen`.

### F09: Armazenamento em Nuvem (Supabase Storage)
- **Como funciona:** Substituição de arquivos salvos em disco (I/O). Utiliza um stream puro (`io.Reader` via `net/http`) que roteia buffers nativos do Frontend pro Supabase S3 sem usar memória RAM. Limite no framework expandido para 50MB.
- **Backend:** `patient_file_handler.go`
- **Setup:** Requer credenciais S3 setadas no `.env` do servidor (Dashboard Supabase).

### F10: Tema Global e Modo Escuro
- **Como funciona:** Alternância de temas visuais integrada com Riverpod (`themeProvider`), sincronização bidirecional na nuvem (`PUT /users/preferences`), com cache local Offline-First (`Hive`).
- **Frontend:** `theme_provider.dart`, `ThemeToggleButton` e UI `topbar.dart`.

---

## 3. Arquitetura do Backend (Conceitos Core)

O backend segue o padrão **Clean Architecture / Layered**:
- `cmd/server/main.go`: Inicializa a aplicação Fiber, injeta dependências.
- `internal/core/domain`: Entidades puras e modelos GORM.
- `internal/core/services`: Onde reside a lógica de negócios complexa (ex: `InventoryService`).
- `internal/adapters/handlers`: Camada HTTP (Controllers). Valida DTOs e entrega a resposta final em JSON.
- `internal/database`: Comunicação direta e Repositórios de DB.

## 4. Arquitetura do Frontend (Flutter / Clean)

- A aplicação usa **go_router** para um roteamento escalável na Web e Mobile.
- Estrutura baseada no modelo "Feature-first": Cada pacote dentro de `lib/features/` possui a própria divisão `presentation`, `domain`, `data`, `providers`.
- O Estado Global e Injeção de Dependências são feitos pelo **Riverpod** (`ConsumerWidget`, `ref.watch`).
- Os temas e cores são isolados (`lib/core/theme`), permitindo escalabilidade para Dark Mode.

---

## 5. Guia Prático de Manutenção

Para adicionar uma nova funcionalidade, siga este checklist:

1. **Alterar Banco (GORM):** 
   - Modifique a Struct no `internal/core/domain` e adicione a Struct em `database.go` (`AutoMigrate`).
2. **Atualizar Backend:**
   - Crie as Rotas em `internal/adapters/routes/routes.go`.
   - Crie o Repositório e o Service, e por fim injete-os num Handler.
3. **Atualizar Frontend (Flutter):**
   - Crie os Data Models e Providers em `lib/features/sua_feature`.
   - Nunca modifique UI sem componentizar adequadamente.
   - Use os widgets padrão globais (do `lib/shared/widgets`) para consistência.
