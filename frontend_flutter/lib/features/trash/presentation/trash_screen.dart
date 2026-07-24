import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/trash/providers/trash_provider.dart';
import 'package:frontend_flutter/features/trash/data/trash_repository.dart';
import 'package:frontend_flutter/shared/widgets/password_confirmation_dialog.dart';

class TrashScreen extends ConsumerStatefulWidget {
  const TrashScreen({super.key});

  @override
  ConsumerState<TrashScreen> createState() => _TrashScreenState();
}

class _TrashScreenState extends ConsumerState<TrashScreen> {
  Future<void> _restoreItem(String type, int id) async {
    try {
      final repo = ref.read(trashRepositoryProvider);
      await repo.restoreItem(type, id);
      ref.invalidate(trashItemsProvider);
      ref.invalidate(trashStatusProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Item restaurado com sucesso!')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro ao restaurar: $e')));
      }
    }
  }

  Future<void> _forceDelete(String type, int id) async {
    final confirmed = await showPasswordConfirmationDialog(
      context: context,
      title: 'Exclusão Permanente',
      message: 'Esta ação não pode ser desfeita. Para confirmar, digite sua senha de administrador:',
      onConfirm: (password) async {
        final repo = ref.read(trashRepositoryProvider);
        await repo.forceDelete(type, id, password);
      },
    );

    if (confirmed == true && mounted) {
      ref.invalidate(trashItemsProvider);
      ref.invalidate(trashStatusProvider);
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Item excluído permanentemente.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final trashItemsAsync = ref.watch(trashItemsProvider);

    return Scaffold(

      appBar: AppBar(
        title: const Text('Lixeira (Soft Deletes)', style: TextStyle(fontWeight: FontWeight.bold)),
        elevation: 0,
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1.0),
          child: Container(color: Theme.of(context).dividerTheme.color, height: 1.0),
        ),
      ),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Gerenciamento de Registros Excluídos',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Theme.of(context).textTheme.titleLarge?.color),
            ),
            const SizedBox(height: 8),
            Text(
              'Restaure itens apagados acidentalmente ou realize a exclusão definitiva seguindo as normas da LGPD.',
              style: TextStyle(color: Theme.of(context).textTheme.bodySmall?.color),
            ),
            const SizedBox(height: 24),
            Expanded(
              child: Card(
                elevation: 0,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                  side: BorderSide(color: Theme.of(context).dividerTheme.color ?? Theme.of(context).colorScheme.outline),
                ),
                child: trashItemsAsync.when(
                  loading: () => const Center(child: CircularProgressIndicator()),
                  error: (e, _) => Center(child: Text('Erro: $e')),
                  data: (items) {
                    if (items.isEmpty) {
                      return const Center(child: Text('Lixeira vazia.'));
                    }

                    return ListView.separated(
                      itemCount: items.length,
                      separatorBuilder: (_, _) => Divider(color: Theme.of(context).dividerTheme.color, height: 1),
                      itemBuilder: (context, index) {
                        final item = items[index];
                        return ListTile(
                          leading: CircleAvatar(
                            backgroundColor: Colors.red.shade50,
                            child: Icon(
                              item.type == 'user' ? LucideIcons.user : 
                              item.type == 'patient' ? LucideIcons.users : LucideIcons.calendar,
                              color: Colors.red,
                            ),
                          ),
                          title: Text(item.title, style: const TextStyle(fontWeight: FontWeight.bold)),
                          subtitle: Text('${item.type} • ${item.subtitle} • Apagado em: ${item.deletedAt?.toLocal().toString().substring(0, 16) ?? "N/A"}'),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              TextButton.icon(
                                onPressed: () => _restoreItem(item.type, item.id),
                                icon: const Icon(LucideIcons.undo2, size: 16),
                                label: Text('Restaurar'),
                              ),
                              const SizedBox(width: 8),
                              ElevatedButton.icon(
                                onPressed: () => _forceDelete(item.type, item.id),
                                icon: const Icon(LucideIcons.trash2, size: 16),
                                label: Text('Excluir Definitivo'),
                                style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                              ),
                            ],
                          ),
                        );
                      },
                    );
                  }
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}




