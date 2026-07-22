import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/schedule/data/appointment_type_model.dart';
import 'package:frontend_flutter/features/settings/data/user_model.dart';

class Waitlist {
  final int id;
  final int patientId;
  final Patient? patient;
  final int? doctorId;
  final User? doctor;
  final int? appointmentTypeId;
  final AppointmentType? appointmentType;
  final String preferredDays;
  final String preferredTimeRange;
  final String urgencyLevel;
  final String notes;
  final String status;

  Waitlist({
    required this.id,
    required this.patientId,
    this.patient,
    this.doctorId,
    this.doctor,
    this.appointmentTypeId,
    this.appointmentType,
    required this.preferredDays,
    required this.preferredTimeRange,
    required this.urgencyLevel,
    required this.notes,
    required this.status,
  });

  factory Waitlist.fromJson(Map<String, dynamic> json) {
    return Waitlist(
      id: json['id'] is int ? json['id'] : int.parse(json['id'].toString()),
      patientId: json['patient_id'] is int ? json['patient_id'] : int.parse(json['patient_id'].toString()),
      patient: json['patient'] != null ? Patient.fromJson(json['patient']) : null,
      doctorId: json['doctor_id'] != null ? (json['doctor_id'] is int ? json['doctor_id'] : int.parse(json['doctor_id'].toString())) : null,
      doctor: json['doctor'] != null ? User.fromJson(json['doctor']) : null,
      appointmentTypeId: json['appointment_type_id'] != null ? (json['appointment_type_id'] is int ? json['appointment_type_id'] : int.parse(json['appointment_type_id'].toString())) : null,
      appointmentType: json['appointment_type'] != null ? AppointmentType.fromJson(json['appointment_type']) : null,
      preferredDays: json['preferred_days']?.toString() ?? '',
      preferredTimeRange: json['preferred_time_range']?.toString() ?? '',
      urgencyLevel: json['urgency_level']?.toString() ?? '',
      notes: json['notes']?.toString() ?? '',
      status: json['status']?.toString() ?? 'pending',
    );
  }
}
