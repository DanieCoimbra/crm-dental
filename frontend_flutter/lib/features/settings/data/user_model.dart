import 'package:frontend_flutter/features/settings/data/role_model.dart';

class User {
  final int id;
  final String name;
  final String email;
  final String? phone;
  final String? avatar;
  final Role? role;
  final int? currentRoomId;

  User({
    required this.id,
    required this.name,
    required this.email,
    this.phone,
    this.avatar,
    this.role,
    this.currentRoomId,
  });

  factory User.fromJson(Map<String, dynamic> json) {
    return User(
      id: json['id'] is int ? json['id'] : int.parse(json['id'].toString()),
      name: json['name']?.toString() ?? '',
      email: json['email']?.toString() ?? '',
      phone: json['phone']?.toString(),
      avatar: json['avatar']?.toString(),
      role: json['role'] != null ? Role.fromJson(json['role']) : null,
      currentRoomId: json['current_room_id'] is int ? json['current_room_id'] : (json['current_room_id'] != null ? int.parse(json['current_room_id'].toString()) : null),
    );
  }
}
