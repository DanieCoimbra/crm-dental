import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_provider.dart';
import 'package:frontend_flutter/features/schedule/data/appointment_type_model.dart';
import 'package:frontend_flutter/features/settings/data/settings_repository.dart';

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

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.type?.name ?? '');
    _descriptionCtrl = TextEditingController(text: widget.type?.description ?? '');
    _durationCtrl = TextEditingController(text: widget.type?.durationMinutes.toString() ?? '30');
    if (widget.type?.color != null && widget.type!.color.isNotEmpty) {
      _selectedColor = widget.type!.color;
    }
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _descriptionCtrl.dispose();
    _durationCtrl.dispose();
    super.dispose();
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
      };

      // Appointment Types API is in SettingsRepository or ScheduleRepository?
      // Wait, appointmentTypesProvider is in schedule_provider.dart, but the backend endpoint is /appointment-types.
      // Let me add createAppointmentType and updateAppointmentType to settings_repository!
      // I'll call API directly via Dio for now to avoid modifying too many files if possible, or I'll add them to repo.
      
      // I should add the methods to SettingsRepository.
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
    return AlertDialog(
      title: Text(widget.type == null ? 'Novo Tipo de Agendamento' : 'Editar Tipo'),
      content: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
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
            const Align(
              alignment: Alignment.centerLeft,
              child: Text('Cor do Agendamento', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14, color: Colors.black54)),
            ),
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
          ],
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
