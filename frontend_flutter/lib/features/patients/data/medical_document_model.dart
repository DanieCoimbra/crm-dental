import 'package:intl/intl.dart';

class MedicalDocument {
  final int id;
  final int? clinicId;
  final int patientId;
  final int? dentistId;
  final String type; // 'ATESTADO', 'RECEITA', 'ENCAMINHAMENTO'
  final String title;
  final String content;
  final DateTime createdAt;
  final DateTime? updatedAt;

  MedicalDocument({
    required this.id,
    this.clinicId,
    required this.patientId,
    this.dentistId,
    required this.type,
    required this.title,
    required this.content,
    required this.createdAt,
    this.updatedAt,
  });

  factory MedicalDocument.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      if (value is String) return int.tryParse(value) ?? 0;
      return 0;
    }

    final createdAt = json['created_at'] != null
        ? DateTime.tryParse(json['created_at'].toString()) ?? DateTime.now()
        : DateTime.now();

    final updatedAt = json['updated_at'] != null
        ? DateTime.tryParse(json['updated_at'].toString())
        : null;

    final contentVal = json['content']?.toString() ??
        json['content_encrypted']?.toString() ??
        '';

    return MedicalDocument(
      id: parseInt(json['id']),
      clinicId: json['clinic_id'] != null ? parseInt(json['clinic_id']) : null,
      patientId: parseInt(json['patient_id']),
      dentistId: json['dentist_id'] != null ? parseInt(json['dentist_id']) : null,
      type: (json['type']?.toString() ?? 'ATESTADO').toUpperCase(),
      title: json['title']?.toString() ?? 'Documento Clínico',
      content: contentVal,
      createdAt: createdAt,
      updatedAt: updatedAt,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      if (clinicId != null) 'clinic_id': clinicId,
      'patient_id': patientId,
      if (dentistId != null) 'dentist_id': dentistId,
      'type': type,
      'title': title,
      'content': content,
      'created_at': createdAt.toIso8601String(),
      if (updatedAt != null) 'updated_at': updatedAt!.toIso8601String(),
    };
  }

  String get typeLabel {
    switch (type.toUpperCase()) {
      case 'ATESTADO':
        return 'Atestado Odontológico';
      case 'RECEITA':
        return 'Receituário Médico';
      case 'ENCAMINHAMENTO':
        return 'Encaminhamento';
      default:
        return title.isNotEmpty ? title : 'Documento';
    }
  }

  String get formattedDate {
    return DateFormat('dd/MM/yyyy HH:mm').format(createdAt);
  }
}
