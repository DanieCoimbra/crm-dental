import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_repository.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_provider.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/settings/data/user_model.dart';
import 'package:frontend_flutter/features/settings/data/room_model.dart';
import 'package:intl/intl.dart';

class ShiftAssignmentFormDialog extends ConsumerStatefulWidget {
  const ShiftAssignmentFormDialog({super.key});

  @override
  ConsumerState<ShiftAssignmentFormDialog> createState() => _ShiftAssignmentFormDialogState();
}

class _ShiftAssignmentFormDialogState extends ConsumerState<ShiftAssignmentFormDialog> {
  final _formKey = GlobalKey<FormState>();
  User? _selectedDoctor;
  Room? _selectedRoom;
  DateTime? _selectedDate;
  String _selectedShift = 'morning';
  bool _isLoading = false;

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedDoctor == null || _selectedRoom == null || _selectedDate == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Preencha todos os campos obrigatórios')));
      return;
    }

    setState(() => _isLoading = true);
    try {
      final repo = ref.read(scheduleRepositoryProvider);
      await repo.createShiftAssignment({
        'doctor_id': _selectedDoctor!.id,
        'room_id': _selectedRoom!.id,
        'date': _selectedDate!.toUtc().toIso8601String(),
        'shift': _selectedShift,
      });

      ref.invalidate(shiftAssignmentsProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Turno criado com sucesso!')));
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    // Fetch doctors and rooms
    final doctorsAsync = ref.watch(usersProvider);
    final roomsAsync = ref.watch(roomsProvider);

    return AlertDialog(
      title: const Text('Novo Turno'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              doctorsAsync.when(
                loading: () => const CircularProgressIndicator(),
                error: (e, _) => Text('Erro: $e'),
                data: (users) {
                  final doctors = users.where((u) => u.role?.name == 'doctor' || u.role?.name == 'dentist' || u.role?.name == 'admin' || u.role?.name == 'owner').toList();
                  return DropdownButtonFormField<User>(
                    decoration: const InputDecoration(labelText: 'Médico / Dentista', border: OutlineInputBorder()),
                    initialValue: _selectedDoctor,
                    items: doctors.map((d) => DropdownMenuItem(value: d, child: Text(d.name))).toList(),
                    onChanged: (v) => setState(() => _selectedDoctor = v),
                    validator: (v) => v == null ? 'Obrigatório' : null,
                  );
                },
              ),
              const SizedBox(height: 16),
              roomsAsync.when(
                loading: () => const CircularProgressIndicator(),
                error: (e, _) => Text('Erro: $e'),
                data: (rooms) {
                  return DropdownButtonFormField<Room>(
                    decoration: const InputDecoration(labelText: 'Sala', border: OutlineInputBorder()),
                    initialValue: _selectedRoom,
                    items: rooms.map((r) => DropdownMenuItem(value: r, child: Text(r.name))).toList(),
                    onChanged: (v) => setState(() => _selectedRoom = v),
                    validator: (v) => v == null ? 'Obrigatório' : null,
                  );
                },
              ),
              const SizedBox(height: 16),
              InkWell(
                onTap: () async {
                  final date = await showDatePicker(
                    context: context,
                    initialDate: _selectedDate ?? DateTime.now(),
                    firstDate: DateTime.now().subtract(const Duration(days: 1)),
                    lastDate: DateTime.now().add(const Duration(days: 365)),
                  );
                  if (date != null) {
                    setState(() => _selectedDate = date);
                  }
                },
                child: InputDecorator(
                  decoration: const InputDecoration(labelText: 'Data', border: OutlineInputBorder()),
                  child: Text(_selectedDate != null ? DateFormat('dd/MM/yyyy').format(_selectedDate!) : 'Selecione uma data'),
                ),
              ),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                decoration: const InputDecoration(labelText: 'Turno', border: OutlineInputBorder()),
                initialValue: _selectedShift,
                items: const [
                  DropdownMenuItem(value: 'morning', child: Text('Manhã')),
                  DropdownMenuItem(value: 'afternoon', child: Text('Tarde')),
                ],
                onChanged: (v) => setState(() => _selectedShift = v ?? 'morning'),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancelar')),
        ElevatedButton(
          onPressed: _isLoading ? null : _submit,
          child: _isLoading ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Salvar'),
        ),
      ],
    );
  }
}
