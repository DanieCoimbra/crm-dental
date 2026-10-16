import 'dart:convert';

class Role {
  final int id;
  final String name;
  final List<String> permissions;

  Role({required this.id, required this.name, required this.permissions});

  factory Role.fromJson(Map<String, dynamic> json) {
    List<String> perms = [];
    if (json['permissions'] != null) {
      if (json['permissions'] is String) {
        try {
          final decoded = jsonDecode(json['permissions']) as List;
          perms = decoded.map((e) => e.toString()).toList();
        } catch (e) {
          // ignorar erro de parsing
        }
      } else if (json['permissions'] is List) {
        perms = (json['permissions'] as List).map((e) => e.toString()).toList();
      }
    }

    return Role(
      id: json['id'] is int ? json['id'] : int.parse(json['id'].toString()),
      name: json['name']?.toString() ?? '',
      permissions: perms,
    );
  }
}
