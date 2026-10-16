import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:frontend_flutter/core/local_storage/hive_service.dart';

class SyncWorker {
  final Dio _dio;
  bool _isSyncing = false;

  SyncWorker(this._dio);

  Future<void> syncOfflineQueue() async {
    if (_isSyncing) return;
    
    final queueBox = HiveService.syncQueueBox;
    if (queueBox.isEmpty) return;

    _isSyncing = true;
    debugPrint('Iniciando sincronização offline de ${queueBox.length} itens...');

    final keys = queueBox.keys.toList();
    for (final key in keys) {
      try {
        final item = queueBox.get(key);
        if (item == null) continue;
        
        final operation = jsonDecode(item) as Map<String, dynamic>;
        
        final method = operation['method'] as String;
        final path = operation['path'] as String;
        final data = operation['data'];
        // o timestamp poderia ser enviado no payload para auditoria exata no backend
        // mas aqui vamos só refazer a requisição
        
        debugPrint('Sincronizando $method $path');

        await _dio.request(
          path,
          data: data,
          options: Options(method: method),
        );
        
        // Se a requisição deu sucesso, remove da fila local
        await queueBox.delete(key);
      } catch (e) {
        debugPrint('Falha ao sincronizar item $key: $e');
        // Se der erro de conexão de novo, a gente para o loop e tenta depois
        if (e is DioException && (e.type == DioExceptionType.connectionError || e.type == DioExceptionType.unknown)) {
          break; 
        }
        // Se for erro 4xx ou 5xx (erro real de regra de negocio), removemos da fila para não travar o loop
        // ou guardamos em outra box de "falhas". Para o MVP, deletamos para não travar.
        await queueBox.delete(key);
      }
    }

    _isSyncing = false;
    debugPrint('Sincronização offline concluída.');
  }
}
