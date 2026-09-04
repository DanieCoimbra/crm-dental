import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/financial/data/financial_model.dart';
import 'package:frontend_flutter/features/financial/data/financial_repository.dart';
import 'package:frontend_flutter/features/financial/providers/financial_provider.dart';

class ProceduresScreen extends ConsumerStatefulWidget {
  const ProceduresScreen({super.key});

  @override
  ConsumerState<ProceduresScreen> createState() => _ProceduresScreenState();
}

class _ProceduresScreenState extends ConsumerState<ProceduresScreen> {
  String _searchQuery = '';
  final TextEditingController _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final proceduresAsync = ref.watch(proceduresListProvider);
    final theme = Theme.of(context);
    final currencyFormatter = NumberFormat.currency(locale: 'pt_BR', symbol: 'R\$');

    return Scaffold(
      appBar: AppBar(
        title: const Text('Catálogo de Procedimentos'),
        scrolledUnderElevation: 0,
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 16.0),
            child: ElevatedButton.icon(
              onPressed: () => _openProcedureFormDialog(context),
              icon: const Icon(LucideIcons.plus, size: 18),
              label: const Text('Novo Procedimento'),
            ),
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          children: [
            // Search Bar
            TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: 'Buscar procedimento por nome ou descrição...',
                prefixIcon: const Icon(LucideIcons.search),
                suffixIcon: _searchQuery.isNotEmpty
                    ? IconButton(
                        icon: const Icon(LucideIcons.x),
                        onPressed: () {
                          setState(() {
                            _searchController.clear();
                            _searchQuery = '';
                          });
                        },
                      )
                    : null,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              ),
              onChanged: (val) {
                setState(() {
                  _searchQuery = val.trim().toLowerCase();
                });
              },
            ),
            const SizedBox(height: 20),

