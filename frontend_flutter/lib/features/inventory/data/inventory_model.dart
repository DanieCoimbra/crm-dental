class InventoryItem {
  final int id;
  final String name;
  final String sku;
  final double quantity;
  final double minQuantity;
  final String unit;
  final bool isLowStock;

  InventoryItem({
    required this.id,
    required this.name,
    required this.sku,
    required this.quantity,
    required this.minQuantity,
    required this.unit,
    required this.isLowStock,
  });

  factory InventoryItem.fromJson(Map<String, dynamic> json) {
    return InventoryItem(
      id: json['id'],
      name: json['name'] ?? '',
      sku: json['sku'] ?? '',
      quantity: (json['quantity'] ?? 0).toDouble(),
      minQuantity: (json['min_quantity'] ?? 0).toDouble(),
      unit: json['unit'] ?? '',
      isLowStock: json['is_low_stock'] ?? false,
    );
  }
}

class InventoryTransaction {
  final int id;
  final int inventoryItemId;
  final String type; // 'in' or 'out'
  final double quantity;
  final String notes;
  final DateTime date;

  InventoryTransaction({
    required this.id,
    required this.inventoryItemId,
    required this.type,
    required this.quantity,
    required this.notes,
    required this.date,
  });

  factory InventoryTransaction.fromJson(Map<String, dynamic> json) {
    return InventoryTransaction(
      id: json['id'],
      inventoryItemId: json['inventory_item_id'],
      type: json['type'] ?? 'out',
      quantity: (json['quantity'] ?? 0).toDouble(),
      notes: json['notes'] ?? '',
      date: DateTime.parse(json['date']),
    );
  }
}
