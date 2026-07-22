class AppSetting {
  final int id;
  final String key;
  final String value;

  AppSetting({
    required this.id,
    required this.key,
    required this.value,
  });

  factory AppSetting.fromJson(Map<String, dynamic> json) {
    return AppSetting(
      id: json['id'] is int ? json['id'] : int.parse(json['id'].toString()),
      key: json['key']?.toString() ?? '',
      value: json['value']?.toString() ?? '',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'key': key,
      'value': value,
    };
  }
}
