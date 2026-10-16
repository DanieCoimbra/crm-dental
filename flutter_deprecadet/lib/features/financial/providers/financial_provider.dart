import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/financial/data/financial_model.dart';
import 'package:frontend_flutter/features/financial/data/financial_repository.dart';

class BudgetQueryParams {
  final int? patientId;
  final String? status;

  const BudgetQueryParams({this.patientId, this.status});

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is BudgetQueryParams &&
          runtimeType == other.runtimeType &&
          patientId == other.patientId &&
          status == other.status;

  @override
  int get hashCode => patientId.hashCode ^ status.hashCode;
}

final proceduresListProvider = FutureProvider.autoDispose<List<Procedure>>((ref) async {
  final repo = ref.watch(financialRepositoryProvider);
  return repo.getProcedures();
});

final budgetsListProvider = FutureProvider.autoDispose.family<List<Budget>, BudgetQueryParams>((ref, params) async {
  final repo = ref.watch(financialRepositoryProvider);
  return repo.getBudgets(patientId: params.patientId, status: params.status);
});

final budgetDetailProvider = FutureProvider.autoDispose.family<Budget, int>((ref, id) async {
  final repo = ref.watch(financialRepositoryProvider);
  return repo.getBudgetById(id);
});

final cashFlowSummaryProvider = FutureProvider.autoDispose<CashFlowSummary>((ref) async {
  final repo = ref.watch(financialRepositoryProvider);
  return repo.getCashFlow();
});

final financialTransactionsProvider = FutureProvider.autoDispose<List<ClinicTransaction>>((ref) async {
  final repo = ref.watch(financialRepositoryProvider);
  return repo.getTransactions();
});
