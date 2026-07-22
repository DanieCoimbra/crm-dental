class AppointmentType {
  final int id;
  final String name;
  final String description;
  final int durationMinutes;
  final String color;

  AppointmentType({
    required this.id,
    required this.name,
    required this.description,
    required this.durationMinutes,
    required this.color,
  });

  factory AppointmentType.fromJson(Map<String, dynamic> json) {
    return AppointmentType(
      id: json['id'] is int ? json['id'] : int.parse(json['id'].toString()),
      name: json['name']?.toString() ?? '',
      description: json['description']?.toString() ?? '',
      durationMinutes: json['duration_minutes'] is int ? json['duration_minutes'] : int.parse(json['duration_minutes'].toString()),
      color: json['color']?.toString() ?? '#3788d8',
    );
  }
}
