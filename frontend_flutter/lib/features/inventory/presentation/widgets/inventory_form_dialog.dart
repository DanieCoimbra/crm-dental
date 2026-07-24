import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/inventory/data/inventory_model.dart';
import 'package:frontend_flutter/features/inventory/data/inventory_repository.dart';
import 'package:frontend_flutter/features/inventory/providers/inventory_provider.dart';

class InventoryFormDialog extends ConsumerStatefulWidget {
  final InventoryItem? item;

  const InventoryFormDialog({super.key, this.item});

  @override
  ConsumerState<InventoryFormDialog> createState() => _InventoryFormDialogState();
}

class _InventoryFormDialogState extends ConsumerState<InventoryFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late String _name;
  late String _sku;
  late double _minQuantity;
  late String _unit;

  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _name = widget.item?.name ?? '';
    _sku = widget.item?.sku ?? '';
    _minQuantity = widget.item?.minQuantity ?? 0.0;
    _unit = widget.item?.unit ?? 'Unidade';
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    _formKey.currentState!.save();

    setState(() => _isLoading = true);

    try {
      final repo = ref.read(inventoryRepositoryProvider);
      final data = {
        'name': _name,
        'sku': _sku,
        'min_quantity': _minQuantity,
        'unit': _unit,
      };

      if (widget.item == null) {
        await repo.createItem(data);
      } else {
        await repo.updateItem(widget.item!.id, data);
      }

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
    return AlertDialog(
      title: Text(widget.item == null ? 'Novo Item de Estoque' : 'Editar Item'),
      content: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                initialValue: _name,
                decoration: const InputDecoration(labelText: 'Nome do Item'),
                validator: (v) => v!.isEmpty ? 'Campo obrigatório' : null,
                onSaved: (v) => _name = v!,
              ),
              const SizedBox(height: 16),
              TextFormField(
                initialValue: _sku,
                decoration: const InputDecoration(labelText: 'SKU (Opcional)'),
                onSaved: (v) => _sku = v ?? '',
              ),
              const SizedBox(height: 16),
              TextFormField(
                initialValue: _minQuantity.toString(),
                decoration: const InputDecoration(labelText: 'Estoque Mínimo'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                validator: (v) => double.tryParse(v ?? '') == null ? 'Número inválido' : null,
                onSaved: (v) => _minQuantity = double.tryParse(v!) ?? 0.0,
              ),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                initialValue: _unit,
                decoration: const InputDecoration(labelText: 'Unidade'),
                items: ['Unidade', 'Caixa', 'Pacote', 'Kit', 'ML', 'Gramas']
                    .map((u) => DropdownMenuItem(value: u, child: Text(u)))
                    .toList(),
                onChanged: (v) => setState(() => _unit = v!),
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
          child: _isLoading ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator()) : const Text('Salvar'),
        ),
      ],
    );
  }
}
