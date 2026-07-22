import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_provider.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_repository.dart';
import 'package:frontend_flutter/features/schedule/data/waitlist_model.dart';

class WaitlistFormDialog extends ConsumerStatefulWidget {
  final Waitlist? waitlist;

  const WaitlistFormDialog({super.key, this.waitlist});

  @override
  ConsumerState<WaitlistFormDialog> createState() => _WaitlistFormDialogState();
}

class _WaitlistFormDialogState extends ConsumerState<WaitlistFormDialog> {
  final _formKey = GlobalKey<FormState>();

  int? _selectedPatientId;
  int? _selectedDoctorId;
  int? _selectedAppointmentTypeId;
  String _urgencyLevel = 'low';
  String _preferredTimeRange = 'qualquer';
  String _notes = '';
  final List<String> _preferredDays = [];

  final _daysOfWeek = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', 'Domingo'];
  bool _isSubmitting = false;

  @override
  void initState() {
    super.initState();
    if (widget.waitlist != null) {
      _selectedPatientId = widget.waitlist!.patientId;
      _selectedDoctorId = widget.waitlist!.doctorId;
      _selectedAppointmentTypeId = widget.waitlist!.appointmentTypeId;
      _urgencyLevel = widget.waitlist!.urgencyLevel;
      if (widget.waitlist!.preferredTimeRange.isNotEmpty) {
        _preferredTimeRange = widget.waitlist!.preferredTimeRange;
      }
      _notes = widget.waitlist!.notes;
      if (widget.waitlist!.preferredDays.isNotEmpty) {
        final days = widget.waitlist!.preferredDays.split(',').map((e) => e.trim()).where((e) => e.isNotEmpty);
        _preferredDays.addAll(days);
      }
    }
  }

  void _toggleDay(String day) {
    setState(() {
      if (_preferredDays.contains(day)) {
        _preferredDays.remove(day);
      } else {
        _preferredDays.add(day);
      }
    });
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedPatientId == null) return;

    setState(() => _isSubmitting = true);

