import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/inventory/providers/inventory_provider.dart';
import 'package:frontend_flutter/features/inventory/data/inventory_repository.dart';

class InventoryTransactionDialog extends ConsumerStatefulWidget {
  final Map<String, dynamic> item;
  final String type; // 'in' ou 'out'

  const InventoryTransactionDialog({
    super.key,
    required this.item,
    required this.type,
  });

  @override
  ConsumerState<InventoryTransactionDialog> createState() => _InventoryTransactionDialogState();
}

class _InventoryTransactionDialogState extends ConsumerState<InventoryTransactionDialog> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _quantityController;
  late TextEditingController _notesController;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _quantityController = TextEditingController(text: '1');
    _notesController = TextEditingController();
  }

  @override
  void dispose() {
    _quantityController.dispose();
    _notesController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isLoading = true);

    try {
      final repo = ref.read(inventoryRepositoryProvider);
      final qty = int.parse(_quantityController.text.trim());
      final itemId = int.parse(widget.item['id'].toString());

      await repo.registerTransaction(
        itemId,
        widget.type,
        qty.toDouble(),
        _notesController.text.trim(),
      );

      ref.invalidate(inventoryListProvider);
      if (mounted) {
        Navigator.of(context).pop(true);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao registrar movimentação: $e'), backgroundColor: Colors.red),
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final isIn = widget.type == 'in';
    final title = isIn ? 'Entrada de Estoque' : 'Saída de Estoque';

    return AlertDialog(
      title: Text('$title - ${widget.item['name']}'),
      content: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextFormField(
              controller: _quantityController,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Quantidade *', border: OutlineInputBorder()),
              validator: (val) {
                final n = int.tryParse(val ?? '');
                if (n == null || n <= 0) return 'Informe uma quantidade válida';
                return null;
              },
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _notesController,
              decoration: const InputDecoration(labelText: 'Observação / Motivo', border: OutlineInputBorder()),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isLoading ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancelar'),
        ),
        ElevatedButton(
          onPressed: _isLoading ? null : _submit,
          style: ElevatedButton.styleFrom(
            backgroundColor: isIn ? Colors.green : Colors.orange,
            foregroundColor: Colors.white,
          ),
          child: _isLoading ? const CircularProgressIndicator() : const Text('Confirmar'),
        ),
      ],
    );
  }
}
