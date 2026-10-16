class ReferralPartner {
  final String id;
  final String name;
  final String email;
  final String phone;
  final double commissionRate;
  final String pixKey;
  final DateTime createdAt;

  ReferralPartner({
    required this.id,
    required this.name,
    required this.email,
    required this.phone,
    required this.commissionRate,
    required this.pixKey,
    required this.createdAt,
  });

  factory ReferralPartner.fromJson(Map<String, dynamic> json) {
    return ReferralPartner(
      id: json['id'],
      name: json['name'],
      email: json['email'] ?? '',
      phone: json['phone'] ?? '',
      commissionRate: (json['commission_rate'] as num).toDouble(),
      pixKey: json['pix_key'] ?? '',
      createdAt: DateTime.parse(json['created_at']),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'email': email,
      'phone': phone,
      'commission_rate': commissionRate,
      'pix_key': pixKey,
      'created_at': createdAt.toIso8601String(),
    };
  }
}

class PromoCode {
  final String id;
  final String code;
  final String discountType;
  final double discountValue;
  final int maxUses;
  final int usedCount;
  final bool isActive;
  final ReferralPartner? partner;

  PromoCode({
    required this.id,
    required this.code,
    required this.discountType,
    required this.discountValue,
    required this.maxUses,
    required this.usedCount,
    required this.isActive,
    this.partner,
  });

  factory PromoCode.fromJson(Map<String, dynamic> json) {
    return PromoCode(
      id: json['id'],
      code: json['code'],
      discountType: json['discount_type'],
      discountValue: (json['discount_value'] as num).toDouble(),
      maxUses: json['max_uses'] ?? 0,
      usedCount: json['used_count'] ?? 0,
      isActive: json['is_active'] ?? true,
      partner: json['partner'] != null ? ReferralPartner.fromJson(json['partner']) : null,
    );
  }
}
