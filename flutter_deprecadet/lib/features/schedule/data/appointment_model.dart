import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/schedule/data/appointment_type_model.dart';
import 'package:frontend_flutter/features/settings/data/user_model.dart';

class Appointment {
  final int id;
  final int doctorId;
  final int patientId;
  final User? doctor;
  final Patient? patient;
  final int? roomId;
  final int? appointmentTypeId;
  final AppointmentType? appointmentType;
  final DateTime startTime;
  final DateTime endTime;
  final DateTime? actualStartTime;
  final DateTime? actualEndTime;
  final String notes;
  final String status;

  Appointment({
    required this.id,
    required this.doctorId,
    required this.patientId,
    this.doctor,
    this.patient,
    this.roomId,
    this.appointmentTypeId,
    this.appointmentType,
    required this.startTime,
    required this.endTime,
    this.actualStartTime,
    this.actualEndTime,
    required this.notes,
    required this.status,
  });

  factory Appointment.fromJson(Map<String, dynamic> json) {
    return Appointment(
      id: json['id'] is int ? json['id'] : int.parse(json['id'].toString()),
      doctorId: json['doctor_id'] is int ? json['doctor_id'] : int.parse(json['doctor_id'].toString()),
      doctor: json['doctor'] != null ? User.fromJson(json['doctor']) : null,
      patientId: json['patient_id'] is int ? json['patient_id'] : int.parse(json['patient_id'].toString()),
      patient: json['patient'] != null ? Patient.fromJson(json['patient']) : null,
      roomId: json['room_id'] != null ? (json['room_id'] is int ? json['room_id'] : int.parse(json['room_id'].toString())) : null,
      appointmentTypeId: json['appointment_type_id'] != null ? (json['appointment_type_id'] is int ? json['appointment_type_id'] : int.parse(json['appointment_type_id'].toString())) : null,
      appointmentType: json['appointment_type'] != null ? AppointmentType.fromJson(json['appointment_type']) : null,
      startTime: DateTime.parse(json['start_time'].toString()),
      endTime: DateTime.parse(json['end_time'].toString()),
      actualStartTime: json['actual_start_time'] != null ? DateTime.parse(json['actual_start_time'].toString()) : null,
      actualEndTime: json['actual_end_time'] != null ? DateTime.parse(json['actual_end_time'].toString()) : null,
      notes: json['notes']?.toString() ?? '',
      status: json['status']?.toString() ?? 'scheduled',
    );
  }
}
