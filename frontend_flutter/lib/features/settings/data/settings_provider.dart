import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/settings/data/clinic_model.dart';
import 'package:frontend_flutter/features/settings/data/user_model.dart';
import 'package:frontend_flutter/features/settings/data/role_model.dart';
import 'package:frontend_flutter/features/settings/data/room_model.dart';
import 'package:frontend_flutter/features/settings/data/setting_model.dart';
import 'package:frontend_flutter/features/settings/data/settings_repository.dart';

final FutureProvider<Clinic> myClinicProvider = FutureProvider<Clinic>((ref) {
  final repository = ref.watch(settingsRepositoryProvider);
  return repository.getMyClinic();
});

final usersProvider = FutureProvider<List<User>>((ref) {
  final repository = ref.watch(settingsRepositoryProvider);
  return repository.getUsers();
});

final rolesProvider = FutureProvider<List<Role>>((ref) {
  final repository = ref.watch(settingsRepositoryProvider);
  return repository.getRoles();
});

final permissionsProvider = FutureProvider<List<String>>((ref) {
  final repository = ref.watch(settingsRepositoryProvider);
  return repository.getPermissions();
});

final roomsProvider = FutureProvider<List<Room>>((ref) {
  final repository = ref.watch(settingsRepositoryProvider);
  return repository.getRooms();
});

final appSettingsProvider = FutureProvider<List<AppSetting>>((ref) {
  final repository = ref.watch(settingsRepositoryProvider);
  return repository.getSettings();
});

final clinicHolidaysProvider = FutureProvider<List<dynamic>>((ref) {
  final repository = ref.watch(settingsRepositoryProvider);
  return repository.getHolidays();
});

final auditLogsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) {
  final repository = ref.watch(settingsRepositoryProvider);
  return repository.getAuditLogs();
});
