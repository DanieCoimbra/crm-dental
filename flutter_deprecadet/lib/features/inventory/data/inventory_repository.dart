import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/features/inventory/data/inventory_model.dart';

final inventoryRepositoryProvider = Provider<InventoryRepository>((ref) {
  return InventoryRepository(ref.read(dioProvider));
});

class InventoryRepository {
  final Dio _dio;

  InventoryRepository(this._dio);

  Future<List<InventoryItem>> getItems() async {
    final response = await _dio.get('/inventory');
    if (response.data == null || response.data == '') return [];
    return (response.data as List)
        .map((json) => InventoryItem.fromJson(json))
        .toList();
  }

  Future<InventoryItem> createItem(Map<String, dynamic> data) async {
    final response = await _dio.post('/inventory', data: data);
    return InventoryItem.fromJson(response.data);
  }

  Future<InventoryItem> updateItem(int id, Map<String, dynamic> data) async {
    final response = await _dio.put('/inventory/$id', data: data);
    return InventoryItem.fromJson(response.data);
  }

  Future<void> deleteItem(int id) async {
    await _dio.delete('/inventory/$id');
  }

  Future<void> registerTransaction(int itemId, String type, double quantity, String notes) async {
    await _dio.post('/inventory/$itemId/transactions', data: {
      'type': type,
      'quantity': quantity,
      'notes': notes,
    });
  }

  Future<List<InventoryTransaction>> getTransactions(int itemId) async {
    final response = await _dio.get('/inventory/$itemId/transactions');
    if (response.data == null || response.data == '') return [];
    return (response.data as List)
        .map((json) => InventoryTransaction.fromJson(json))
        .toList();
  }
}
