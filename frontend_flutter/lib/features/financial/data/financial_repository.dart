import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/financial/data/financial_model.dart';

final financialRepositoryProvider = Provider<FinancialRepository>((ref) {
  return FinancialRepository(ref.read(dioProvider));
});

class FinancialRepository {
  final Dio _dio;

  FinancialRepository(this._dio);

  Future<List<ClinicTransaction>> getTransactions({String? type, String? status}) async {
    final queryParams = <String, dynamic>{};
    if (type != null && type.isNotEmpty) queryParams['type'] = type;
    if (status != null && status.isNotEmpty) queryParams['status'] = status;

    final response = await _dio.get('/financial/transactions', queryParameters: queryParams);
    final List data = response.data;
    return data.map((json) => ClinicTransaction.fromJson(json)).toList();
  }

  Future<ClinicTransaction> createTransaction({
    required String type,
    required String category,
    required String description,
    required int totalAmountCents,
    required String paymentMethod,
    required String dueDate,
    required int totalInstallments,
    int? patientId,
  }) async {
    final response = await _dio.post('/financial/transactions', data: {
      'type': type,
      'category': category,
      'description': description,
      'total_amount_cents': totalAmountCents,
      'payment_method': paymentMethod,
      'due_date': dueDate,
      'total_installments': totalInstallments,
      'patient_id': ?patientId,
    });
    return ClinicTransaction.fromJson(response.data);
  }

  Future<void> payInstallment(int installmentId) async {
    await _dio.put('/financial/installments/$installmentId/pay');
  }
}
