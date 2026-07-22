import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/core/network/api_client.dart';
import 'package:frontend_flutter/core/network/sync_worker.dart';

enum NetworkStatus { online, offline }

class NetworkStatusNotifier extends Notifier<NetworkStatus> {
  final Connectivity _connectivity = Connectivity();

  @override
  NetworkStatus build() {
    _init();
    _connectivity.onConnectivityChanged.listen((List<ConnectivityResult> results) {
      final hasConnection = !results.contains(ConnectivityResult.none);
      final newStatus = hasConnection ? NetworkStatus.online : NetworkStatus.offline;
      
      if (newStatus == NetworkStatus.online && state == NetworkStatus.offline) {
        final syncWorker = SyncWorker(ref.read(dioProvider));
        syncWorker.syncOfflineQueue();
      }
      
      state = newStatus;
    });
    return NetworkStatus.online;
  }

  Future<void> _init() async {
    final results = await _connectivity.checkConnectivity();
    final hasConnection = !results.contains(ConnectivityResult.none);
    state = hasConnection ? NetworkStatus.online : NetworkStatus.offline;
  }
}

final networkStatusProvider = NotifierProvider<NetworkStatusNotifier, NetworkStatus>(() {
  return NetworkStatusNotifier();
});
