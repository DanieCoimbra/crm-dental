import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mask_text_input_formatter/mask_text_input_formatter.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';
import 'package:frontend_flutter/features/patients/utils/validators.dart';

class PatientFormDialog extends ConsumerStatefulWidget {
  final Patient? patient;

  const PatientFormDialog({super.key, this.patient});

  @override
  ConsumerState<PatientFormDialog> createState() => _PatientFormDialogState();
}

class _PatientFormDialogState extends ConsumerState<PatientFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _nameController;
  late TextEditingController _cpfController;
  late TextEditingController _phoneController;
  late TextEditingController _emailController;
  late TextEditingController _birthDateController;
  late TextEditingController _healthInsuranceController;
  late TextEditingController _cepController;
  late TextEditingController _streetController;
  late TextEditingController _neighborhoodController;
  late TextEditingController _numberController;
  late TextEditingController _notesController;

  final _cpfFormatter = MaskTextInputFormatter(
    mask: '###.###.###-##',
    filter: {"#": RegExp(r'[0-9]')},
  );

  final _phoneFormatter = MaskTextInputFormatter(
    mask: '(##) #####-####',
    filter: {"#": RegExp(r'[0-9]')},
  );

  final _dateFormatter = MaskTextInputFormatter(
    mask: '##/##/####',
    filter: {"#": RegExp(r'[0-9]')},
  );

  final _cepFormatter = MaskTextInputFormatter(
    mask: '#####-###',
    filter: {"#": RegExp(r'[0-9]')},
  );

  bool _isSaving = false;
  String _selectedInsurance = 'Particular';

  final List<String> _insuranceOptions = [
    'Particular',
    'Unimed',
    'Amil',
    'Bradesco Saúde',
    'SulAmérica',
    'Porto Seguro',
    'Outro',
  ];

  @override
  void initState() {
    super.initState();
    final p = widget.patient;
    _nameController = TextEditingController(text: p?.name ?? '');
    _cpfController = TextEditingController(text: p?.cpf ?? '');
    _phoneController = TextEditingController(text: p?.phone ?? '');
    _emailController = TextEditingController(text: p?.email ?? '');
    _birthDateController = TextEditingController(text: p?.birthDate ?? '');
    _healthInsuranceController = TextEditingController(text: p?.healthInsurance ?? 'Particular');
    _cepController = TextEditingController(text: p?.cep ?? '');
    _streetController = TextEditingController(text: p?.street ?? '');
    _neighborhoodController = TextEditingController(text: p?.neighborhood ?? '');
    _numberController = TextEditingController(text: p?.number ?? '');
    _notesController = TextEditingController(text: p?.notes ?? '');

    if (p?.healthInsurance != null && _insuranceOptions.contains(p!.healthInsurance!)) {
      _selectedInsurance = p.healthInsurance!;
    } else if (p?.healthInsurance != null && p!.healthInsurance!.isNotEmpty) {
      _selectedInsurance = 'Outro';
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _cpfController.dispose();
    _phoneController.dispose();
    _emailController.dispose();
    _birthDateController.dispose();
    _healthInsuranceController.dispose();
    _cepController.dispose();
    _streetController.dispose();
    _neighborhoodController.dispose();
    _numberController.dispose();
    _notesController.dispose();
    super.dispose();
  }

  Future<void> _selectBirthDate() async {
    DateTime initial = DateTime.now().subtract(const Duration(days: 365 * 30));
    if (_birthDateController.text.isNotEmpty) {
      final parts = _birthDateController.text.split('/');
      if (parts.length == 3) {
        final d = int.tryParse(parts[0]);
        final m = int.tryParse(parts[1]);
        final y = int.tryParse(parts[2]);
        if (d != null && m != null && y != null) {
          initial = DateTime(y, m, d);
        }
      }
    }

    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(1900),
      lastDate: DateTime.now(),
    );

    if (picked != null) {
      final day = picked.day.toString().padLeft(2, '0');
      final month = picked.month.toString().padLeft(2, '0');
      final year = picked.year.toString();
      setState(() {
        _birthDateController.text = '$day/$month/$year';
      });
    }
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _isSaving = true;
    });

    try {
      final insurance = _selectedInsurance == 'Outro'
          ? _healthInsuranceController.text.trim()
          : _selectedInsurance;

      final data = {
        'name': _nameController.text.trim(),
        'cpf': _cpfController.text.trim(),
        'phone': _phoneController.text.trim(),
        'email': _emailController.text.trim(),
        'birth_date': _birthDateController.text.trim(),
        'health_insurance': insurance,
        'cep': _cepController.text.trim(),
        'street': _streetController.text.trim(),
        'neighborhood': _neighborhoodController.text.trim(),
        'number': _numberController.text.trim(),
        'notes': _notesController.text.trim(),
      };

      final repo = ref.read(patientsRepositoryProvider);
      if (widget.patient == null) {
        await repo.createPatient(data);
      } else {
        await repo.updatePatient(widget.patient!.id, data);
      }

      ref.invalidate(patientsListProvider);

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(widget.patient == null
                ? 'Paciente cadastrado com sucesso!'
                : 'Paciente atualizado com sucesso!'),
            backgroundColor: Colors.green,
          ),
        );
        Navigator.of(context).pop(true);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Erro ao salvar paciente: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isSaving = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isEditing = widget.patient != null;
    final theme = Theme.of(context);

    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Container(
        width: 650,
        constraints: BoxConstraints(
          maxHeight: MediaQuery.of(context).size.height * 0.85,
        ),
        padding: const EdgeInsets.all(24.0),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Row(
                    children: [
                      Icon(
                        isEditing ? LucideIcons.userCheck : LucideIcons.userPlus,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 12),
                      Text(
                        isEditing ? 'Editar Paciente' : 'Novo Cadastro de Paciente',
                        style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
                  IconButton(
                    icon: const Icon(LucideIcons.x),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const Divider(height: 24),
              Flexible(
                child: SingleChildScrollView(
                  child: Column(
                    children: [
                      // Nome Completo
                      TextFormField(
                        controller: _nameController,
                        decoration: const InputDecoration(
                          labelText: 'Nome Completo *',
                          prefixIcon: Icon(LucideIcons.user),
                          border: OutlineInputBorder(),
                        ),
                        validator: (val) => val == null || val.trim().isEmpty ? 'Informe o nome completo' : null,
                      ),
                      const SizedBox(height: 16),

                      // Row: CPF e Telefone
                      Row(
                        children: [
                          Expanded(
                            child: TextFormField(
                              controller: _cpfController,
                              inputFormatters: [_cpfFormatter],
                              keyboardType: TextInputType.number,
                              decoration: const InputDecoration(
                                labelText: 'CPF *',
                                hintText: '000.000.000-00',
                                prefixIcon: Icon(LucideIcons.idCard),
                                border: OutlineInputBorder(),
                              ),
                              validator: AppValidators.validateCpf,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: TextFormField(
                              controller: _phoneController,
                              inputFormatters: [_phoneFormatter],
                              keyboardType: TextInputType.phone,
                              decoration: const InputDecoration(
                                labelText: 'Telefone / WhatsApp *',
                                hintText: '(00) 00000-0000',
                                prefixIcon: Icon(LucideIcons.phone),
                                border: OutlineInputBorder(),
                              ),
                              validator: AppValidators.validatePhone,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),

                      // Row: E-mail e Data Nasc
                      Row(
                        children: [
                          Expanded(
                            child: TextFormField(
                              controller: _emailController,
                              keyboardType: TextInputType.emailAddress,
                              decoration: const InputDecoration(
                                labelText: 'E-mail',
                                prefixIcon: Icon(LucideIcons.mail),
                                border: OutlineInputBorder(),
                              ),
                              validator: AppValidators.validateEmail,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: TextFormField(
                              controller: _birthDateController,
                              inputFormatters: [_dateFormatter],
                              keyboardType: TextInputType.datetime,
                              decoration: InputDecoration(
                                labelText: 'Data de Nascimento',
                                hintText: 'DD/MM/AAAA',
                                prefixIcon: const Icon(LucideIcons.calendar),
                                suffixIcon: IconButton(
                                  icon: const Icon(LucideIcons.calendarDays),
                                  onPressed: _selectBirthDate,
                                ),
                                border: const OutlineInputBorder(),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),

                      // Convênio / Plano de Saúde
                      DropdownButtonFormField<String>(
                        initialValue: _selectedInsurance,
                        decoration: const InputDecoration(
                          labelText: 'Convênio / Plano de Saúde',
                          prefixIcon: Icon(LucideIcons.shieldCheck),
                          border: OutlineInputBorder(),
                        ),
                        items: _insuranceOptions.map((opt) {
                          return DropdownMenuItem(
                            value: opt,
                            child: Text(opt),
                          );
                        }).toList(),
                        onChanged: (val) {
                          if (val != null) {
                            setState(() {
                              _selectedInsurance = val;
                            });
                          }
                        },
                      ),
                      if (_selectedInsurance == 'Outro') ...[
                        const SizedBox(height: 12),
                        TextFormField(
                          controller: _healthInsuranceController,
                          decoration: const InputDecoration(
                            labelText: 'Especifique o Convênio',
                            border: OutlineInputBorder(),
                          ),
                        ),
                      ],
                      const SizedBox(height: 16),

                      // Row: CEP, Rua, Número, Bairro
                      Row(
                        children: [
                          Expanded(
                            flex: 2,
                            child: TextFormField(
                              controller: _cepController,
                              inputFormatters: [_cepFormatter],
                              keyboardType: TextInputType.number,
                              decoration: const InputDecoration(
                                labelText: 'CEP',
                                prefixIcon: Icon(LucideIcons.mapPin),
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            flex: 3,
                            child: TextFormField(
                              controller: _streetController,
                              decoration: const InputDecoration(
                                labelText: 'Rua / Logradouro',
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),

                      Row(
                        children: [
                          Expanded(
                            flex: 2,
                            child: TextFormField(
                              controller: _numberController,
                              decoration: const InputDecoration(
                                labelText: 'Número',
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            flex: 3,
                            child: TextFormField(
                              controller: _neighborhoodController,
                              decoration: const InputDecoration(
                                labelText: 'Bairro',
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),

                      // Anamnese / Observações gerais
                      TextFormField(
                        controller: _notesController,
                        maxLines: 3,
                        decoration: const InputDecoration(
                          labelText: 'Observações / Anamnese Inicial',
                          hintText: 'Alergias, histórico médico de interesse...',
                          alignLabelWithHint: true,
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 20),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  OutlinedButton(
                    onPressed: _isSaving ? null : () => Navigator.of(context).pop(),
                    child: const Text('Cancelar'),
                  ),
                  const SizedBox(width: 12),
                  ElevatedButton.icon(
                    onPressed: _isSaving ? null : _submit,
                    icon: _isSaving
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2),
                          )
                        : const Icon(LucideIcons.save),
                    label: Text(isEditing ? 'Atualizar Paciente' : 'Salvar Paciente'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
