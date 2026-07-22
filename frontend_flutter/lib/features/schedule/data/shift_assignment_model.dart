import 'package:frontend_flutter/features/settings/data/user_model.dart';
import 'package:frontend_flutter/features/settings/data/room_model.dart';

class ShiftAssignment {
  final int id;
  final int clinicId;
  final int doctorId;
  final User? doctor;
  final int roomId;
  final Room? room;
  final DateTime? date;
  final String shift; // 'morning' or 'afternoon'

  ShiftAssignment({
    required this.id,
    required this.clinicId,
    required this.doctorId,
    this.doctor,
    required this.roomId,
    this.room,
    this.date,
    required this.shift,
  });

  factory ShiftAssignment.fromJson(Map<String, dynamic> json) {
    return ShiftAssignment(
      id: json['id'],
      clinicId: json['clinic_id'],
      doctorId: json['doctor_id'],
      doctor: json['doctor'] != null ? User.fromJson(json['doctor']) : null,
      roomId: json['room_id'],
      room: json['room'] != null ? Room.fromJson(json['room']) : null,
      date: json['date'] != null ? DateTime.parse(json['date']) : null,
      shift: json['shift'] ?? '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'clinic_id': clinicId,
      'doctor_id': doctorId,
      'room_id': roomId,
      'date': date?.toIso8601String(),
      'shift': shift,
    };
  }
}
