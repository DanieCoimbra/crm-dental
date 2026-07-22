import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/financial/data/financial_model.dart';
import 'package:frontend_flutter/features/financial/data/financial_repository.dart';

final financialTransactionsProvider = FutureProvider.autoDispose<List<ClinicTransaction>>((ref) async {
  final repo = ref.watch(financialRepositoryProvider);
  return repo.getTransactions();
});
