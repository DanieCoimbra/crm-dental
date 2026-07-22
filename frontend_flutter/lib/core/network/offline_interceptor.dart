import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:frontend_flutter/core/local_storage/hive_service.dart';

class OfflineInterceptor extends Interceptor {
  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    // Verificar se o erro foi de conexão e a requisição era uma mutação (POST/PUT/DELETE)
    if (_isConnectionError(err) && _isMutation(err.requestOptions.method)) {
      
      final queueBox = HiveService.syncQueueBox;
      
      // Montar a operação para salvar na fila
      final operation = {
        'method': err.requestOptions.method,
        'path': err.requestOptions.path,
        'data': err.requestOptions.data,
        'timestamp': DateTime.now().toIso8601String(),
      };
      
      // Salvar no cofre local
      await queueBox.add(jsonEncode(operation));
      
      // "Fingir" que deu certo para a UI continuar fluindo
      // Isso permite o App funcionar offline graciosamente.
      return handler.resolve(
        Response(
          requestOptions: err.requestOptions,
          statusCode: 200,
          data: {'status': 'queued_offline', ...?(err.requestOptions.data as Map<String, dynamic>?)},
        ),
      );
    }
    
    // Se não for erro de rede ou for GET, deixa o erro fluir normalmente
    return handler.next(err);
  }

  bool _isConnectionError(DioException err) {
    return err.type == DioExceptionType.connectionTimeout ||
           err.type == DioExceptionType.receiveTimeout ||
           err.type == DioExceptionType.sendTimeout ||
           err.type == DioExceptionType.connectionError ||
           err.type == DioExceptionType.unknown; // Em mobile, as vezes sem internet cai como unknown
  }

  bool _isMutation(String method) {
    final m = method.toUpperCase();
    return m == 'POST' || m == 'PUT' || m == 'DELETE' || m == 'PATCH';
  }
}
