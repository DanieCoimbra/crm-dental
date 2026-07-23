class PatientFile {
  final int id;
  final int clinicId;
  final int patientId;
  final String fileName;
  final String supabaseUrl;
  final String fileType;
  final String category;
  final DateTime createdAt;
  final DateTime updatedAt;

  PatientFile({
    required this.id,
    required this.clinicId,
    required this.patientId,
    required this.fileName,
    required this.supabaseUrl,
    required this.fileType,
    required this.category,
    required this.createdAt,
    required this.updatedAt,
  });

  factory PatientFile.fromJson(Map<String, dynamic> json) {
    return PatientFile(
      id: json['id'] as int,
      clinicId: json['clinic_id'] ?? 0,
      patientId: json['patient_id'] ?? 0,
      fileName: json['file_name'] ?? '',
      supabaseUrl: json['supabase_url'] ?? '',
      fileType: json['file_type'] ?? '',
      category: json['category'] ?? '',
      createdAt: json['created_at'] != null ? DateTime.parse(json['created_at']) : DateTime.now(),
      updatedAt: json['updated_at'] != null ? DateTime.parse(json['updated_at']) : DateTime.now(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'clinic_id': clinicId,
      'patient_id': patientId,
      'file_name': fileName,
      'supabase_url': supabaseUrl,
      'file_type': fileType,
      'category': category,
      'created_at': createdAt.toIso8601String(),
      'updated_at': updatedAt.toIso8601String(),
    };
  }
}
