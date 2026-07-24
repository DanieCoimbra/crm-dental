# Feature Contract: F05-Inventory Control

## 1. Contract Summary
- **Feature**: F05-Inventory Control
- **Version**: 1.0.0
- **Primary Consumer(s)**: Frontend Flutter, e Consumer de Mensageria Interna do Backend (consumindo eventos do F03).

## 2. Inputs
- **Evento Interno (Go Channel ou Mensageria)**: `Appointment.Completed`
- **Payload Schema**:
  ```json
  {
    "appointment_id": "integer",
    "clinic_id": "integer",
    "procedure_ids": ["array de integers"]
  }
  ```

## 3. Outputs
- Nenhum output direto para a API pública na dedução. O resultado (side effect) é visualizado nos GETs do Frontend (ex: `GET /api/v1/inventory/alerts`).

## 4. Business Rules & Limits
- **Validation Rules**: Deduções automáticas logadas em `stock_movements` devem receber a tag `type = 'auto_deduction'`.

## 5. Events Emitted (Side Effects)
- **Event Name**: `Inventory.StockAlertTrigged`
- **Trigger**: Emitido se um item cruzar a fronteira de > mínimo para <= mínimo.

## 6. Acceptance Criteria (Integration)
- [ ] O módulo recebe payload de `Appointment.Completed` via listener interno.
- [ ] O banco de dados recebe a baixa das quantidades correspondentes sem travar o endpoint de quem emitiu o evento.
