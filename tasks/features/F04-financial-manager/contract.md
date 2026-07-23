# Feature Contract: F04-Financial Manager

## 1. Contract Summary
- **Feature**: F04-Financial Manager
- **Version**: 1.0.0
- **Primary Consumer(s)**: Frontend Flutter (Painel Administrativo da Clínica).

## 2. Inputs
- **Endpoint**: `POST /api/v1/financial/transactions`
- **Payload Schema**:
  ```json
  {
    "type": "string (enum: income, expense)",
    "total_amount_cents": "integer",
    "installments_count": "integer (min: 1, max: 36)",
    "description": "string"
  }
  ```

## 3. Outputs
- **Success Response (201 Created)**:
  ```json
  {
    "transaction_id": "integer",
    "installments_created": "integer"
  }
  ```

## 4. Business Rules & Limits
- **Validation Rules**: `total_amount_cents` deve ser um inteiro positivo. Valores negativos são negados (HTTP 422).

## 5. Events Emitted (Side Effects)
- **Event Name**: `Financial.TransactionPaid`
- **Trigger**: Quando status de uma installment ou transaction total muda para `paid`.

## 6. Acceptance Criteria (Integration)
- [ ] Envio de `total_amount_cents: 100` e `installments_count: 3` -> Sistema cria parcelas de valores exatos cuja soma retorne 100, sem perda fracionária.
