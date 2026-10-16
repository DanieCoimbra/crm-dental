import 'package:flutter/material.dart';

enum ToothFace {
  mesial('MESIAL', 'Mesial'),
  distal('DISTAL', 'Distal'),
  oclusal('OCLUSAL', 'Oclusal'),
  incisal('INCISAL', 'Incisal'),
  vestibular('VESTIBULAR', 'Vestibular'),
  palatina('PALATINA', 'Palatina'),
  lingual('LINGUAL', 'Lingual'),
  geral('GERAL', 'Dente Todo (Geral)');

  final String code;
  final String label;

  const ToothFace(this.code, this.label);

  static ToothFace fromString(String? value) {
    if (value == null) return ToothFace.geral;
    final upper = value.trim().toUpperCase();
    return ToothFace.values.firstWhere(
      (e) => e.code == upper,
      orElse: () => ToothFace.geral,
    );
  }
}

enum ToothCondition {
  higido('HIGIDO', 'Hígido', Color(0xFF10B981)),
  cariado('CARIADO', 'Cariado (Lesão Ativa)', Color(0xFFEF4444)),
  restaurado('RESTAURADO', 'Restaurado', Color(0xFF2563EB)),
  extraido('EXTRAIDO', 'Extraído / Ausente', Color(0xFF6B7280)),
  implante('IMPLANTE', 'Implante', Color(0xFF8B5CF6)),
  tratamentoCanal('TRATAMENTO_CANAL', 'Tratamento de Canal', Color(0xFFF59E0B)),
  coroaProtese('COROA_PROTESE', 'Coroa / Prótese', Color(0xFFF97316)),
  emTratamento('EM_TRATAMENTO', 'Em Tratamento', Color(0xFF06B6D4));

  final String code;
  final String label;
  final Color color;

  const ToothCondition(this.code, this.label, this.color);

  static ToothCondition fromString(String? value) {
    if (value == null) return ToothCondition.higido;
    final upper = value.trim().toUpperCase();
    return ToothCondition.values.firstWhere(
      (e) => e.code == upper,
      orElse: () => ToothCondition.higido,
    );
  }
}

class ToothStatus {
  final dynamic id;
  final int patientId;
  final int toothNumber;
  final ToothFace face;
  final ToothCondition condition;
  final String? notes;
  final DateTime? updatedAt;

  ToothStatus({
    this.id,
    required this.patientId,
    required this.toothNumber,
    this.face = ToothFace.geral,
    required this.condition,
    this.notes,
    this.updatedAt,
  });

  factory ToothStatus.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic v) {
      if (v is int) return v;
      if (v is double) return v.toInt();
      return int.tryParse(v?.toString() ?? '0') ?? 0;
    }

    return ToothStatus(
      id: json['id'],
      patientId: parseInt(json['patient_id']),
      toothNumber: parseInt(json['tooth_number']),
      face: ToothFace.fromString(json['face']?.toString()),
      condition: ToothCondition.fromString(json['condition']?.toString()),
      notes: json['notes']?.toString(),
      updatedAt: json['updated_at'] != null ? DateTime.tryParse(json['updated_at'].toString()) : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      if (id != null) 'id': id,
      'patient_id': patientId,
      'tooth_number': toothNumber,
      'face': face.code,
      'condition': condition.code,
      if (notes != null) 'notes': notes,
    };
  }
}

class ToothHistory {
  final dynamic id;
  final int patientId;
  final int toothNumber;
  final ToothFace face;
  final ToothCondition? previousCondition;
  final ToothCondition newCondition;
  final String? notes;
  final String? createdByName;
  final DateTime createdAt;

  ToothHistory({
    this.id,
    required this.patientId,
    required this.toothNumber,
    required this.face,
    this.previousCondition,
    required this.newCondition,
    this.notes,
    this.createdByName,
    required this.createdAt,
  });

  factory ToothHistory.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic v) {
      if (v is int) return v;
      if (v is double) return v.toInt();
      return int.tryParse(v?.toString() ?? '0') ?? 0;
    }

    return ToothHistory(
      id: json['id'],
      patientId: parseInt(json['patient_id']),
      toothNumber: parseInt(json['tooth_number']),
      face: ToothFace.fromString(json['face']?.toString()),
      previousCondition: json['previous_condition'] != null ? ToothCondition.fromString(json['previous_condition'].toString()) : null,
      newCondition: ToothCondition.fromString(json['new_condition']?.toString() ?? json['condition']?.toString()),
      notes: json['notes']?.toString(),
      createdByName: json['user_name']?.toString() ?? json['created_by_name']?.toString() ?? json['user']?['name']?.toString(),
      createdAt: json['created_at'] != null ? DateTime.parse(json['created_at'].toString()) : DateTime.now(),
    );
  }
}