            // Content List
            Expanded(
              child: proceduresAsync.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (err, _) => Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Icon(LucideIcons.alertTriangle, color: Colors.red, size: 48),
                      const SizedBox(height: 12),
                      Text('Erro ao carregar procedimentos: $err'),
                      const SizedBox(height: 16),
                      ElevatedButton.icon(
                        onPressed: () => ref.invalidate(proceduresListProvider),
                        icon: const Icon(LucideIcons.refreshCw),
                        label: const Text('Tentar Novamente'),
                      ),
                    ],
                  ),
                ),
                data: (procedures) {
                  final filtered = procedures.where((p) {
                    if (_searchQuery.isEmpty) return true;
                    return p.name.toLowerCase().contains(_searchQuery) ||
                        (p.description != null && p.description!.toLowerCase().contains(_searchQuery));
                  }).toList();

                  if (filtered.isEmpty) {
                    return Center(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(LucideIcons.stethoscope, size: 64, color: Colors.grey),
                          const SizedBox(height: 16),
                          Text(
                            _searchQuery.isEmpty
                                ? 'Nenhum procedimento cadastrado.'
                                : 'Nenhum procedimento encontrado para "$_searchQuery".',
                            style: const TextStyle(fontSize: 16, color: Colors.grey),
                          ),
                          const SizedBox(height: 16),
                          if (_searchQuery.isEmpty)
                            ElevatedButton.icon(
                              onPressed: () => _openProcedureFormDialog(context),
                              icon: const Icon(LucideIcons.plus),
                              label: const Text('Cadastrar Primeiro Procedimento'),
                            ),
                        ],
                      ),
                    );
                  }

                  return ListView.builder(
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final proc = filtered[index];
                      final Color procColor = _parseColor(proc.color);

                      return Card(
                        margin: const EdgeInsets.only(bottom: 12),
                        child: ListTile(
                          contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                          leading: CircleAvatar(
                            backgroundColor: procColor.withValues(alpha: 0.2),
                            child: Icon(LucideIcons.stethoscope, color: procColor),
                          ),
                          title: Row(
                            children: [
                              Text(
                                proc.name,
                                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                              ),
                              const SizedBox(width: 8),
                              Container(
                                width: 12,
                                height: 12,
                                decoration: BoxDecoration(
                                  color: procColor,
                                  shape: BoxShape.circle,
                                ),
                              ),
                            ],
                          ),
                          subtitle: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              if (proc.description != null && proc.description!.isNotEmpty)
                                Padding(
                                  padding: const EdgeInsets.only(top: 4.0),
                                  child: Text(proc.description!),
                                ),
                              const SizedBox(height: 4),
                              Row(
                                children: [
                                  Icon(LucideIcons.clock, size: 14, color: theme.textTheme.bodySmall?.color),
                                  const SizedBox(width: 4),
                                  Text(
                                    '${proc.durationMinutes} min',
                                    style: TextStyle(fontSize: 13, color: theme.textTheme.bodySmall?.color),
                                  ),
                                ],
                              ),
                            ],
                          ),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                currencyFormatter.format(proc.basePrice),
                                style: TextStyle(
                                  fontSize: 18,
                                  fontWeight: FontWeight.bold,
                                  color: theme.colorScheme.primary,
                                ),
                              ),
                              const SizedBox(width: 16),
                              IconButton(
                                icon: const Icon(LucideIcons.edit2, size: 18),
                                tooltip: 'Editar',
                                onPressed: () => _openProcedureFormDialog(context, procedure: proc),
                              ),
                              IconButton(
                                icon: const Icon(LucideIcons.trash2, size: 18, color: Colors.red),
                                tooltip: 'Excluir',
                                onPressed: () => _confirmDelete(context, proc),
                              ),
                            ],
                          ),
                        ),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Color _parseColor(String colorHex) {
    try {
      final hex = colorHex.replaceAll('#', '');
      return Color(int.parse('FF$hex', radix: 16));
    } catch (_) {
      return Colors.blue;
    }
  }

  void _openProcedureFormDialog(BuildContext context, {Procedure? procedure}) {
    showDialog(
      context: context,
      builder: (context) => _ProcedureFormModal(procedure: procedure),
    ).then((updated) {
      if (updated == true) {
        ref.invalidate(proceduresListProvider);
      }
    });
  }

  void _confirmDelete(BuildContext context, Procedure procedure) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Excluir Procedimento'),
        content: Text('Tem certeza que deseja excluir o procedimento "${procedure.name}"?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red, foregroundColor: Colors.white),
            onPressed: () async {
              Navigator.of(context).pop();
              try {
                await ref.read(financialRepositoryProvider).deleteProcedure(procedure.id);
                ref.invalidate(proceduresListProvider);
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Procedimento excluído com sucesso!')),
                  );
                }
              } catch (e) {
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(content: Text('Erro ao excluir procedimento: $e')),
                  );
                }
              }
            },
            child: const Text('Excluir'),
          ),
        ],
      ),
    );
  }
}

class _ProcedureFormModal extends ConsumerStatefulWidget {
  final Procedure? procedure;
  const _ProcedureFormModal({this.procedure});

  @override
  ConsumerState<_ProcedureFormModal> createState() => _ProcedureFormModalState();
}

