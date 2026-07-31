import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class InventoryAlertBadge extends ConsumerWidget {
  const InventoryAlertBadge({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return const Icon(Icons.inventory_2_outlined, color: Colors.grey);
  }
}
