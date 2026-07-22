class Clinic {
  final int id;
  final String name;
  final String cnpj;
  final String email;
  final String phone;
  final String status;
  final String plan;
  final DateTime? trialEndsAt;

  Clinic({
    required this.id,
    required this.name,
    required this.cnpj,
    required this.email,
    required this.phone,
    required this.status,
    required this.plan,
    this.trialEndsAt,
  });

  factory Clinic.fromJson(Map<String, dynamic> json) {
    return Clinic(
      id: json['id'] is int ? json['id'] : int.parse(json['id'].toString()),
      name: json['name']?.toString() ?? '',
      cnpj: json['cnpj']?.toString() ?? '',
      email: json['email']?.toString() ?? '',
      phone: json['phone']?.toString() ?? '',
      status: json['status']?.toString() ?? 'trial',
      plan: json['plan']?.toString() ?? 'basic',
      trialEndsAt: json['trial_ends_at'] != null ? DateTime.tryParse(json['trial_ends_at'].toString()) : null,
    );
  }
}
