class Procedure {
  final int id;
  final int clinicId;
  final String name;
  final String? description;
  final int basePriceCents;
  final int durationMinutes;
  final String color;
  final DateTime? createdAt;

  Procedure({
    required this.id,
    required this.clinicId,
    required this.name,
    this.description,
    required this.basePriceCents,
    this.durationMinutes = 30,
    this.color = '#2563EB',
    this.createdAt,
  });

  double get basePrice => basePriceCents / 100.0;

  factory Procedure.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      return int.tryParse(value.toString()) ?? 0;
    }

    return Procedure(
      id: parseInt(json['id']),
      clinicId: parseInt(json['clinic_id']),
      name: json['name']?.toString() ?? '',
      description: json['description']?.toString(),
      basePriceCents: parseInt(json['base_price_cents']),
      durationMinutes: parseInt(json['duration_minutes'] ?? 30),
      color: json['color']?.toString() ?? '#2563EB',
      createdAt: json['created_at'] != null ? DateTime.tryParse(json['created_at'].toString()) : null,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'clinic_id': clinicId,
      'name': name,
      'description': description,
      'base_price_cents': basePriceCents,
      'duration_minutes': durationMinutes,
      'color': color,
    };
  }
}

class BudgetItem {
  final int? id;
  final int? budgetId;
  final int procedureId;
  final String? procedureName;
  final int? toothNumber;
  final String? face;
  final int priceCents;
  final int quantity;

  BudgetItem({
    this.id,
    this.budgetId,
    required this.procedureId,
    this.procedureName,
    this.toothNumber,
    this.face,
    required this.priceCents,
    this.quantity = 1,
  });

  double get price => priceCents / 100.0;
  double get subtotal => (priceCents * quantity) / 100.0;
  int get subtotalCents => priceCents * quantity;

  factory BudgetItem.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      return int.tryParse(value.toString()) ?? 0;
    }

    return BudgetItem(
      id: json['id'] != null ? parseInt(json['id']) : null,
      budgetId: json['budget_id'] != null ? parseInt(json['budget_id']) : null,
      procedureId: parseInt(json['procedure_id']),
      procedureName: json['procedure_name']?.toString() ??
          json['procedure']?['name']?.toString() ??
          json['name']?.toString(),
      toothNumber: json['tooth_number'] != null ? parseInt(json['tooth_number']) : null,
      face: json['face']?.toString(),
      priceCents: parseInt(json['price_cents']),
      quantity: parseInt(json['quantity'] ?? 1),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      if (id != null) 'id': id,
      if (budgetId != null) 'budget_id': budgetId,
      'procedure_id': procedureId,
      if (toothNumber != null) 'tooth_number': toothNumber,
      if (face != null && face!.isNotEmpty) 'face': face,
      'price_cents': priceCents,
      'quantity': quantity,
    };
  }
}

class Budget {
  final int id;
  final int clinicId;
  final int patientId;
  final String? patientName;
  final int? dentistId;
  final int totalAmountCents;
  final int discountCents;
  final int finalAmountCents;
  final String status; // draft, sent, approved, rejected
  final String? notes;
  final DateTime? createdAt;
  final DateTime? updatedAt;
  final List<BudgetItem> items;

  Budget({
    required this.id,
    required this.clinicId,
    required this.patientId,
    this.patientName,
    this.dentistId,
    required this.totalAmountCents,
    this.discountCents = 0,
    required this.finalAmountCents,
    required this.status,
    this.notes,
    this.createdAt,
    this.updatedAt,
    this.items = const [],
  });

  double get totalAmount => totalAmountCents / 100.0;
  double get discount => discountCents / 100.0;
  double get finalAmount => finalAmountCents / 100.0;

  factory Budget.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      return int.tryParse(value.toString()) ?? 0;
    }

    List<BudgetItem> parsedItems = [];
    if (json['items'] != null && json['items'] is List) {
      parsedItems = (json['items'] as List).map((i) => BudgetItem.fromJson(i)).toList();
    } else if (json['budget_items'] != null && json['budget_items'] is List) {
      parsedItems = (json['budget_items'] as List).map((i) => BudgetItem.fromJson(i)).toList();
    }

    return Budget(
      id: parseInt(json['id']),
      clinicId: parseInt(json['clinic_id']),
      patientId: parseInt(json['patient_id']),
      patientName: json['patient_name']?.toString() ??
          json['patient']?['name']?.toString() ??
          json['patient']?['full_name']?.toString(),
      dentistId: json['dentist_id'] != null ? parseInt(json['dentist_id']) : null,
      totalAmountCents: parseInt(json['total_amount_cents']),
      discountCents: parseInt(json['discount_cents'] ?? 0),
      finalAmountCents: parseInt(json['final_amount_cents']),
      status: json['status']?.toString() ?? 'draft',
      notes: json['notes']?.toString(),
      createdAt: json['created_at'] != null ? DateTime.tryParse(json['created_at'].toString()) : null,
      updatedAt: json['updated_at'] != null ? DateTime.tryParse(json['updated_at'].toString()) : null,
      items: parsedItems,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'clinic_id': clinicId,
      'patient_id': patientId,
      if (dentistId != null) 'dentist_id': dentistId,
      'total_amount_cents': totalAmountCents,
      'discount_cents': discountCents,
      'final_amount_cents': finalAmountCents,
      'status': status,
      'notes': notes,
      'items': items.map((i) => i.toJson()).toList(),
    };
  }
}

