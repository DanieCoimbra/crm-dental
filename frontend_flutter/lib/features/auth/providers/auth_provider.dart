import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:frontend_flutter/features/auth/data/auth_repository.dart';
import 'package:frontend_flutter/features/settings/data/user_model.dart';
import 'package:frontend_flutter/features/settings/data/clinic_model.dart';
import 'package:frontend_flutter/core/network/api_client.dart';

const _storage = FlutterSecureStorage();

class AuthState {
  final bool isLoading;
  final bool isAuthenticated;
  final String role; // 'admin', 'doctor', 'receptionist'
  
  AuthState({
    required this.isLoading,
    required this.isAuthenticated,
    this.role = 'admin',
  });
}

class AuthNotifier extends Notifier<AuthState> {
  @override
  AuthState build() {
    _checkAuth();
    return AuthState(isLoading: true, isAuthenticated: false);
  }

  Future<void> _checkAuth() async {
    final token = await _storage.read(key: 'jwt_token');
    final role = await _storage.read(key: 'user_role') ?? 'admin';
    state = AuthState(isLoading: false, isAuthenticated: token != null, role: role);
  }

  Future<void> login(String token, String role) async {
    await _storage.write(key: 'jwt_token', value: token);
    await _storage.write(key: 'user_role', value: role); 
    state = AuthState(isLoading: false, isAuthenticated: true, role: role);
  }

  Future<void> switchRole(String newRole) async {
    await _storage.write(key: 'user_role', value: newRole);
    state = AuthState(isLoading: false, isAuthenticated: state.isAuthenticated, role: newRole);
  }

  Future<void> logout() async {
    await _storage.delete(key: 'jwt_token');
    await _storage.delete(key: 'user_role');
    state = AuthState(isLoading: false, isAuthenticated: false, role: 'admin');
  }
}

final authProvider = NotifierProvider<AuthNotifier, AuthState>(() {
  return AuthNotifier();
});

final currentUserProvider = FutureProvider<User>((ref) {
  final repository = ref.watch(authRepositoryProvider);
  return repository.getProfile();
});

final currentClinicProvider = FutureProvider<Clinic>((ref) async {
  // Use o settingsRepositoryProvider, mas ele não está importado.
  // Vou importar ou usar dio diretamente
  final dio = ref.watch(dioProvider);
  final response = await dio.get('/clinics/me');
  return Clinic.fromJson(response.data);
});
