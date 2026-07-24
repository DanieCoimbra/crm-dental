# Feature Technical Specification (Spec)

## 1. Technical Overview
- **Feature**: CRM Clínico Fullstack MVP + Lote 7 (Gestão, EMR, Estoque, Financeiro, Dashboard, Cupons, WhatsApp, Supabase Storage, Tema Escuro)
- **Tech Stack Used**: 
  - **Backend**: Go 1.26, Fiber v2, PostgreSQL (GORM), Supabase Storage (via aws-sdk-go-v2), Stripe-go.
  - **Frontend**: Flutter (Dart ^3.12.2), Riverpod (State Management), go_router, Dio, `pdf` / `printing` para recibos, `flutter_stripe` para Pagamentos Nativos.
- **Architecture Approach**: 
  - Separação rígida de responsabilidades: O backend atua puramente como API RESTful com arquitetura limpa (Core -> Usecases -> Adapters -> Database). 
  - O Frontend utiliza padrão SPA Client-side orientado a features (`lib/features/`), consumindo a API com injeção de dependência via Riverpod.
  - Arquitetura B2B (Multi-tenant) baseia-se em escopos globais no banco de dados.

---

## 2. Data Models & Schema

### Database Changes (PostgreSQL / GORM)
Todas as tabelas operacionais da clínica DEVEM possuir a coluna `clinic_id` (BigInt) para isolamento.

- **`subscriptions` e `transactions` (SaaS Billing)**:
  - `clinic_id: BIGINT NOT NULL`
  - `status: VARCHAR(20)` ('trialing', 'active', 'past_due', 'canceled')
  - `external_subscription_id: VARCHAR(255)`
- **`affiliates` e `promo_codes` (Afiliados / F08)**:
  - `affiliates`: `id`, `name`, `email`, `commission_percent: DECIMAL`, `balance_cents: BIGINT`
  - `promo_codes`: `id`, `code: VARCHAR(50) UNIQUE`, `discount_percent: DECIMAL`, `affiliate_id: BIGINT`
- **`clinic_transactions` e `clinic_installments` (Módulo Financeiro)**:
  - `amount_cents: BIGINT` (Lidando com finanças estritamente em centavos inteiros).
  - `type: VARCHAR(15)` ('income', 'expense')
- **`products` e `stock_movements` (Estoque)**:
  - `current_stock: DECIMAL(10,3)` (Permite decimais para ml/gramas).
  - `type: VARCHAR(15)` ('in', 'out', 'auto')
- **`app_settings` (Configurações da Clínica / F11)**:
  - `clinic_id: BIGINT`
  - `whatsapp_api_key: VARCHAR(255)`
  - `whatsapp_instance: VARCHAR(255)`

### State Management (Flutter / Riverpod)
- **`AuthNotifier`**: Mantém o Token JWT e o Status de Acesso do Tenant.
- **`ThemeProvider` (F10)**: StateNotifier que escuta as `SharedPreferences` para alternar entre `ThemeMode.light` e `ThemeMode.dark`.
- **`DashboardNotifier` (F07)**: Gerencia o Future do carregamento das estatísticas (Receita, Faltas, Total de Consultas).
- **`InventoryAlertProvider`**: Expõe lista de produtos que disparam os badges vermelhos na UI.

---

## 3. Component Architecture (UI)

- **`TenantGuardOverlay`**:
  - **Props**: `child` (Widget)
  - **Responsibility**: Cobre a tela forçando o redirecionamento para pagamento caso a assinatura esteja inadimplente.
- **`DashboardScreen` (F07)**:
  - **Responsibility**: Renderiza os 4 KPI Cards superiores e o `fl_chart` (Gráfico de linha/barra) com o faturamento mensal.
- **`ThemeToggleButton` (F10)**:
  - **Responsibility**: Botão no `Topbar` global que alterna o tema claro/escuro.
- **`ReceiptPDFGenerator`**:
  - **Responsibility**: Classe de UI ausente de tela. Busca dados, desenha o PDF em Canvas e dispara a janela de impressão nativa (`printing.layoutPdf()`).

---

## 4. Core Logic & Algorithms

