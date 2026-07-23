class ClinicalEvolution {
  final int id;
  final int patientId;
  final String contentHtml;
  final DateTime createdAt;
  final String? userName; // se o backend retornar

  ClinicalEvolution({
    required this.id,
    required this.patientId,
    required this.contentHtml,
    required this.createdAt,
    this.userName,
  });

  factory ClinicalEvolution.fromJson(Map<String, dynamic> json) {
    return ClinicalEvolution(
      id: json['id'] is int ? json['id'] : int.parse(json['id'].toString()),
      patientId: json['patient_id'] is int ? json['patient_id'] : int.parse(json['patient_id'].toString()),
      contentHtml: json['content_html']?.toString() ?? '',
      createdAt: DateTime.parse(json['created_at'].toString()),
      userName: json['user']?['name']?.toString(), // se tiver relacionamento populado
    );
  }
}
