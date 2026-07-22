class TrashStatus {
  final bool isFull;
  final bool isAlmostFull;
  final int total;
  final int limit;

  TrashStatus({
    required this.isFull,
    required this.isAlmostFull,
    required this.total,
    required this.limit,
  });

  factory TrashStatus.fromJson(Map<String, dynamic> json) {
    return TrashStatus(
      isFull: json['is_full'] ?? false,
      isAlmostFull: json['is_almost_full'] ?? false,
      total: json['total'] is int ? json['total'] : int.parse(json['total'].toString()),
      limit: json['limit'] is int ? json['limit'] : int.parse(json['limit'].toString()),
    );
  }
}

class TrashItem {
  final int id;
  final String type; // 'user', 'patient', 'appointment'
  final String title;
  final String subtitle;
  final DateTime? deletedAt;

  TrashItem({
    required this.id,
    required this.type,
    required this.title,
    required this.subtitle,
    this.deletedAt,
  });
}
