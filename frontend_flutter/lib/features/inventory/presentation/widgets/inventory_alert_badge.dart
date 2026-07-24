import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:go_router/go_router.dart';
import 'package:frontend_flutter/features/inventory/providers/inventory_provider.dart';

class InventoryAlertBadge extends ConsumerWidget {
  const InventoryAlertBadge({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final inventoryAsync = ref.watch(inventoryListProvider);
    final theme = Theme.of(context);

    return inventoryAsync.when(
      data: (items) {
        final lowStockCount = items.where((i) => i.isLowStock).length;

        return Stack(
          alignment: Alignment.center,
          children: [
            IconButton(
              icon: Icon(LucideIcons.bell, color: theme.iconTheme.color),
              onPressed: () {
                if (lowStockCount > 0) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      content: Text('Atenção: $lowStockCount item(ns) com estoque baixo!'),
                      action: SnackBarAction(
                        label: 'Ver',
                        onPressed: () => context.go('/settings?tab=inventory'), // Ou para onde fica o estoque
                      ),
                    ),
                  );
                } else {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Nenhum alerta no momento.')),
                  );
                }
              },
            ),
            if (lowStockCount > 0)
              Positioned(
                top: 8,
                right: 8,
                child: Container(
                  padding: const EdgeInsets.all(4),
                  decoration: const BoxDecoration(
                    color: Colors.red,
                    shape: BoxShape.circle,
                  ),
                  child: Text(
                    lowStockCount.toString(),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 10,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),
          ],
        );
      },
      loading: () => IconButton(
        icon: Icon(LucideIcons.bell, color: theme.iconTheme.color),
        onPressed: () {},
      ),
      error: (_, _) => IconButton(
        icon: Icon(LucideIcons.bell, color: theme.iconTheme.color),
        onPressed: () {},
      ),
    );
  }
}
