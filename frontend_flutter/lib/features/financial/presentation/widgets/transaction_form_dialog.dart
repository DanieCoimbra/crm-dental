import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/financial/providers/financial_provider.dart';
import 'package:frontend_flutter/features/financial/data/financial_repository.dart';

class TransactionFormDialog extends ConsumerStatefulWidget {
  const TransactionFormDialog({super.key});

  @override
  ConsumerState<TransactionFormDialog> createState() => _TransactionFormDialogState();
}

class _TransactionFormDialogState extends ConsumerState<TransactionFormDialog> {
  final _formKey = GlobalKey<FormState>();
  final _descController = TextEditingController();
  final _amountController = TextEditingController();

  String _type = 'income';
  String _category = 'Procedimento';
  String _paymentMethod = 'Pix';
  int _installments = 1;
  DateTime _dueDate = DateTime.now();

  @override
  void dispose() {
    _descController.dispose();
    _amountController.dispose();
    super.dispose();
  }

  void _submit() async {
    if (!_formKey.currentState!.validate()) return;
    
    final amountDouble = double.tryParse(_amountController.text.replaceAll(',', '.')) ?? 0;
    final amountCents = (amountDouble * 100).toInt();

    if (amountCents <= 0) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Valor deve ser maior que zero')));
      return;
    }

    try {
      await ref.read(financialRepositoryProvider).createTransaction(
        type: _type,
        category: _category,
        description: _descController.text,
        totalAmountCents: amountCents,
        paymentMethod: _paymentMethod,
        dueDate: _dueDate.toIso8601String().split('T')[0],
        totalInstallments: _installments,
      );
      ref.invalidate(financialTransactionsProvider);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Novo Lançamento'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DropdownButtonFormField<String>(
                  initialValue: _type,
                  decoration: const InputDecoration(labelText: 'Tipo'),
                  items: const [
                    DropdownMenuItem(value: 'income', child: Text('Receita (Entrada)')),
                    DropdownMenuItem(value: 'expense', child: Text('Despesa (Saída)')),
                  ],
                  onChanged: (val) => setState(() {
                    _type = val!;
                    _category = _type == 'income' ? 'Procedimento' : 'Aluguel';
                  }),
                ),
                const SizedBox(height: 16),
                DropdownButtonFormField<String>(
                  initialValue: _category,
                  decoration: const InputDecoration(labelText: 'Categoria'),
                  items: _type == 'income'
                      ? ['Procedimento', 'Venda', 'Outros'].map((c) => DropdownMenuItem(value: c, child: Text(c))).toList()
                      : ['Aluguel', 'Salário', 'Material', 'Luz/Água', 'Outros'].map((c) => DropdownMenuItem(value: c, child: Text(c))).toList(),
                  onChanged: (val) => setState(() => _category = val!),
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _descController,
                  decoration: const InputDecoration(labelText: 'Descrição'),
                  validator: (val) => val == null || val.isEmpty ? 'Campo obrigatório' : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _amountController,
                  decoration: const InputDecoration(labelText: 'Valor (R\$)', prefixText: 'R\$ '),
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  validator: (val) => val == null || val.isEmpty ? 'Campo obrigatório' : null,
                ),
                const SizedBox(height: 16),
                DropdownButtonFormField<String>(
                  initialValue: _paymentMethod,
                  decoration: const InputDecoration(labelText: 'Forma de Pagamento'),
                  items: ['Pix', 'Dinheiro', 'Cartão de Crédito', 'Cartão de Débito', 'Boleto']
                      .map((p) => DropdownMenuItem(value: p, child: Text(p))).toList(),
                  onChanged: (val) => setState(() => _paymentMethod = val!),
                ),
                const SizedBox(height: 16),
                DropdownButtonFormField<int>(
                  initialValue: _installments,
                  decoration: const InputDecoration(labelText: 'Parcelamento (Qtd)'),
                  items: List.generate(12, (i) => i + 1).map((i) => DropdownMenuItem(value: i, child: Text('${i}x'))).toList(),
                  onChanged: (val) => setState(() => _installments = val!),
                ),
                const SizedBox(height: 16),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Data do Primeiro Vencimento'),
                  subtitle: Text('${_dueDate.day}/${_dueDate.month}/${_dueDate.year}'),
                  trailing: const Icon(Icons.calendar_today),
                  onTap: () async {
                    final date = await showDatePicker(
                      context: context,
                      initialDate: _dueDate,
                      firstDate: DateTime(2000),
                      lastDate: DateTime(2100),
                    );
                    if (date != null) setState(() => _dueDate = date);
                  },
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancelar'),
        ),
        ElevatedButton(
          onPressed: _submit,
          child: const Text('Salvar'),
        ),
      ],
    );
  }
}
