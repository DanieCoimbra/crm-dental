import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/financial/data/financial_model.dart';
import 'package:frontend_flutter/features/financial/data/financial_repository.dart';
import 'package:frontend_flutter/features/financial/providers/financial_provider.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';

class BudgetFormDialog extends ConsumerStatefulWidget {
  final int? initialPatientId;
  final int? initialToothNumber;
  final String? initialFace;

  const BudgetFormDialog({
    super.key,
    this.initialPatientId,
    this.initialToothNumber,
    this.initialFace,
  });

  @override
  ConsumerState<BudgetFormDialog> createState() => _BudgetFormDialogState();
}

class _BudgetFormDialogState extends ConsumerState<BudgetFormDialog> {
  final _formKey = GlobalKey<FormState>();
  int? _selectedPatientId;
  final TextEditingController _notesController = TextEditingController();
  final TextEditingController _discountController = TextEditingController(text: '0,00');

  final List<BudgetItem> _items = [];
  bool _isSaving = false;

  @override
  void initState() {
    super.initState();
    _selectedPatientId = widget.initialPatientId;
  }

  @override
  void dispose() {
    _notesController.dispose();
    _discountController.dispose();
    super.dispose();
  }

  int get _subtotalCents => _items.fold(0, (sum, item) => sum + item.subtotalCents);

  int get _discountCents {
    final text = _discountController.text.trim().replaceAll(',', '.');
    final val = double.tryParse(text) ?? 0.0;
    return (val * 100).round();
  }

  int get _finalAmountCents {
    final net = _subtotalCents - _discountCents;
    return net < 0 ? 0 : net;
  }

