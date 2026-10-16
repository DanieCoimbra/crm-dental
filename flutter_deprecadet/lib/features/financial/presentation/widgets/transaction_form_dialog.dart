import 'package:flutter/material.dart';

class TransactionFormDialog extends StatefulWidget {
  final Map<String, dynamic>? transaction;

  const TransactionFormDialog({super.key, this.transaction});

  @override
  State<TransactionFormDialog> createState() => _TransactionFormDialogState();
}

class _TransactionFormDialogState extends State<TransactionFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _descriptionController;
  late TextEditingController _amountController;
  late TextEditingController _categoryController;
  String _type = 'income';

  @override
  void initState() {
    super.initState();
    _descriptionController = TextEditingController(text: widget.transaction?['description'] ?? '');
    _amountController = TextEditingController(text: widget.transaction?['amount']?.toString() ?? '');
    _categoryController = TextEditingController(text: widget.transaction?['category'] ?? 'Geral');
    _type = widget.transaction?['type'] ?? 'income';
  }

  @override
  void dispose() {
    _descriptionController.dispose();
    _amountController.dispose();
    _categoryController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isEditing = widget.transaction != null;

    return AlertDialog(
      title: Text(isEditing ? 'Editar Lançamento' : 'Novo Lançamento Financeiro'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _type,
                items: const [
                  DropdownMenuItem(value: 'income', child: Text('Receita (+)')),
                  DropdownMenuItem(value: 'expense', child: Text('Despesa (-)')),
                ],
                onChanged: (val) => setState(() => _type = val ?? 'income'),
                decoration: const InputDecoration(labelText: 'Tipo', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _descriptionController,
                decoration: const InputDecoration(labelText: 'Descrição *', border: OutlineInputBorder()),
                validator: (val) => val == null || val.trim().isEmpty ? 'Informe a descrição' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _amountController,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Valor', border: OutlineInputBorder()),
                validator: (val) => val == null || double.tryParse(val) == null ? 'Valor inválido' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _categoryController,
                decoration: const InputDecoration(labelText: 'Categoria', border: OutlineInputBorder()),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancelar'),
        ),
        ElevatedButton(
          onPressed: () {
            if (_formKey.currentState!.validate()) {
              Navigator.of(context).pop(true);
            }
          },
          child: Text(isEditing ? 'Atualizar' : 'Salvar'),
        ),
      ],
    );
  }
}