- **Operation: Dashboard Aggregation (F07)**
  - **Step 1**: Endpoint GET `/api/v1/dashboard/stats`.
  - **Step 2**: Backend executa `SELECT SUM(amount_cents) ... GROUP BY EXTRACT(MONTH FROM date)`.
  - **Step 3**: Extrai a contagem de agendamentos com `status = 'canceled'`. Retorna um JSON consolidado.
- **Operation: Disparo de WhatsApp (Cron Job / F11)**
  - **Step 1**: Goroutine com `time.Ticker` ou biblioteca de Cron escaneia às 08:00am.
  - **Step 2**: Busca agendamentos de amanhã (`date = CURDATE() + 1`).
  - **Step 3**: Faz JOIN com `app_settings` para pegar a `whatsapp_api_key` da clínica.
  - **Step 4**: Envia POST assíncrono para a API externa (Evolution/Z-API).
- **Operation: Atribuição de Comissão (F08)**
  - **Step 1**: O webhook da Stripe recebe `invoice.payment_succeeded`.
  - **Step 2**: Verifica se a `subscription` da clínica tem um `promo_code_id` atrelado.
  - **Step 3**: Se sim, busca o Afiliado, calcula `amount_cents * commission_percent` e soma ao `balance_cents` dele com transação segura (`db.Transaction`).
- **Operation: Upload Supabase Storage (F09)**
  - **Step 1**: O Frontend envia Multipart/Form-Data para `POST /api/v1/patients/files`.
  - **Step 2**: O Handler em Go lê o buffer, cria um arquivo único (UUID) e faz PutObject no Supabase Storage via `aws-sdk-go-v2`.
  - **Step 3**: Grava apenas o `path` ou `public_url` da chave no banco PostgreSQL.

- **Operation: Stripe Checkout e PIX (F12)**
  - **Step 1**: Frontend requisita a criação de um PaymentIntent no Backend Go informando valor e plano.
  - **Step 2**: Go chama a API da Stripe criando um `PaymentIntent` e retorna o `client_secret` para o Frontend.
  - **Step 3 (Cartão)**: Frontend (via `flutter_stripe`) apresenta CardField, coleta os dados com segurança PCI-compliance e confirma o pagamento diretamente com a Stripe usando o `client_secret`.
  - **Step 3 (PIX)**: Frontend recebe do Go os detalhes do PIX gerado pela Stripe (QR Code String), desenha na tela (ex: `qr_flutter`) e inicia um polling via endpoint próprio do Go ou aguarda um WebSocket alertar que o Webhook já aprovou a fatura.

---

## 5. Error Handling & Edge Cases

- **Scenario 1: Instabilidade no WhatsApp API**
  - **Handling**: Em caso de timeout/falha HTTP na API de WhatsApp, o log deve ser gravado e um job de Retry tenta no máximo mais 3 vezes usando backoff exponencial.
- **Scenario 2: Clínicas com Inadimplência ou Fim de Trial**
  - **Handling**: Fiber Middleware bloqueia rotas de mutação `POST/PUT/DELETE` com HTTP `402 Payment Required`. O Frontend (Interceptor Dio) capta o erro globalmente.
- **Scenario 3: Competição na Baixa de Estoque**
  - **Handling**: Consultas do GORM para abatimento usarão atualizações relativas: `UPDATE products SET current_stock = current_stock - 1 WHERE id = ?`.

---

## 6. Security & Performance

- **Security Check**: 
  - Token JWT será gerido via `flutter_secure_storage`.
  - Webhooks de Pagamento validam rigorosamente a assinatura HMAC nativa do Gateway.
  - Arquivos médicos no Supabase Storage devem estar em bucket PRIVADO, sendo servidos apenas mediante presigned URLs de expiração curta ou através de um Proxy do Go.
- **Performance Targets**: 
  - Consultas de Dashboard (F07) não devem estourar a memória. É exigida a criação de Índices B-Tree compostos (`clinic_id`, `status`) na tabela `appointments` e `clinic_transactions`.
  - A API Go deverá retornar as listagens rotineiras em menos de 100ms.