  @override
  Widget build(BuildContext context) {
    final patientsAsync = ref.watch(patientsListProvider(''));
    final proceduresAsync = ref.watch(proceduresListProvider);
    final currencyFormatter = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');
    final theme = Theme.of(context);

    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
      child: Container(
        width: 720,
        constraints: const BoxConstraints(maxHeight: 800),
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Header
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'Novo Orçamento',
                    style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold),
                  ),
                  IconButton(
                    icon: const Icon(LucideIcons.x),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const Divider(height: 24),

              Expanded(
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // Patient Selector
                      patientsAsync.when(
                        loading: () => const LinearProgressIndicator(),
                        error: (e, _) => Text('Erro ao carregar pacientes: $e', style: const TextStyle(color: Colors.red)),
                        data: (patients) {
                          if (_selectedPatientId == null && patients.isNotEmpty) {
                            _selectedPatientId = patients.first.id;
                          }
                          return DropdownButtonFormField<int>(
                            initialValue: _selectedPatientId,
                            decoration: const InputDecoration(
                              labelText: 'Paciente *',
                              prefixIcon: Icon(LucideIcons.user),
                              border: OutlineInputBorder(),
                            ),
                            items: patients.map((p) {
                              return DropdownMenuItem<int>(
                                value: p.id,
                                child: Text('${p.name} (CPF: ${p.cpf ?? 'N/I'})'),
                              );
                            }).toList(),
                            onChanged: widget.initialPatientId != null
                                ? null
                                : (val) {
                                    setState(() => _selectedPatientId = val);
                                  },
                            validator: (val) => val == null ? 'Selecione um paciente' : null,
                          );
                        },
                      ),
                      const SizedBox(height: 20),

                      // Items Section Header
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            'Itens do Orçamento (${_items.length})',
                            style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                          ),
                          ElevatedButton.icon(
                            onPressed: () => _addItemModal(context, proceduresAsync),
                            icon: const Icon(LucideIcons.plus, size: 16),
                            label: const Text('Adicionar Procedimento'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),

                      // Items Table / List
                      if (_items.isEmpty)
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(32),
                          decoration: BoxDecoration(
                            color: theme.cardColor,
                            borderRadius: BorderRadius.circular(12),
                            border: Border.all(color: theme.dividerColor),
                          ),
                          child: Column(
                            children: [
                              const Icon(LucideIcons.receipt, size: 48, color: Colors.grey),
                              const SizedBox(height: 8),
                              const Text(
                                'Nenhum procedimento adicionado ao orçamento.',
                                style: TextStyle(color: Colors.grey),
                              ),
                              const SizedBox(height: 12),
                              OutlinedButton.icon(
                                onPressed: () => _addItemModal(context, proceduresAsync),
                                icon: const Icon(LucideIcons.plus, size: 16),
                                label: const Text('Adicionar Procedimento'),
                              ),
                            ],
                          ),
                        )
                      else
                        ListView.builder(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          itemCount: _items.length,
                          itemBuilder: (context, index) {
                            final item = _items[index];
                            return Card(
                              margin: const EdgeInsets.only(bottom: 8),
                              child: ListTile(
                                dense: true,
                                title: Text(
                                  item.procedureName ?? 'Procedimento #${item.procedureId}',
                                  style: const TextStyle(fontWeight: FontWeight.bold),
                                ),
                                subtitle: Row(
                                  children: [
                                    if (item.toothNumber != null) ...[
                                      Chip(
                                        label: Text('Dente ${item.toothNumber}'),
                                        padding: EdgeInsets.zero,
                                        visualDensity: VisualDensity.compact,
                                        backgroundColor: Colors.blue.withValues(alpha: 0.1),
                                      ),
                                      const SizedBox(width: 6),
                                    ],
                                    if (item.face != null && item.face!.isNotEmpty) ...[
                                      Chip(
                                        label: Text('Face: ${item.face}'),
                                        padding: EdgeInsets.zero,
                                        visualDensity: VisualDensity.compact,
                                        backgroundColor: Colors.purple.withValues(alpha: 0.1),
                                      ),
                                      const SizedBox(width: 6),
                                    ],
                                    Text('Qtd: ${item.quantity} x ${currencyFormatter.format(item.price)}'),
                                  ],
                                ),
                                trailing: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Text(
                                      currencyFormatter.format(item.subtotal),
                                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
                                    ),
                                    const SizedBox(width: 8),
                                    IconButton(
                                      icon: const Icon(LucideIcons.trash2, size: 16, color: Colors.red),
                                      onPressed: () {
                                        setState(() {
                                          _items.removeAt(index);
                                        });
                                      },
                                    ),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
                      const SizedBox(height: 20),

                      // Totals & Discount Card
                      Card(
                        color: theme.colorScheme.primaryContainer.withValues(alpha: 0.2),
                        child: Padding(
                          padding: const EdgeInsets.all(16.0),
                          child: Column(
                            children: [
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  const Text('Subtotal:', style: TextStyle(fontSize: 15)),
                                  Text(
                                    currencyFormatter.format(_subtotalCents / 100.0),
                                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 12),
                              Row(
                                children: [
                                  const Text('Desconto (R\$):', style: TextStyle(fontSize: 15)),
                                  const SizedBox(width: 16),
                                  SizedBox(
                                    width: 140,
                                    child: TextFormField(
                                      controller: _discountController,
                                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                                      decoration: const InputDecoration(
                                        prefixText: 'R\$ ',
                                        isDense: true,
                                        contentPadding: EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                                        border: OutlineInputBorder(),
                                      ),
                                      onChanged: (_) => setState(() {}),
                                    ),
                                  ),
                                  const Spacer(),
                                  Text(
                                    '- ${currencyFormatter.format(_discountCents / 100.0)}',
                                    style: const TextStyle(color: Colors.red, fontSize: 15),
                                  ),
                                ],
                              ),
                              const Divider(height: 20),
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: [
                                  Text(
                                    'Valor Total Final:',
                                    style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                                  ),
                                  Text(
                                    currencyFormatter.format(_finalAmountCents / 100.0),
                                    style: theme.textTheme.headlineSmall?.copyWith(
                                      fontWeight: FontWeight.bold,
                                      color: theme.colorScheme.primary,
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 16),

                      // Notes Input
                      TextFormField(
                        controller: _notesController,
                        decoration: const InputDecoration(
                          labelText: 'Observações / Recomendações',
                          hintText: 'Condições de pagamento, garantias ou observações médicas...',
                          border: OutlineInputBorder(),
                        ),
                        maxLines: 2,
                      ),
                    ],
                  ),
                ),
              ),

              const Divider(height: 24),
              // Footer Action Buttons
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: _isSaving ? null : () => Navigator.of(context).pop(),
                    child: const Text('Cancelar'),
                  ),
                  const SizedBox(width: 12),
                  OutlinedButton.icon(
                    onPressed: _isSaving ? null : () => _submitBudget(status: 'draft'),
                    icon: const Icon(LucideIcons.fileEdit, size: 16),
                    label: const Text('Salvar como Rascunho'),
                  ),
                  const SizedBox(width: 12),
                  ElevatedButton.icon(
                    onPressed: _isSaving ? null : () => _submitBudget(status: 'sent'),
                    icon: const Icon(LucideIcons.send, size: 16),
                    label: _isSaving
                        ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Gerar e Enviar Orçamento'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _addItemModal(BuildContext context, AsyncValue<List<Procedure>> proceduresAsync) {
    proceduresAsync.whenData((procedures) {
      if (procedures.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Nenhum procedimento cadastrado no catálogo. Cadastre procedimentos primeiro.')),
        );
        return;
      }

      showDialog(
        context: context,
        builder: (context) => _BudgetItemDialog(
          procedures: procedures,
          initialTooth: widget.initialToothNumber,
          initialFace: widget.initialFace,
        ),
      ).then((newItem) {
        if (newItem != null && newItem is BudgetItem) {
          setState(() {
            _items.add(newItem);
          });
        }
      });
    });
  }

  Future<void> _submitBudget({required String status}) async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedPatientId == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Selecione um paciente para o orçamento.')),
      );
      return;
    }
    if (_items.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Adicione pelo menos um procedimento ao orçamento.')),
      );
      return;
    }

    setState(() => _isSaving = true);
    try {
      final repo = ref.read(financialRepositoryProvider);

      await repo.createBudget(
        patientId: _selectedPatientId!,
        totalAmountCents: _subtotalCents,
        discountCents: _discountCents,
        finalAmountCents: _finalAmountCents,
        status: status,
        notes: _notesController.text.trim().isEmpty ? null : _notesController.text.trim(),
        items: _items.map((item) => item.toJson()).toList(),
      );

      ref.invalidate(budgetsListProvider);

      if (mounted) {
        Navigator.of(context).pop(true);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(status == 'draft' ? 'Rascunho de orçamento salvo!' : 'Orçamento criado com sucesso!')),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao salvar orçamento: $e')),
        );
      }
    }
  }
}

