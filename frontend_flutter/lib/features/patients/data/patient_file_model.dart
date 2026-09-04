class PatientFile {
  final int id;
  final int clinicId;
  final int patientId;
  final String fileName;
  final String fileUrl;
  final String fileType;
  final String category;
  final int fileSize;
  final DateTime createdAt;

  PatientFile({
    required this.id,
    required this.clinicId,
    required this.patientId,
    required this.fileName,
    required this.fileUrl,
    required this.fileType,
    required this.category,
    required this.fileSize,
    required this.createdAt,
  });

  bool get isImage {
    final lowerType = fileType.toLowerCase();
    final lowerName = fileName.toLowerCase();
    return lowerType.contains('image') ||
        lowerType.contains('jpg') ||
        lowerType.contains('jpeg') ||
        lowerType.contains('png') ||
        lowerName.endsWith('.jpg') ||
        lowerName.endsWith('.jpeg') ||
        lowerName.endsWith('.png');
  }

  bool get isPdf {
    final lowerType = fileType.toLowerCase();
    final lowerName = fileName.toLowerCase();
    return lowerType.contains('pdf') || lowerName.endsWith('.pdf');
  }

  String get formattedSize {
    if (fileSize <= 0) return 'Tamanho desconhecido';
    if (fileSize < 1024) return '$fileSize B';
    if (fileSize < 1024 * 1024) return '${(fileSize / 1024).toStringAsFixed(1)} KB';
    return '${(fileSize / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  factory PatientFile.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      if (value is String) return int.tryParse(value) ?? 0;
      return 0;
    }

    return PatientFile(
      id: parseInt(json['id']),
      clinicId: parseInt(json['clinic_id']),
      patientId: parseInt(json['patient_id']),
      fileName: json['file_name']?.toString() ?? 'Arquivo',
      fileUrl: json['file_url']?.toString() ?? json['supabase_url']?.toString() ?? '',
      fileType: json['file_type']?.toString() ?? '',
      category: json['category']?.toString() ?? json['file_type_enum']?.toString() ?? 'OUTRO',
      fileSize: parseInt(json['file_size']),
      createdAt: json['created_at'] != null ? DateTime.parse(json['created_at'].toString()) : DateTime.now(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'clinic_id': clinicId,
      'patient_id': patientId,
      'file_name': fileName,
      'file_url': fileUrl,
      'file_type': fileType,
      'category': category,
      'file_size': fileSize,
      'created_at': createdAt.toIso8601String(),
    };
  }
}
