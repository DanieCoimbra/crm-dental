# Feature Technical Specification (Spec)

## 1. Technical Overview
- **Feature**: F12 - Stripe Payments (Cartão e PIX)
- **Tech Stack Used**: 
  - Backend: Go 1.26, Fiber v2, `stripe-go/v78`
  - Frontend: Flutter (Dart), `flutter_stripe` (Cartão), `qr_flutter` (PIX), Riverpod
- **Architecture Approach**: Cliente-Servidor. O backend gera o `PaymentIntent` e retorna o segredo. O frontend usa pacotes nativos para se comunicar com a Stripe (PCI-Compliance) usando esse segredo.

## 2. Data Models & Schema
- **Database Changes**: 
  - Tabela `subscriptions` mantida. Pode ser necessário garantir que o `stripe_customer_id` seja criado/buscado antes do pagamento.
  - Tabela `clinic_transactions` pode registrar a tentativa de pagamento (Opcional, pois a Stripe já mantem o log de PaymentIntents).
- **State Management (Frontend)**:
  - `PaymentNotifier` (Riverpod): Armazena o estado atual do checkout (`loading`, `success`, `error`, `awaiting_pix`).

## 3. Component Architecture (UI)
- `SaasCheckoutScreen`:
  - **Responsibility**: Orquestra a exibição dos planos e o fluxo de pagamento.
- `StripeCardForm`:
  - **Responsibility**: Widget encapsulado que chama `CardField` (do `flutter_stripe`) para capturar dados do cartão.
- `PixQRCodeView`:
  - **Props**: `qrCodeString` (String), `expiresAt` (DateTime).
  - **Responsibility**: Usa `qr_flutter` para desenhar o QR Code e oferece botão para "Copiar e Colar". Ouve websockets ou faz polling da aprovação.

## 4. Core Logic & Algorithms
- **Operation: Criar PaymentIntent (Backend)**
  - Step 1: Frontend envia `plan` (basic/pro/premium) para `POST /saas/create-payment-intent`.
  - Step 2: Backend busca o `customer_id` na Stripe (ou cria).
  - Step 3: Backend calcula o valor (ex: premium = R$ 199,00).
  - Step 4: Backend chama `stripe.PaymentIntent.New` ativando `payment_method_types: ["card", "pix"]`.
  - Step 5: Retorna `client_secret` (e os dados do PIX se PIX for selecionado).
- **Operation: Confirmar Cartão (Frontend)**
  - Step 1: Usuário digita cartão no `CardField`.
  - Step 2: Frontend chama `Stripe.instance.confirmPayment(client_secret)`.
  - Step 3: Aguarda retorno de Sucesso. Redireciona para `/login` (Força Refresh de token).

## 5. Error Handling & Edge Cases
- **Scenario 1**: Cartão Recusado (Saldo insuficiente).
  - **Handling**: A library `flutter_stripe` joga uma `StripeException`. O app captura e mostra um toast vermelho.
- **Scenario 2**: PIX não pago a tempo (Expiração).
  - **Handling**: O polling para e a tela informa "PIX Expirado". O usuário deve recomeçar.

## 6. Security & Performance
- **Security Check**: Os números de cartão NUNCA tocam no nosso backend Fiber. Vão diretamente do Flutter para a Stripe. PCI-Compliance total.
- **Performance**: O webhook deve processar o evento `payment_intent.succeeded` rapidamente para que o Polling do front identifique a mudança e libere o usuário sem atraso.