class _BudgetItemDialog extends StatefulWidget {
  final List<Procedure> procedures;
  final int? initialTooth;
  final String? initialFace;

  const _BudgetItemDialog({
    required this.procedures,
    this.initialTooth,
    this.initialFace,
  });

  @override
  State<_BudgetItemDialog> createState() => _BudgetItemDialogState();
}

class _BudgetItemDialogState extends State<_BudgetItemDialog> {
  final _formKey = GlobalKey<FormState>();
  late Procedure _selectedProcedure;
  late TextEditingController _priceController;
  late TextEditingController _quantityController;
  late TextEditingController _toothController;
  String? _selectedFace;

  final List<String> _faces = [
    'Geral',
    'MESIAL',
    'DISTAL',
    'VESTIBULAR',
    'PALATINA/LINGUAL',
    'OCLUSAL',
    'INCISAL',
  ];

  @override
  void initState() {
    super.initState();
    _selectedProcedure = widget.procedures.first;
    _priceController = TextEditingController(text: _selectedProcedure.basePrice.toStringAsFixed(2));
    _quantityController = TextEditingController(text: '1');
    _toothController = TextEditingController(
      text: widget.initialTooth != null ? widget.initialTooth.toString() : '',
    );
    if (widget.initialFace != null && _faces.contains(widget.initialFace)) {
      _selectedFace = widget.initialFace;
    } else {
      _selectedFace = 'Geral';
    }
  }

