import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';

final dashboardStatsProvider = FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final dio = ref.read(dioProvider);
  final response = await dio.get('/dashboard/stats');
  return response.data;
});
