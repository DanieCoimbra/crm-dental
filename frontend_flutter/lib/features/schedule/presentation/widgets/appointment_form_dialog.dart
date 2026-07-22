import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_provider.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_repository.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:intl/intl.dart';

class AppointmentFormDialog extends ConsumerStatefulWidget {
  final int? initialPatientId;
  final DateTime? initialDate;
  final String? initialNotes;

  const AppointmentFormDialog({
    super.key,
    this.initialPatientId,
    this.initialDate,
    this.initialNotes,
  });

  @override
  ConsumerState<AppointmentFormDialog> createState() => _AppointmentFormDialogState();
}

class _AppointmentFormDialogState extends ConsumerState<AppointmentFormDialog> {
  final _formKey = GlobalKey<FormState>();
  
  int? _selectedPatientId;
  String? _selectedPatientName;
  
  int? _selectedDoctorId;
  String? _selectedDoctorName;
  
  int? _selectedTypeId;
  String? _selectedTypeName;
  
  DateTime? _selectedDate;
  TimeOfDay? _selectedTime;
  final TextEditingController _notesController = TextEditingController();
  
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _selectedPatientId = widget.initialPatientId;
    _selectedDate = widget.initialDate ?? DateTime.now();
    _selectedTime = widget.initialDate != null 
        ? TimeOfDay.fromDateTime(widget.initialDate!) 
        : TimeOfDay.now();
    if (widget.initialNotes != null) {
      _notesController.text = widget.initialNotes!;
    }
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedPatientId == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Selecione um paciente')));
      return;
    }
    if (_selectedDoctorId == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Selecione um médico')));
      return;
    }
    
    setState(() => _isLoading = true);

    try {
      final start = DateTime(
        _selectedDate!.year, _selectedDate!.month, _selectedDate!.day,
        _selectedTime!.hour, _selectedTime!.minute,
      );
      // Duração default de 30 min se não houver tipo, senão poderia buscar do tipo
      final end = start.add(const Duration(minutes: 30));

      final data = {
        'patient_id': _selectedPatientId,
        'doctor_id': _selectedDoctorId,
        'appointment_type_id': _selectedTypeId,
        'start_time': start.toUtc().toIso8601String(),
        'end_time': end.toUtc().toIso8601String(),
        'notes': _notesController.text,
      };

      final repo = ref.read(scheduleRepositoryProvider);
      await repo.createAppointment(data);
      
      if (mounted) {
        ref.invalidate(appointmentsProvider);
        Navigator.pop(context, true);
      }
    } on DioException catch (e) {
      if (mounted) {
        String errMsg = 'Erro de conexão';
        if (e.response?.data is Map && e.response?.data['message'] != null) {
          errMsg = e.response?.data['message'];
        } else if (e.message != null) {
          errMsg = e.message!;
        }
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(errMsg)));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro inesperado: $e')));
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Widget _buildAutocomplete<T extends Object>({
    required String label,
    required List<T> options,
    required String Function(T) displayString,
    required void Function(T) onSelected,
    required void Function(String) onChanged,
    required T? initialValue,
    required String? Function(String?) validator,
  }) {
    final initialText = initialValue != null ? displayString(initialValue) : '';

    return Autocomplete<T>(
      initialValue: TextEditingValue(text: initialText),
      displayStringForOption: displayString,
      optionsBuilder: (TextEditingValue textEditingValue) {
        if (textEditingValue.text.length < 2) {
          return const Iterable.empty();
        }
        return options
            .where((option) => displayString(option)
                .toLowerCase()
                .contains(textEditingValue.text.toLowerCase()))
            .take(5);
      },
      onSelected: onSelected,
      fieldViewBuilder: (context, controller, focusNode, onFieldSubmitted) {
        return TextFormField(
          controller: controller,
          focusNode: focusNode,
          decoration: InputDecoration(
            labelText: label,
            border: const OutlineInputBorder(),
          ),
          validator: validator,
          onChanged: onChanged,
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final patientsAsync = ref.watch(patientsListProvider(''));
    final typesAsync = ref.watch(appointmentTypesProvider);

    return AlertDialog(
      title: const Text('Novo Agendamento'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // Patient Autocomplete
              patientsAsync.when(
                data: (patients) {
                  final initialPatient = _selectedPatientId != null ? patients.where((p) => p.id == _selectedPatientId).firstOrNull : null;
                  if (initialPatient != null && _selectedPatientName == null) {
                    _selectedPatientName = initialPatient.name;
                  }
                  return _buildAutocomplete(
                    label: 'Paciente',
                    options: patients,
                    displayString: (p) => p.name,
                    initialValue: initialPatient,
                    onSelected: (p) {
                      setState(() {
                        _selectedPatientId = p.id;
                        _selectedPatientName = p.name;
                      });
                    },
                    onChanged: (val) {
                      if (val != _selectedPatientName) {
                        _selectedPatientId = null;
                        _selectedPatientName = null;
                      }
                    },
                    validator: (v) {
                      if (v == null || v.isEmpty) return 'Obrigatório';
                      if (_selectedPatientId == null) return 'Selecione um paciente da lista';
                      return null;
                    },
                  );
                },
                loading: () => const CircularProgressIndicator(),
                error: (e, _) => const Text('Erro ao carregar pacientes'),
              ),
              const SizedBox(height: 16),
              
              // Doctor Autocomplete
              ref.watch(usersProvider).when(
                data: (users) {
                  final doctors = users.where((u) => u.role?.name == 'doctor' || u.role?.name == 'medico').toList();
                  final initialDoctor = _selectedDoctorId != null ? doctors.where((d) => d.id == _selectedDoctorId).firstOrNull : null;
                  if (initialDoctor != null && _selectedDoctorName == null) {
                    _selectedDoctorName = initialDoctor.name;
                  }
                  return _buildAutocomplete(
                    label: 'Médico',
                    options: doctors,
                    displayString: (d) => d.name,
                    initialValue: initialDoctor,
                    onSelected: (d) {
                      setState(() {
                        _selectedDoctorId = d.id;
                        _selectedDoctorName = d.name;
                      });
                    },
                    onChanged: (val) {
                      if (val != _selectedDoctorName) {
                        _selectedDoctorId = null;
                        _selectedDoctorName = null;
                      }
                    },
                    validator: (v) {
                      if (v == null || v.isEmpty) return 'Obrigatório';
                      if (_selectedDoctorId == null) return 'Selecione um médico da lista';
                      return null;
                    },
                  );
                },
                loading: () => const CircularProgressIndicator(),
                error: (e, _) => const Text('Erro ao carregar médicos'),
              ),
              const SizedBox(height: 16),

              // Appointment Type Autocomplete
              typesAsync.when(
                data: (types) {
                  final initialType = _selectedTypeId != null ? types.where((t) => t.id == _selectedTypeId).firstOrNull : null;
                  if (initialType != null && _selectedTypeName == null) {
                    _selectedTypeName = initialType.name;
                  }
                  return _buildAutocomplete(
                    label: 'Tipo de Consulta',
                    options: types,
                    displayString: (t) => t.name,
                    initialValue: initialType,
                    onSelected: (t) {
                      setState(() {
                        _selectedTypeId = t.id;
                        _selectedTypeName = t.name;
                      });
                    },
                    onChanged: (val) {
                      if (val != _selectedTypeName) {
                        _selectedTypeId = null;
                        _selectedTypeName = null;
                      }
                    },
                    validator: (v) {
                      if (v != null && v.isNotEmpty && _selectedTypeId == null) {
                        return 'Selecione um tipo da lista';
                      }
                      return null;
                    },
                  );
                },
                loading: () => const SizedBox(),
                error: (e, _) => const SizedBox(),
              ),
              const SizedBox(height: 16),

              // Date Picker
              Row(
                children: [
                  Expanded(
                    child: InkWell(
                      onTap: () async {
                        final date = await showDatePicker(
                          context: context,
                          initialDate: _selectedDate ?? DateTime.now(),
                          firstDate: DateTime(2020),
                          lastDate: DateTime(2030),
                        );
                        if (date != null) setState(() => _selectedDate = date);
                      },
                      child: InputDecorator(
                        decoration: const InputDecoration(labelText: 'Data', border: OutlineInputBorder()),
                        child: Text(_selectedDate != null ? DateFormat('dd/MM/yyyy').format(_selectedDate!) : 'Selecione'),
                      ),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: InkWell(
                      onTap: () async {
                        final time = await showTimePicker(
                          context: context,
                          initialTime: _selectedTime ?? TimeOfDay.now(),
                        );
                        if (time != null) setState(() => _selectedTime = time);
                      },
                      child: InputDecorator(
                        decoration: const InputDecoration(labelText: 'Hora', border: OutlineInputBorder()),
                        child: Text(_selectedTime != null ? _selectedTime!.format(context) : 'Selecione'),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),

              // Notes
              TextFormField(
                controller: _notesController,
                decoration: const InputDecoration(labelText: 'Observações (Serão Criptografadas)', border: OutlineInputBorder()),
                maxLines: 2,
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
        ElevatedButton(
          onPressed: _isLoading ? null : _submit,
          child: _isLoading ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Agendar'),
        ),
      ],
    );
  }
}