    try {
      final repository = ref.read(scheduleRepositoryProvider);
      final payload = {
        'patient_id': _selectedPatientId,
        'doctor_id': _selectedDoctorId,
        'appointment_type_id': _selectedAppointmentTypeId,
        'preferred_days': _preferredDays.join(', '),
        'preferred_time_range': _preferredTimeRange,
        'urgency_level': _urgencyLevel,
        'notes': _notes,
      };

      if (widget.waitlist == null) {
        await repository.createWaitlist(payload);
      } else {
        await repository.updateWaitlist(widget.waitlist!.id, payload);
      }

      ref.invalidate(waitlistsProvider);
      if (mounted) {
        Navigator.of(context).pop();
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Paciente adicionado à fila com sucesso!')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Erro ao adicionar paciente: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _isSubmitting = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final patientsAsync = ref.watch(patientsListProvider(''));
    final usersAsync = ref.watch(usersProvider);
    final appointmentTypesAsync = ref.watch(appointmentTypesProvider);

    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Container(
        width: 500,
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    widget.waitlist == null ? 'Adicionar à Fila de Espera' : 'Editar Fila de Espera',
                    style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const Text(
                'Registre as preferências do paciente para futuros encaixes.',
                style: TextStyle(color: Colors.grey),
              ),
              const SizedBox(height: 24),
              Flexible(
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // Patient
                      const Text('Paciente *', style: TextStyle(fontWeight: FontWeight.bold)),
                      const SizedBox(height: 8),
                      patientsAsync.when(
                        data: (patients) => DropdownButtonFormField<int>(
                          decoration: InputDecoration(
                            border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                          ),
                          hint: const Text('Selecione o paciente'),
                          value: _selectedPatientId,
                          items: patients.map((p) => DropdownMenuItem(
                            value: p.id,
                            child: Text('${p.name} - ${p.phone}'),
                          )).toList(),
                          onChanged: (val) => setState(() => _selectedPatientId = val),
                          validator: (val) => val == null ? 'Obrigatório' : null,
                        ),
                        loading: () => const CircularProgressIndicator(),
                        error: (err, _) => Text('Erro: $err'),
                      ),
                      const SizedBox(height: 16),
                      
                      Row(
                        children: [
                          // Doctor
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Text('Médico (Opcional)', style: TextStyle(fontWeight: FontWeight.bold)),
                                const SizedBox(height: 8),
                                usersAsync.when(
                                  data: (users) {
                                    final doctors = users.where((u) => u.role?.name == 'doctor' || u.role?.name == 'dentist').toList();
                                    return DropdownButtonFormField<int>(
                                      decoration: InputDecoration(
                                        border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                                        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                                      ),
                                      hint: const Text('Qualquer Médico'),
                                      value: _selectedDoctorId,
                                      items: doctors.map((d) => DropdownMenuItem(
                                        value: d.id,
                                        child: Text(d.name),
                                      )).toList(),
                                      onChanged: (val) => setState(() => _selectedDoctorId = val),
                                    );
                                  },
                                  loading: () => const CircularProgressIndicator(),
                                  error: (err, _) => Text('Erro: $err'),
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(width: 16),
                          // Appointment Type
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Text('Tipo de Consulta', style: TextStyle(fontWeight: FontWeight.bold)),
                                const SizedBox(height: 8),
                                appointmentTypesAsync.when(
                                  data: (types) => DropdownButtonFormField<int>(
                                    decoration: InputDecoration(
                                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                                      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                                    ),
                                    hint: const Text('Qualquer Tipo'),
                                    value: _selectedAppointmentTypeId,
                                    items: types.map((t) => DropdownMenuItem(
                                      value: t.id,
                                      child: Text(t.name),
                                    )).toList(),
                                    onChanged: (val) => setState(() => _selectedAppointmentTypeId = val),
                                  ),
                                  loading: () => const CircularProgressIndicator(),
                                  error: (err, _) => Text('Erro: $err'),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),

                      // Urgency
                      const Text('Nível de Emergência', style: TextStyle(fontWeight: FontWeight.bold)),
                      Row(
                        children: [
                          Radio<String>(value: 'low', groupValue: _urgencyLevel, onChanged: (v) => setState(() => _urgencyLevel = v!)),
                          const Text('Baixa'),
                          const SizedBox(width: 16),
                          Radio<String>(value: 'medium', groupValue: _urgencyLevel, onChanged: (v) => setState(() => _urgencyLevel = v!)),
                          const Text('Média'),
                          const SizedBox(width: 16),
                          Radio<String>(value: 'high', groupValue: _urgencyLevel, onChanged: (v) => setState(() => _urgencyLevel = v!)),
                          const Text('Alta'),
                        ],
                      ),
                      const SizedBox(height: 16),

                      // Preferred Days
                      const Text('Dias Preferenciais', style: TextStyle(fontWeight: FontWeight.bold)),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: _daysOfWeek.map((day) {
                          final isSelected = _preferredDays.contains(day);
                          return InkWell(
                            onTap: () => _toggleDay(day),
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                              decoration: BoxDecoration(
                                color: isSelected ? Theme.of(context).primaryColor : Colors.grey.shade200,
                                borderRadius: BorderRadius.circular(20),
                                border: Border.all(color: isSelected ? Theme.of(context).primaryColor : Colors.transparent),
                              ),
                              child: Text(
                                day,
                                style: TextStyle(
                                  color: isSelected ? Colors.white : Colors.black87,
                                  fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                                ),
                              ),
                            ),
                          );
                        }).toList(),
                      ),
                      const SizedBox(height: 16),

                      // Time Range
                      const Text('Turno Preferencial', style: TextStyle(fontWeight: FontWeight.bold)),
                      const SizedBox(height: 8),
                      DropdownButtonFormField<String>(
                        decoration: InputDecoration(
                          border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                        ),
                        value: _preferredTimeRange,
                        items: const [
                          DropdownMenuItem(value: 'qualquer', child: Text('Qualquer Horário')),
                          DropdownMenuItem(value: 'manhã', child: Text('Apenas Manhã')),
                          DropdownMenuItem(value: 'tarde', child: Text('Apenas Tarde')),
                        ],
                        onChanged: (val) => setState(() => _preferredTimeRange = val!),
                      ),
                      const SizedBox(height: 16),

                      // Notes
                      const Text('Observações', style: TextStyle(fontWeight: FontWeight.bold)),
                      const SizedBox(height: 8),
                      TextFormField(
                        decoration: InputDecoration(
                          border: OutlineInputBorder(borderRadius: BorderRadius.circular(8)),
                          hintText: 'Ex: Pode chegar em 20 minutos se for avisado',
                        ),
                        maxLines: 2,
                        onChanged: (val) => _notes = val,
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Cancelar'),
                  ),
                  const SizedBox(width: 16),
                  ElevatedButton(
                    onPressed: _isSubmitting ? null : _submit,
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                    ),
                    child: _isSubmitting
                        ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                        : Text(widget.waitlist == null ? 'Adicionar à Fila' : 'Salvar Alterações'),
                  ),
                ],
              )
            ],
          ),
        ),
      ),
    );
  }
}
