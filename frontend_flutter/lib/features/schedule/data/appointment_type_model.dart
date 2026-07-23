class AppointmentType {
  final int id;
  final String name;
  final String description;
  final int durationMinutes;
  final String color;
  final List<ProcedureMaterial> materials;

  AppointmentType({
    required this.id,
    required this.name,
    required this.description,
    required this.durationMinutes,
    required this.color,
    this.materials = const [],
  });

  factory AppointmentType.fromJson(Map<String, dynamic> json) {
    return AppointmentType(
      id: json['id'] is int ? json['id'] : int.parse(json['id'].toString()),
      name: json['name']?.toString() ?? '',
      description: json['description']?.toString() ?? '',
      durationMinutes: json['duration_minutes'] is int ? json['duration_minutes'] : int.parse(json['duration_minutes'].toString()),
      color: json['color']?.toString() ?? '#3788d8',
      materials: json['materials'] != null 
          ? (json['materials'] as List).map((i) => ProcedureMaterial.fromJson(i)).toList()
          : [],
    );
  }
}

class ProcedureMaterial {
  final int id;
  final int inventoryItemId;
  final double quantity;
  final String? itemName; // Opcional caso venha com preload
  final String? itemUnit;

  ProcedureMaterial({
    required this.id,
    required this.inventoryItemId,
    required this.quantity,
    this.itemName,
    this.itemUnit,
  });

  factory ProcedureMaterial.fromJson(Map<String, dynamic> json) {
    return ProcedureMaterial(
      id: json['id'],
      inventoryItemId: json['inventory_item_id'],
      quantity: (json['quantity'] ?? 0).toDouble(),
      itemName: json['item']?['name'],
      itemUnit: json['item']?['unit'],
    );
  }
}