class _ProcedureFormModalState extends ConsumerState<_ProcedureFormModal> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _nameController;
  late TextEditingController _descController;
  late TextEditingController _priceController;
  late TextEditingController _durationController;
  String _selectedColor = '#2563EB';
  bool _isSaving = false;

  final List<String> _colorOptions = [
    '#2563EB', // Blue
    '#10B981', // Green
    '#F59E0B', // Amber
    '#EF4444', // Red
    '#8B5CF6', // Purple
    '#EC4899', // Pink
    '#06B6D4', // Cyan
    '#64748B', // Slate
  ];

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.procedure?.name ?? '');
    _descController = TextEditingController(text: widget.procedure?.description ?? '');
    _priceController = TextEditingController(
      text: widget.procedure != null ? widget.procedure!.basePrice.toStringAsFixed(2) : '',
    );
    _durationController = TextEditingController(
      text: widget.procedure != null ? widget.procedure!.durationMinutes.toString() : '30',
    );
    if (widget.procedure != null) {
      _selectedColor = widget.procedure!.color;
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _descController.dispose();
    _priceController.dispose();
    _durationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isEditing = widget.procedure != null;

    return AlertDialog(
      title: Text(isEditing ? 'Editar Procedimento' : 'Novo Procedimento'),
      content: SizedBox(
        width: 480,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextFormField(
                  controller: _nameController,
                  decoration: const InputDecoration(
                    labelText: 'Nome do Procedimento *',
                    hintText: 'Ex: Restauração Resina, Limpeza, Extração',
                  ),
                  validator: (val) {
                    if (val == null || val.trim().isEmpty) return 'Informe o nome do procedimento.';
                    return null;
                  },
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _descController,
                  decoration: const InputDecoration(
                    labelText: 'Descrição (opcional)',
                    hintText: 'Detalhes clínicos do procedimento',
                  ),
                  maxLines: 2,
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _priceController,
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        decoration: const InputDecoration(
                          labelText: 'Preço-Base (R\$) *',
                          hintText: '0,00',
                          prefixText: 'R\$ ',
                        ),
                        validator: (val) {
                          if (val == null || val.trim().isEmpty) return 'Informe o preço.';
                          final parsed = double.tryParse(val.replaceAll(',', '.'));
                          if (parsed == null || parsed < 0) return 'Preço inválido.';
                          return null;
                        },
                      ),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: TextFormField(
                        controller: _durationController,
                        keyboardType: TextInputType.number,
                        decoration: const InputDecoration(
                          labelText: 'Duração (minutos)',
                          hintText: '30',
                        ),
                        validator: (val) {
                          if (val != null && val.isNotEmpty) {
                            final p = int.tryParse(val);
                            if (p == null || p <= 0) return 'Duração inválida.';
                          }
                          return null;
                        },
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 20),
                const Text('Cor de Identificação:', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 10,
                  children: _colorOptions.map((c) {
                    final isSelected = _selectedColor.toLowerCase() == c.toLowerCase();
                    final colorVal = Color(int.parse('FF${c.replaceAll('#', '')}', radix: 16));
                    return GestureDetector(
                      onTap: () => setState(() => _selectedColor = c),
                      child: Container(
                        width: 32,
                        height: 32,
                        decoration: BoxDecoration(
                          color: colorVal,
                          shape: BoxShape.circle,
                          border: isSelected
                              ? Border.all(color: Theme.of(context).colorScheme.primary, width: 3)
                              : null,
                        ),
                        child: isSelected ? const Icon(LucideIcons.check, size: 16, color: Colors.white) : null,
                      ),
                    );
                  }).toList(),
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isSaving ? null : () => Navigator.of(context).pop(false),
          child: const Text('Cancelar'),
        ),
        ElevatedButton(
          onPressed: _isSaving ? null : _saveProcedure,
          child: _isSaving
              ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
              : Text(isEditing ? 'Salvar Alterações' : 'Cadastrar'),
        ),
      ],
    );
  }

  Future<void> _saveProcedure() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSaving = true);
    try {
      final name = _nameController.text.trim();
      final desc = _descController.text.trim().isEmpty ? null : _descController.text.trim();
      final priceDouble = double.parse(_priceController.text.trim().replaceAll(',', '.'));
      final priceCents = (priceDouble * 100).round();
      final duration = int.tryParse(_durationController.text.trim()) ?? 30;

      final repo = ref.read(financialRepositoryProvider);

      if (widget.procedure != null) {
        await repo.updateProcedure(
          widget.procedure!.id,
          name: name,
          description: desc,
          basePriceCents: priceCents,
          durationMinutes: duration,
          color: _selectedColor,
        );
      } else {
        await repo.createProcedure(
          name: name,
          description: desc,
          basePriceCents: priceCents,
          durationMinutes: duration,
          color: _selectedColor,
        );
      }

      if (mounted) {
        Navigator.of(context).pop(true);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              widget.procedure != null
                  ? 'Procedimento atualizado!'
                  : 'Procedimento cadastrado com sucesso!',
            ),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isSaving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao salvar procedimento: $e')),
        );
      }
    }
  }
}