class CashFlowSummary {
  final int totalIncomeCents;
  final int totalPendingCents;
  final int totalOverdueCents;

  CashFlowSummary({
    required this.totalIncomeCents,
    required this.totalPendingCents,
    required this.totalOverdueCents,
  });

  double get totalIncome => totalIncomeCents / 100.0;
  double get totalPending => totalPendingCents / 100.0;
  double get totalOverdue => totalOverdueCents / 100.0;

  factory CashFlowSummary.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      return int.tryParse(value.toString()) ?? 0;
    }

    return CashFlowSummary(
      totalIncomeCents: parseInt(
        json['total_income_cents'] ?? json['income_cents'] ?? json['received_cents'] ?? json['income'] ?? 0,
      ),
      totalPendingCents: parseInt(
        json['total_pending_cents'] ?? json['pending_cents'] ?? json['pending'] ?? 0,
      ),
      totalOverdueCents: parseInt(
        json['total_overdue_cents'] ?? json['overdue_cents'] ?? json['overdue'] ?? json['overdue_amount_cents'] ?? 0,
      ),
    );
  }
}

class ClinicTransaction {
  final int id;
  final int clinicId;
  final int? patientId;
  final String? patientName;
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
    this.patientName,
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
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      return int.tryParse(value.toString()) ?? 0;
    }

    return ClinicTransaction(
      id: parseInt(json['id']),
      clinicId: parseInt(json['clinic_id']),
      patientId: json['patient_id'] != null ? parseInt(json['patient_id']) : null,
      patientName: json['patient_name']?.toString() ?? json['patient']?['name']?.toString(),
      type: json['type']?.toString() ?? 'income',
      category: json['category']?.toString() ?? 'Geral',
      description: json['description']?.toString() ?? '',
      totalAmountCents: parseInt(json['total_amount_cents']),
      paymentMethod: json['payment_method']?.toString() ?? 'PIX',
      status: json['status']?.toString() ?? 'pending',
      dueDate: json['due_date'] != null ? DateTime.parse(json['due_date'].toString()) : DateTime.now(),
      paidAt: json['paid_at'] != null ? DateTime.tryParse(json['paid_at'].toString()) : null,
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
  final String? patientName;
  final String? description;
  final int number;
  final int totalNumber;
  final int amountCents;
  final DateTime dueDate;
  final DateTime? paidAt;
  final String status; // pending, paid, overdue

  ClinicInstallment({
    required this.id,
    required this.transactionId,
    this.patientName,
    this.description,
    required this.number,
    required this.totalNumber,
    required this.amountCents,
    required this.dueDate,
    this.paidAt,
    required this.status,
  });

  factory ClinicInstallment.fromJson(Map<String, dynamic> json) {
    int parseInt(dynamic value) {
      if (value == null) return 0;
      if (value is int) return value;
      if (value is double) return value.toInt();
      return int.tryParse(value.toString()) ?? 0;
    }

    return ClinicInstallment(
      id: parseInt(json['id']),
      transactionId: parseInt(json['transaction_id']),
      patientName: json['patient_name']?.toString() ?? json['transaction']?['patient_name']?.toString(),
      description: json['description']?.toString() ?? json['transaction']?['description']?.toString(),
      number: parseInt(json['number']),
      totalNumber: parseInt(json['total_number']),
      amountCents: parseInt(json['amount_cents']),
      dueDate: json['due_date'] != null ? DateTime.parse(json['due_date'].toString()) : DateTime.now(),
      paidAt: json['paid_at'] != null ? DateTime.tryParse(json['paid_at'].toString()) : null,
      status: json['status']?.toString() ?? 'pending',
    );
  }

  double get amount => amountCents / 100.0;

  bool get isOverdue {
    if (status == 'paid') return false;
    return dueDate.isBefore(DateTime.now());
  }
}
