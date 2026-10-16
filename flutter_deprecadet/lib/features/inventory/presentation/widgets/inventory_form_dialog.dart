import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/inventory/providers/inventory_provider.dart';
import 'package:frontend_flutter/features/inventory/data/inventory_repository.dart';

class InventoryFormDialog extends ConsumerStatefulWidget {
  final Map<String, dynamic>? item;

  const InventoryFormDialog({super.key, this.item});

  @override
  ConsumerState<InventoryFormDialog> createState() => _InventoryFormDialogState();
}

class _InventoryFormDialogState extends ConsumerState<InventoryFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _nameController;
  late TextEditingController _categoryController;
  late TextEditingController _quantityController;
  late TextEditingController _minQuantityController;
  late TextEditingController _unitController;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.item?['name'] ?? '');
    _categoryController = TextEditingController(text: widget.item?['category'] ?? 'Geral');
    _quantityController = TextEditingController(text: widget.item?['quantity']?.toString() ?? '0');
    _minQuantityController = TextEditingController(text: widget.item?['min_quantity']?.toString() ?? '5');
    _unitController = TextEditingController(text: widget.item?['unit'] ?? 'un');
  }

  @override
  void dispose() {
    _nameController.dispose();
    _categoryController.dispose();
    _quantityController.dispose();
    _minQuantityController.dispose();
    _unitController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isLoading = true);

    try {
      final repo = ref.read(inventoryRepositoryProvider);
      final data = {
        'name': _nameController.text.trim(),
        'category': _categoryController.text.trim(),
        'quantity': int.parse(_quantityController.text.trim()),
        'min_quantity': int.parse(_minQuantityController.text.trim()),
        'unit': _unitController.text.trim(),
      };

      if (widget.item != null && widget.item!['id'] != null) {
        final id = int.parse(widget.item!['id'].toString());
        await repo.updateItem(id, data);
      } else {
        await repo.createItem(data);
      }

      ref.invalidate(inventoryListProvider);
      if (mounted) {
        Navigator.of(context).pop(true);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao salvar item: $e'), backgroundColor: Colors.red),
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final isEditing = widget.item != null;

    return AlertDialog(
      title: Text(isEditing ? 'Editar Item do Estoque' : 'Novo Item do Estoque'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _nameController,
                decoration: const InputDecoration(labelText: 'Nome do Item *', border: OutlineInputBorder()),
                validator: (val) => val == null || val.trim().isEmpty ? 'Informe o nome' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _categoryController,
                decoration: const InputDecoration(labelText: 'Categoria', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: TextFormField(
                      controller: _quantityController,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(labelText: 'Quantidade *', border: OutlineInputBorder()),
                      validator: (val) => val == null || int.tryParse(val) == null ? 'Inválido' : null,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      controller: _minQuantityController,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(labelText: 'Mínimo *', border: OutlineInputBorder()),
                      validator: (val) => val == null || int.tryParse(val) == null ? 'Inválido' : null,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _unitController,
                decoration: const InputDecoration(labelText: 'Unidade (ex: cx, un, ml)', border: OutlineInputBorder()),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isLoading ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancelar'),
        ),
        ElevatedButton(
          onPressed: _isLoading ? null : _submit,
          child: _isLoading ? const CircularProgressIndicator() : Text(isEditing ? 'Atualizar' : 'Salvar'),
        ),
      ],
    );
  }
}
