class Room {
  final int id;
  final String name;
  final String? description;
  final bool isActive;

  Room({
    required this.id,
    required this.name,
    this.description,
    this.isActive = true,
  });

  factory Room.fromJson(Map<String, dynamic> json) {
    return Room(
      id: json['id'],
      name: json['name'],
      description: json['description'],
      isActive: json['is_active'] ?? true,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'description': description,
      'is_active': isActive,
    };
  }
}