  @override
  void dispose() {
    _priceController.dispose();
    _quantityController.dispose();
    _toothController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Adicionar Item de Procedimento'),
      content: SizedBox(
        width: 440,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                DropdownButtonFormField<Procedure>(
                  initialValue: _selectedProcedure,
                  decoration: const InputDecoration(
                    labelText: 'Procedimento *',
                    border: OutlineInputBorder(),
                  ),
                  items: widget.procedures.map((p) {
                    return DropdownMenuItem<Procedure>(
                      value: p,
                      child: Text('${p.name} (R\$ ${p.basePrice.toStringAsFixed(2)})'),
                    );
                  }).toList(),
                  onChanged: (proc) {
                    if (proc != null) {
                      setState(() {
                        _selectedProcedure = proc;
                        _priceController.text = proc.basePrice.toStringAsFixed(2);
                      });
                    }
                  },
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _toothController,
                        keyboardType: TextInputType.number,
                        decoration: const InputDecoration(
                          labelText: 'Dente (FDI ex: 11-48, 51-85)',
                          hintText: 'Opcional',
                          border: OutlineInputBorder(),
                        ),
                        validator: (val) {
                          if (val != null && val.trim().isNotEmpty) {
                            final tooth = int.tryParse(val.trim());
                            if (tooth == null) return 'Dente inválido';
                          }
                          return null;
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: _selectedFace,
                        decoration: const InputDecoration(
                          labelText: 'Face',
                          border: OutlineInputBorder(),
                        ),
                        items: _faces.map((f) => DropdownMenuItem(value: f, child: Text(f))).toList(),
                        onChanged: (val) => setState(() => _selectedFace = val),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _priceController,
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        decoration: const InputDecoration(
                          labelText: 'Preço Unitário (R\$) *',
                          prefixText: 'R\$ ',
                          border: OutlineInputBorder(),
                        ),
                        validator: (val) {
                          if (val == null || val.trim().isEmpty) return 'Informe o valor';
                          final p = double.tryParse(val.replaceAll(',', '.'));
                          if (p == null || p < 0) return 'Valor inválido';
                          return null;
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _quantityController,
                        keyboardType: TextInputType.number,
                        decoration: const InputDecoration(
                          labelText: 'Quantidade *',
                          border: OutlineInputBorder(),
                        ),
                        validator: (val) {
                          if (val == null || val.trim().isEmpty) return 'Informe qtd';
                          final q = int.tryParse(val.trim());
                          if (q == null || q <= 0) return 'Invalido';
                          return null;
                        },
                      ),
                    ),
                  ],
                ),
              ],
            ),
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
              final priceDouble = double.parse(_priceController.text.trim().replaceAll(',', '.'));
              final priceCents = (priceDouble * 100).round();
              final qty = int.parse(_quantityController.text.trim());
              final tooth = _toothController.text.trim().isNotEmpty
                  ? int.parse(_toothController.text.trim())
                  : null;
              final face = (_selectedFace != null && _selectedFace != 'Geral') ? _selectedFace : null;

              final item = BudgetItem(
                procedureId: _selectedProcedure.id,
                procedureName: _selectedProcedure.name,
                toothNumber: tooth,
                face: face,
                priceCents: priceCents,
                quantity: qty,
              );
              Navigator.of(context).pop(item);
            }
          },
          child: const Text('Adicionar'),
        ),
      ],
    );
  }
}
