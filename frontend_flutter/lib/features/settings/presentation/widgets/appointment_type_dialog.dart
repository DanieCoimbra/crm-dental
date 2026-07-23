import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_provider.dart';
import 'package:frontend_flutter/features/schedule/data/appointment_type_model.dart';
import 'package:frontend_flutter/features/settings/data/settings_repository.dart';
import 'package:frontend_flutter/features/inventory/providers/inventory_provider.dart';
import 'package:frontend_flutter/features/inventory/data/inventory_model.dart';
import 'package:frontend_flutter/core/theme/app_theme.dart';

class AppointmentTypeDialog extends ConsumerStatefulWidget {
  final AppointmentType? type;
  const AppointmentTypeDialog({super.key, this.type});

  @override
  ConsumerState<AppointmentTypeDialog> createState() => _AppointmentTypeDialogState();
}

class _AppointmentTypeDialogState extends ConsumerState<AppointmentTypeDialog> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _nameCtrl;
  late TextEditingController _descriptionCtrl;
  late TextEditingController _durationCtrl;
  String _selectedColor = '#4CAF50';
  bool _isLoading = false;

  List<Map<String, dynamic>> _selectedMaterials = [];

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.type?.name ?? '');
    _descriptionCtrl = TextEditingController(text: widget.type?.description ?? '');
    _durationCtrl = TextEditingController(text: widget.type?.durationMinutes.toString() ?? '30');
    if (widget.type?.color != null && widget.type!.color.isNotEmpty) {
      _selectedColor = widget.type!.color;
    }
    if (widget.type?.materials != null) {
      _selectedMaterials = widget.type!.materials.map((m) => {
        'inventory_item_id': m.inventoryItemId,
        'quantity': m.quantity,
        'name': m.itemName ?? 'Item ${m.inventoryItemId}',
        'unit': m.itemUnit ?? 'un',
      }).toList();
    }
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _descriptionCtrl.dispose();
    _durationCtrl.dispose();
    super.dispose();
  }

  void _addMaterial(InventoryItem item, double quantity) {
    setState(() {
      final idx = _selectedMaterials.indexWhere((m) => m['inventory_item_id'] == item.id);
      if (idx >= 0) {
        _selectedMaterials[idx]['quantity'] = quantity;
      } else {
        _selectedMaterials.add({
          'inventory_item_id': item.id,
          'quantity': quantity,
          'name': item.name,
          'unit': item.unit,
        });
      }
    });
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _isLoading = true);
    
    try {
      final repo = ref.read(settingsRepositoryProvider);
      final data = {
        'name': _nameCtrl.text,
        'description': _descriptionCtrl.text,
        'duration_minutes': int.parse(_durationCtrl.text),
        'color': _selectedColor,
        'materials': _selectedMaterials.map((m) => {
          'inventory_item_id': m['inventory_item_id'],
          'quantity': m['quantity'],
        }).toList(),
      };

      if (widget.type == null) {
        await repo.createAppointmentType(data);
      } else {
        await repo.updateAppointmentType(widget.type!.id, data);
      }
      
      if (mounted) {
        ref.invalidate(appointmentTypesProvider);
        Navigator.pop(context);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Tipo de agendamento salvo com sucesso!'))
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final inventoryAsync = ref.watch(inventoryListProvider);

    return AlertDialog(
      title: Text(widget.type == null ? 'Novo Tipo de Agendamento' : 'Editar Tipo'),
      content: SizedBox(
        width: 600,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextFormField(
                  controller: _nameCtrl,
                  decoration: const InputDecoration(labelText: 'Nome do Serviço'),
                  validator: (v) => v!.isEmpty ? 'Obrigatório' : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _descriptionCtrl,
                  decoration: const InputDecoration(labelText: 'Descrição (opcional)'),
                  maxLines: 2,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _durationCtrl,
                  decoration: const InputDecoration(labelText: 'Duração (minutos)'),
                  keyboardType: TextInputType.number,
                  validator: (v) => int.tryParse(v ?? '') == null ? 'Número inválido' : null,
                ),
                const SizedBox(height: 24),
                const Text('Cor do Agendamento', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14, color: Colors.black54)),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 12,
                  runSpacing: 12,
                  children: [
                    '#4CAF50', '#2196F3', '#F44336', '#FF9800', '#9C27B0',
                    '#009688', '#E91E63', '#3F51B5', '#00BCD4', '#8BC34A',
                  ].map((colorHex) {
                    final color = Color(int.parse(colorHex.replaceFirst('#', '0xFF')));
                    final isSelected = _selectedColor.toUpperCase() == colorHex.toUpperCase();
                    return GestureDetector(
                      onTap: () => setState(() => _selectedColor = colorHex),
                      child: Container(
                        width: 36,
                        height: 36,
                        decoration: BoxDecoration(
                          color: color,
                          shape: BoxShape.circle,
                          border: isSelected ? Border.all(color: Colors.black87, width: 3) : Border.all(color: Colors.transparent, width: 3),
                          boxShadow: [
                            if (isSelected) BoxShadow(color: color.withOpacity(0.5), blurRadius: 4, spreadRadius: 1)
                          ],
                        ),
                        child: isSelected ? const Icon(Icons.check, color: Colors.white, size: 20) : null,
                      ),
                    );
                  }).toList(),
                ),
                const SizedBox(height: 24),
                const Divider(),
                const Text('Materiais Consumidos (Automático)', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                const Text('Ao finalizar este procedimento, os itens abaixo serão deduzidos do estoque.', style: TextStyle(fontSize: 12, color: Colors.grey)),
                const SizedBox(height: 12),
                
                // Lista de materiais adicionados
                if (_selectedMaterials.isNotEmpty)
                  ListView.builder(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    itemCount: _selectedMaterials.length,
                    itemBuilder: (context, index) {
                      final mat = _selectedMaterials[index];
                      return ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text(mat['name']),
                        subtitle: Text('Qtd: ${mat['quantity']} ${mat['unit']}'),
                        trailing: IconButton(
                          icon: const Icon(LucideIcons.trash2, color: Colors.red, size: 18),
                          onPressed: () {
                            setState(() => _selectedMaterials.removeAt(index));
                          },
                        ),
                      );
                    },
                  ),
                  
                const SizedBox(height: 8),
                inventoryAsync.when(
                  data: (items) {
                    InventoryItem? selectedItem;
                    final qtyCtrl = TextEditingController(text: '1');
                    
                    return StatefulBuilder(
                      builder: (context, setLocalState) {
                        return Row(
                          children: [
                            Expanded(
                              flex: 2,
                              child: DropdownButtonFormField<InventoryItem>(
                                decoration: const InputDecoration(labelText: 'Selecionar Produto', isDense: true),
                                value: selectedItem,
                                isExpanded: true,
                                items: items.map((i) => DropdownMenuItem(
                                  value: i,
                                  child: Text('${i.name} (Em est: ${i.quantity})'),
                                )).toList(),
                                onChanged: (v) => setLocalState(() => selectedItem = v),
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              flex: 1,
                              child: TextFormField(
                                controller: qtyCtrl,
                                decoration: const InputDecoration(labelText: 'Qtd', isDense: true),
                                keyboardType: TextInputType.number,
                              ),
                            ),
                            const SizedBox(width: 8),
                            ElevatedButton(
                              onPressed: () {
                                if (selectedItem != null) {
                                  final q = double.tryParse(qtyCtrl.text) ?? 1.0;
                                  _addMaterial(selectedItem!, q);
                                  setLocalState(() => selectedItem = null);
                                  qtyCtrl.text = '1';
                                }
                              },
                              child: const Icon(LucideIcons.plus, size: 18),
                            )
                          ],
                        );
                      }
                    );
                  },
                  loading: () => const CircularProgressIndicator(),
                  error: (e, _) => Text('Erro ao carregar estoque: $e'),
                )
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
          onPressed: _isLoading ? null : _save,
          child: _isLoading ? const CircularProgressIndicator() : const Text('Salvar'),
        ),
      ],
    );
  }
}
