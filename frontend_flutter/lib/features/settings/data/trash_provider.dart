import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/settings/data/trash_repository.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';

final trashProvider = FutureProvider<Map<String, dynamic>>((ref) async {
  final repo = ref.watch(trashRepositoryProvider);
  return repo.getTrash();
});

final trashStatusProvider = FutureProvider<Map<String, dynamic>>((ref) async {
  final auth = ref.watch(authProvider);
  if (auth.role != 'admin' && auth.role != 'manager' && auth.role != 'owner') {
    return {'total_items': 0, 'categories': {}};
  }
  final repo = ref.watch(trashRepositoryProvider);
  return repo.getTrashStatus();
});
