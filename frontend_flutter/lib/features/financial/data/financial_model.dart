class ClinicTransaction {
  final int id;
  final int clinicId;
  final int? patientId;
  final String type; // income, expense
  final String category;
  final String description;
  final int totalAmountCents;
  final String paymentMethod;
  final String status;
  final DateTime dueDate;
  final DateTime? paidAt;
  final List<ClinicInstallment> installments;

  ClinicTransaction({
    required this.id,
    required this.clinicId,
    this.patientId,
    required this.type,
    required this.category,
    required this.description,
    required this.totalAmountCents,
    required this.paymentMethod,
    required this.status,
    required this.dueDate,
    this.paidAt,
    this.installments = const [],
  });

  factory ClinicTransaction.fromJson(Map<String, dynamic> json) {
    return ClinicTransaction(
      id: json['id'],
      clinicId: json['clinic_id'],
      patientId: json['patient_id'],
      type: json['type'],
      category: json['category'],
      description: json['description'],
      totalAmountCents: json['total_amount_cents'],
      paymentMethod: json['payment_method'],
      status: json['status'],
      dueDate: DateTime.parse(json['due_date']),
      paidAt: json['paid_at'] != null ? DateTime.parse(json['paid_at']) : null,
      installments: json['installments'] != null
          ? (json['installments'] as List).map((i) => ClinicInstallment.fromJson(i)).toList()
          : [],
    );
  }

  double get amount => totalAmountCents / 100.0;
}

class ClinicInstallment {
  final int id;
  final int transactionId;
  final int number;
  final int totalNumber;
  final int amountCents;
  final DateTime dueDate;
  final DateTime? paidAt;
  final String status;

  ClinicInstallment({
    required this.id,
    required this.transactionId,
    required this.number,
    required this.totalNumber,
    required this.amountCents,
    required this.dueDate,
    this.paidAt,
    required this.status,
  });

  factory ClinicInstallment.fromJson(Map<String, dynamic> json) {
    return ClinicInstallment(
      id: json['id'],
      transactionId: json['transaction_id'],
      number: json['number'],
      totalNumber: json['total_number'],
      amountCents: json['amount_cents'],
      dueDate: DateTime.parse(json['due_date']),
      paidAt: json['paid_at'] != null ? DateTime.parse(json['paid_at']) : null,
      status: json['status'],
    );
  }

  double get amount => amountCents / 100.0;
}
