# Action Plan: F12 - Stripe Payments (Cartão e PIX)

## 1. Local Scope
- **Derivation**: Extracted from `tasks/prd-dental-crm-fullstack.md` (US-011).
- **Responsibility**: Implementar o processo de checkout financeiro real dentro do aplicativo para ativação da assinatura SaaS. Isso inclui substituir o botão simulado por um formulário de cartão de crédito nativo (`flutter_stripe`) e a geração de QR Code do PIX integrado com a API da Stripe.

## 2. External Dependencies (Before starting)
- Necessário conta ativa na Stripe (Stripe Dashboard).
- Secret Key (`STRIPE_SECRET_KEY`) configurada no backend `.env`.
- Publishable Key (`STRIPE_PUBLISHABLE_KEY`) configurada no frontend.
- Conhecimento do endpoint atual de mudança de plano (`change-plan`) que precisará ser reescrito para gerar um `PaymentIntent`.

## 3. Execution Phases
- **Phase 1: Backend Setup & Contracts**
  - Adicionar pacote `stripe-go` para manipular `PaymentIntents`.
  - Criar um novo endpoint `POST /api/v1/saas/create-payment-intent`.
  - O endpoint deve calcular o valor do plano escolhido e retornar o `client_secret`.
  - Atualizar Webhook para suportar o evento `payment_intent.succeeded` (se diferente do Invoice).
  
- **Phase 2: Frontend Setup (Cartão de Crédito)**
  - Instalar e inicializar o pacote `flutter_stripe` no arquivo `main.dart`.
  - Construir o componente UI `CardField` dentro da `SaasCheckoutScreen`.
  - Implementar lógica de confirmação (`Stripe.instance.confirmPayment`).

- **Phase 3: Frontend Setup (PIX)**
  - Habilitar PIX como método de pagamento no `PaymentIntent` no backend.
  - Receber os dados do PIX (QR Code e URL) no Flutter.
  - Instalar `qr_flutter` para desenhar o QR Code na tela com botão de "Copia e Cola".

- **Phase 4: Feedback e Integração**
  - Implementar um mecanismo de escuta (Polling de 5 em 5 segundos chamando uma rota de status ou WebSocket) para atualizar a tela do checkout assim que o PIX ou Cartão for aprovado pela Stripe.
  - Redirecionar para `/login` (Refresh) quando o status virar `active`.

## 4. Next Steps
- Prompt the user or agent to run `feature-spec` and `feature-contract` specifically for this folder if more technical granularity is required.
