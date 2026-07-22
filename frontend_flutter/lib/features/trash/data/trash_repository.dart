import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/trash/data/trash_model.dart';

final trashRepositoryProvider = Provider<TrashRepository>((ref) {
  return TrashRepository(ref.read(dioProvider));
});

class TrashRepository {
  final Dio _dio;

  TrashRepository(this._dio);

  Future<TrashStatus> getStatus() async {
    final response = await _dio.get('/trash/status');
    return TrashStatus.fromJson(response.data);
  }

  Future<List<TrashItem>> getTrashItems() async {
    final response = await _dio.get('/trash');
    final Map<String, dynamic> data = response.data;
    
    final List<TrashItem> items = [];

    if (data['users'] != null) {
      for (var u in data['users']) {
        items.add(TrashItem(
          id: u['id'],
          type: 'user',
          title: u['name'] ?? 'Usuário',
          subtitle: u['email'] ?? '',
          deletedAt: u['deleted_at'] != null ? DateTime.tryParse(u['deleted_at']['time']) : null,
        ));
      }
    }

    if (data['patients'] != null) {
      for (var p in data['patients']) {
        items.add(TrashItem(
          id: p['id'],
          type: 'patient',
          title: p['name'] ?? 'Paciente',
          subtitle: p['cpf'] ?? '',
          deletedAt: p['deleted_at'] != null ? DateTime.tryParse(p['deleted_at']['time']) : null,
        ));
      }
    }

    if (data['appointments'] != null) {
      for (var a in data['appointments']) {
        items.add(TrashItem(
          id: a['id'],
          type: 'appointment',
          title: 'Agendamento ID: ${a['id']}',
          subtitle: a['start_time'] ?? '',
          deletedAt: a['deleted_at'] != null ? DateTime.tryParse(a['deleted_at']['time']) : null,
        ));
      }
    }

    return items;
  }

  Future<void> restoreItem(String type, int id) async {
    await _dio.post('/trash/$type/$id/restore');
  }

  Future<void> forceDelete(String type, int id, String password) async {
    await _dio.delete(
      '/trash/$type/$id/force',
      data: {'password': password},
    );
  }
}
