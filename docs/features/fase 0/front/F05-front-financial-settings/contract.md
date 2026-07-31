# Contrato de Repositório Client-Side: Financeiro e Configurações

- **Domínio:** `F05-front-financial-settings`
- **Pacote:** `package:frontend_flutter/features/financial` & `settings`

---

## 1. Interface de Repositório (`FinancialRepository` & `SettingsRepository`)

```dart
abstract class IFinancialRepository {
  Future<List<Map<String, dynamic>>> getTransactions();
  Future<void> createTransaction(Map<String, dynamic> data);
}

abstract class ISettingsRepository {
  Future<void> saveRole(Map<String, dynamic> data);
  Future<void> saveRoom(Map<String, dynamic> data);
  Future<void> saveAppointmentType(Map<String, dynamic> data);
}
```

---

## 2. Contrato de Componentes e Modais

### `TransactionFormDialog`
- **Inputs:** `Map<String, dynamic>? transaction`.

### `RoleManagerDialog`, `RoomDialog`, `AppointmentTypeDialog`
- **Inputs:** `Map<String, dynamic>? initialData`.
