import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/inventory/providers/inventory_provider.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

class InventoryScreen extends ConsumerWidget {
  const InventoryScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final inventoryAsync = ref.watch(inventoryListProvider);
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Estoque'),
        scrolledUnderElevation: 0,
      ),
      // Floating action button removed until InventoryFormDialog is implemented
      body: inventoryAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Erro: $e')),
        data: (items) {
          if (items.isEmpty) {
            return const Center(child: Text('Nenhum item cadastrado no estoque.'));
          }

          // Sort so low stock is at top
          final sortedItems = List.of(items)
            ..sort((a, b) {
              if (a.isLowStock && !b.isLowStock) return -1;
              if (!a.isLowStock && b.isLowStock) return 1;
              return a.name.compareTo(b.name);
            });

          return ListView.builder(
            padding: const EdgeInsets.all(24),
            itemCount: sortedItems.length,
            itemBuilder: (context, index) {
              final item = sortedItems[index];

              return Card(
                margin: const EdgeInsets.only(bottom: 12),
                child: ListTile(
                  leading: CircleAvatar(
                    backgroundColor: item.isLowStock 
                      ? Colors.red.withValues(alpha: 0.2) 
                      : Colors.blue.withValues(alpha: 0.2),
                    child: Icon(
                      item.isLowStock ? LucideIcons.alertTriangle : LucideIcons.package,
                      color: item.isLowStock ? Colors.red : Colors.blue,
                    ),
                  ),
                  title: Text(item.name, style: const TextStyle(fontWeight: FontWeight.bold)),
                  subtitle: Text('SKU: ${item.sku} • Estoque Mínimo: ${item.minQuantity.toStringAsFixed(1)} ${item.unit}'),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            '${item.quantity.toStringAsFixed(1)} ${item.unit}',
                            style: TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.bold,
                              color: item.isLowStock ? Colors.red : theme.textTheme.bodyLarge?.color,
                            ),
                          ),
                          if (item.isLowStock)
                            const Text('Estoque Baixo', style: TextStyle(color: Colors.red, fontSize: 10, fontWeight: FontWeight.bold)),
                        ],
                      ),
                    ],
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }
}
