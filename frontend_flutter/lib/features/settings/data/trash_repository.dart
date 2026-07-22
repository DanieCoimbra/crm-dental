import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';

final trashRepositoryProvider = Provider<TrashRepository>((ref) {
  return TrashRepository(ref.read(dioProvider));
});

class TrashRepository {
  final Dio _dio;

  TrashRepository(this._dio);

  Future<Map<String, dynamic>> getTrash() async {
    final response = await _dio.get('/trash');
    return response.data;
  }

  Future<Map<String, dynamic>> getTrashStatus() async {
    final response = await _dio.get('/trash/status');
    return response.data;
  }

  Future<void> restoreItem(String type, int id) async {
    await _dio.post('/trash/$type/$id/restore');
  }

  Future<void> forceDeleteItem(String type, int id, String password) async {
    await _dio.delete('/trash/$type/$id/force', data: {'password': password});
  }
}
