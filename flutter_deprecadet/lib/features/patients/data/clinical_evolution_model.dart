class ClinicalEvolution {
  final int id;
  final int patientId;
  final int? dentistId;
  final String? userName;
  final DateTime attendanceDate;
  final String? chiefComplaint;
  final String? diagnosis;
  final String procedureSummary;
  final String contentHtml;
  final DateTime createdAt;

  ClinicalEvolution({
    required this.id,
    required this.patientId,
    this.dentistId,
    this.userName,
    required this.attendanceDate,
    this.chiefComplaint,
    this.diagnosis,
    required this.procedureSummary,
    required this.contentHtml,
    required this.createdAt,
  });

  factory ClinicalEvolution.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      if (value is String) return int.tryParse(value) ?? 0;
      return 0;
    }

    final createdAt = json['created_at'] != null ? DateTime.parse(json['created_at'].toString()) : DateTime.now();
    final attendanceDate = json['attendance_date'] != null
        ? DateTime.parse(json['attendance_date'].toString())
        : createdAt;

    final chiefComplaint = json['chief_complaint']?.toString() ?? json['chief_complaint_encrypted']?.toString();
    final diagnosis = json['diagnosis']?.toString() ?? json['diagnosis_encrypted']?.toString();
    final procedureSummary = json['procedure_summary']?.toString() ?? '';
    final contentHtml = json['content_html']?.toString() ?? '';

    return ClinicalEvolution(
      id: parseInt(json['id']),
      patientId: parseInt(json['patient_id']),
      dentistId: json['dentist_id'] != null ? parseInt(json['dentist_id']) : null,
      userName: json['user_name']?.toString() ?? json['user']?['name']?.toString() ?? json['dentist_name']?.toString(),
      attendanceDate: attendanceDate,
      chiefComplaint: chiefComplaint,
      diagnosis: diagnosis,
      procedureSummary: procedureSummary.isNotEmpty ? procedureSummary : contentHtml,
      contentHtml: contentHtml.isNotEmpty ? contentHtml : procedureSummary,
      createdAt: createdAt,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'patient_id': patientId,
      if (dentistId != null) 'dentist_id': dentistId,
      'attendance_date': attendanceDate.toIso8601String(),
      if (chiefComplaint != null) 'chief_complaint': chiefComplaint,
      if (diagnosis != null) 'diagnosis': diagnosis,
      'procedure_summary': procedureSummary,
      'content_html': contentHtml,
      'created_at': createdAt.toIso8601String(),
    };
  }
}
