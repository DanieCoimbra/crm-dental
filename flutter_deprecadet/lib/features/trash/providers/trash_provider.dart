import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';
import 'package:frontend_flutter/features/trash/data/trash_model.dart';
import 'package:frontend_flutter/features/trash/data/trash_repository.dart';

final trashStatusProvider = FutureProvider<TrashStatus>((ref) {
  final auth = ref.watch(authProvider);
  if (auth.isLoading || (auth.role != 'admin' && auth.role != 'manager' && auth.role != 'owner')) {
    return TrashStatus(isFull: false, isAlmostFull: false, total: 0, limit: 1000);
  }
  final repository = ref.watch(trashRepositoryProvider);
  return repository.getStatus();
});

final trashItemsProvider = FutureProvider<List<TrashItem>>((ref) {
  final auth = ref.watch(authProvider);
  if (auth.isLoading || (auth.role != 'admin' && auth.role != 'manager' && auth.role != 'owner')) {
    return [];
  }
  final repository = ref.watch(trashRepositoryProvider);
  return repository.getTrashItems();
});
