import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/offline_interceptor.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';

const _storage = FlutterSecureStorage();

final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: 'http://localhost:8080/api/v1',
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 10),
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
  ));

  dio.interceptors.add(OfflineInterceptor());

  dio.interceptors.add(InterceptorsWrapper(
    onRequest: (options, handler) async {
      final token = await _storage.read(key: 'jwt_token');
      if (token != null) {
        options.headers['Authorization'] = 'Bearer $token';
      }
      return handler.next(options);
    },
    onError: (DioException e, handler) async {
      if (e.response?.statusCode == 401) {
        await _storage.delete(key: 'jwt_token');
        ref.read(authProvider.notifier).logout();
      }
      return handler.next(e);
    },
  ));

  return dio;
});
