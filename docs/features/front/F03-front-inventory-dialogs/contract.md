# Contrato de Repositório Client-Side: Modais de Estoque

- **Domínio:** `F03-front-inventory-dialogs`
- **Pacote:** `package:frontend_flutter/features/inventory`

---

## 1. Interface do Repositório (`InventoryRepository`)

```dart
abstract class IInventoryRepository {
  Future<List<Map<String, dynamic>>> getItems();
  Future<void> saveItem(Map<String, dynamic> itemData);
  Future<void> registerTransaction({
    required String itemId,
    required String type, // 'in' ou 'out'
    required int quantity,
    String? reason,
  });
}
```

---

## 2. Contrato das Modais Interativas (Widgets)

### `InventoryFormDialog`
- **Inputs:** `Map<String, dynamic>? item` (Opcional).
- **Callbacks:** `VoidCallback? onSuccess`.

### `InventoryTransactionDialog`
- **Inputs:** `required Map<String, dynamic> item`, `required String type` ('in' | 'out').
- **Callbacks:** `VoidCallback? onSuccess`.
