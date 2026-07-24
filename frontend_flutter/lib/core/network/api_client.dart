import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/offline_interceptor.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';

const _storage = FlutterSecureStorage();

const String _rawBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'https://crm-clinica-gjss.onrender.com',
);

String get backendBaseUrl {
  var url = _rawBaseUrl;
  if (url.endsWith('/api/v1')) {
    url = url.substring(0, url.length - '/api/v1'.length);
  }
  if (url.endsWith('/')) {
    url = url.substring(0, url.length - 1);
  }
  return url;
}

String get apiBaseUrl => '$backendBaseUrl/api/v1';

final dioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: apiBaseUrl,
    connectTimeout: const Duration(seconds: 30),
    receiveTimeout: const Duration(seconds: 30),
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
