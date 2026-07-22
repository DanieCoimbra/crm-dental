import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/inventory/data/inventory_model.dart';
import 'package:frontend_flutter/features/inventory/data/inventory_repository.dart';
import 'package:frontend_flutter/features/inventory/providers/inventory_provider.dart';

class InventoryTransactionDialog extends ConsumerStatefulWidget {
  final InventoryItem item;
  final String type; // 'in' or 'out'

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
  double _quantity = 1.0;
  String _notes = '';
  bool _isLoading = false;

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    _formKey.currentState!.save();

    setState(() => _isLoading = true);

    try {
      final repo = ref.read(inventoryRepositoryProvider);
      
      await repo.registerTransaction(widget.item.id, widget.type, _quantity, _notes);

      ref.invalidate(inventoryListProvider);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final isEntry = widget.type == 'in';
    
    return AlertDialog(
      title: Text(isEntry ? 'Registrar Entrada' : 'Registrar Saída/Consumo'),
      content: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Item: ${widget.item.name}'),
              const SizedBox(height: 16),
              TextFormField(
                initialValue: _quantity.toString(),
                decoration: const InputDecoration(labelText: 'Quantidade'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                validator: (v) {
                  final val = double.tryParse(v ?? '');
                  if (val == null || val <= 0) return 'Quantidade inválida';
                  if (!isEntry && val > widget.item.quantity) return 'Estoque insuficiente';
                  return null;
                },
                onSaved: (v) => _quantity = double.tryParse(v!) ?? 1.0,
              ),
              const SizedBox(height: 16),
              TextFormField(
                decoration: const InputDecoration(
                  labelText: 'Motivo / Observação',
                  hintText: 'Ex: Compra NF 123, Uso em consulta do João...',
                ),
                maxLines: 2,
                onSaved: (v) => _notes = v ?? '',
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancelar'),
        ),
        FilledButton(
          onPressed: _isLoading ? null : _save,
          child: _isLoading ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator()) : const Text('Confirmar'),
        ),
      ],
    );
  }
}
