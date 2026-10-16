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

  // --- Procedures ---
  Future<List<Procedure>> getProcedures() async {
    final response = await _dio.get('/procedures');
    final List data = response.data;
    return data.map((json) => Procedure.fromJson(json)).toList();
  }

  Future<Procedure> createProcedure({
    required String name,
    String? description,
    required int basePriceCents,
    int durationMinutes = 30,
    String color = '#2563EB',
  }) async {
    final response = await _dio.post('/procedures', data: {
      'name': name,
      'description': description,
      'base_price_cents': basePriceCents,
      'duration_minutes': durationMinutes,
      'color': color,
    });
    return Procedure.fromJson(response.data);
  }

  Future<Procedure> updateProcedure(
    int id, {
    required String name,
    String? description,
    required int basePriceCents,
    int durationMinutes = 30,
    String color = '#2563EB',
  }) async {
    final response = await _dio.put('/procedures/$id', data: {
      'name': name,
      'description': description,
      'base_price_cents': basePriceCents,
      'duration_minutes': durationMinutes,
      'color': color,
    });
    return Procedure.fromJson(response.data);
  }

  Future<void> deleteProcedure(int id) async {
    await _dio.delete('/procedures/$id');
  }

  // --- Budgets ---
  Future<List<Budget>> getBudgets({int? patientId, String? status}) async {
    final queryParams = <String, dynamic>{};
    if (patientId != null) queryParams['patient_id'] = patientId;
    if (status != null && status.isNotEmpty) queryParams['status'] = status;

    final response = await _dio.get('/budgets', queryParameters: queryParams);
    final List data = response.data;
    return data.map((json) => Budget.fromJson(json)).toList();
  }

  Future<Budget> getBudgetById(int id) async {
    final response = await _dio.get('/budgets/$id');
    return Budget.fromJson(response.data);
  }

  Future<Budget> createBudget({
    required int patientId,
    int? dentistId,
    required int totalAmountCents,
    required int discountCents,
    required int finalAmountCents,
    String status = 'draft',
    String? notes,
    required List<Map<String, dynamic>> items,
  }) async {
    final response = await _dio.post('/budgets', data: {
      'patient_id': patientId,
      'dentist_id': ?dentistId,
      'total_amount_cents': totalAmountCents,
      'discount_cents': discountCents,
      'final_amount_cents': finalAmountCents,
      'status': status,
      'notes': notes,
      'items': items,
    });
    return Budget.fromJson(response.data);
  }

  Future<void> approveBudget(
    int id, {
    int installmentsCount = 1,
    String paymentMethod = 'PIX',
  }) async {
    await _dio.post('/budgets/$id/approve', data: {
      'installments_count': installmentsCount,
      'total_installments': installmentsCount,
      'payment_method': paymentMethod,
    });
  }

  Future<void> rejectBudget(int id) async {
    await _dio.post('/budgets/$id/reject');
  }

  // --- Financial & Cash Flow ---
  Future<List<ClinicTransaction>> getTransactions({String? type, String? status}) async {
    final queryParams = <String, dynamic>{};
    if (type != null && type.isNotEmpty) queryParams['type'] = type;
    if (status != null && status.isNotEmpty) queryParams['status'] = status;

    final response = await _dio.get('/financial/transactions', queryParameters: queryParams);
    final List data = response.data;
    return data.map((json) => ClinicTransaction.fromJson(json)).toList();
  }

  Future<CashFlowSummary> getCashFlow() async {
    final response = await _dio.get('/financial/cash-flow');
    return CashFlowSummary.fromJson(response.data);
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

  Future<void> payInstallment(int installmentId, {String paymentMethod = 'PIX'}) async {
    try {
      await _dio.post('/financial/installments/$installmentId/pay', data: {
        'payment_method': paymentMethod,
      });
    } on DioException catch (e) {
      if (e.response?.statusCode == 404 || e.response?.statusCode == 405) {
        await _dio.put('/financial/installments/$installmentId/pay', data: {
          'payment_method': paymentMethod,
        });
      } else {
        rethrow;
      }
    }
  }
}
