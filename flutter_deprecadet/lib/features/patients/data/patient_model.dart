class Patient {
  final int id;
  final int clinicId;
  final String name;
  final String? cpf;
  final String? email;
  final String? phone;
  final String? cep;
  final String? street;
  final String? neighborhood;
  final String? number;
  final String? healthInsurance;
  final String? birthDate;
  final String? medicalHistory;
  final String? notes;
  final double? weight;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  Patient({
    required this.id,
    required this.clinicId,
    required this.name,
    this.cpf,
    this.email,
    this.phone,
    this.cep,
    this.street,
    this.neighborhood,
    this.number,
    this.healthInsurance,
    this.birthDate,
    this.medicalHistory,
    this.notes,
    this.weight,
    this.createdAt,
    this.updatedAt,
  });

  String get initials {
    if (name.trim().isEmpty) return '?';
    final parts = name.trim().split(RegExp(r'\s+'));
    if (parts.length == 1) {
      return parts.first.substring(0, parts.first.length > 1 ? 2 : 1).toUpperCase();
    }
    return '${parts.first[0]}${parts.last[0]}'.toUpperCase();
  }

  factory Patient.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      if (value is String) return int.tryParse(value) ?? 0;
      return 0;
    }

    return Patient(
      id: parseInt(json['id']),
      clinicId: parseInt(json['clinic_id']),
      name: json['name']?.toString() ?? json['full_name']?.toString() ?? 'Sem nome',
      cpf: json['cpf']?.toString() ?? json['cpf_encrypted']?.toString(),
      email: json['email']?.toString(),
      phone: json['phone']?.toString() ?? json['phone_encrypted']?.toString(),
      cep: json['cep']?.toString(),
      street: json['street']?.toString(),
      neighborhood: json['neighborhood']?.toString(),
      number: json['number']?.toString(),
      healthInsurance: json['health_insurance']?.toString(),
      birthDate: json['birth_date']?.toString(),
      medicalHistory: json['medical_history']?.toString(),
      notes: json['notes']?.toString() ?? json['notes_encrypted']?.toString(),
      weight: json['weight'] != null ? double.tryParse(json['weight'].toString()) : null,
      createdAt: json['created_at'] != null ? DateTime.tryParse(json['created_at'].toString()) : null,
      updatedAt: json['updated_at'] != null ? DateTime.tryParse(json['updated_at'].toString()) : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'clinic_id': clinicId,
      'name': name,
      'cpf': cpf,
      'email': email,
      'phone': phone,
      'cep': cep,
      'street': street,
      'neighborhood': neighborhood,
      'number': number,
      'health_insurance': healthInsurance,
      'birth_date': birthDate,
      'medical_history': medicalHistory,
      'notes': notes,
      'weight': weight,
    };
  }
}
