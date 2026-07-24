import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mask_text_input_formatter/mask_text_input_formatter.dart';
import 'package:frontend_flutter/features/patients/data/patient_model.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';

class PatientFormDialog extends ConsumerStatefulWidget {
  final Patient? patient;

  const PatientFormDialog({super.key, this.patient});

  @override
  ConsumerState<PatientFormDialog> createState() => _PatientFormDialogState();
}

class _PatientFormDialogState extends ConsumerState<PatientFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _nameController;
  late TextEditingController _emailController;
  late TextEditingController _phoneController;
  late TextEditingController _cpfController;
  late TextEditingController _birthDateController;
  late TextEditingController _cepController;
  late TextEditingController _streetController;
  late TextEditingController _neighborhoodController;
  late TextEditingController _numberController;
  late TextEditingController _weightController;

  final cpfMask = MaskTextInputFormatter(mask: '###.###.###-##', filter: {"#": RegExp(r'[0-9]')});
  final cepMask = MaskTextInputFormatter(mask: '#####-###', filter: {"#": RegExp(r'[0-9]')});
  final phoneMask = MaskTextInputFormatter(mask: '(##) #####-####', filter: {"#": RegExp(r'[0-9]')});
  final dateMask = MaskTextInputFormatter(mask: '##/##/####', filter: {"#": RegExp(r'[0-9]')});

  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.patient?.name ?? '');
    _emailController = TextEditingController(text: widget.patient?.email ?? '');
    _phoneController = TextEditingController(text: widget.patient?.phone ?? '');
    _cpfController = TextEditingController(text: widget.patient?.cpf ?? '');
    _cepController = TextEditingController(text: widget.patient?.cep ?? '');
    _streetController = TextEditingController(text: widget.patient?.street ?? '');
    _neighborhoodController = TextEditingController(text: widget.patient?.neighborhood ?? '');
    _numberController = TextEditingController(text: widget.patient?.number ?? '');
    _weightController = TextEditingController(text: widget.patient?.weight != null && widget.patient!.weight! > 0 ? widget.patient!.weight.toString() : '');
    
    // Formatar data se existir
    String birthDateStr = '';
    if (widget.patient?.birthDate != null && widget.patient!.birthDate!.isNotEmpty) {
      try {
        final date = DateTime.parse(widget.patient!.birthDate!);
        birthDateStr = '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
      } catch (_) {}
    }
    _birthDateController = TextEditingController(text: birthDateStr);
  }

  @override
  void dispose() {
    _nameController.dispose();
    _emailController.dispose();
    _phoneController.dispose();
    _cpfController.dispose();
    _birthDateController.dispose();
    _cepController.dispose();
    _streetController.dispose();
    _neighborhoodController.dispose();
    _numberController.dispose();
    _weightController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _isLoading = true;
    });

    try {
      final repo = ref.read(patientsRepositoryProvider);
      
      // Converter data DD/MM/YYYY para YYYY-MM-DD
      String? formattedDate;
      if (_birthDateController.text.length == 10) {
        final parts = _birthDateController.text.split('/');
        formattedDate = '${parts[2]}-${parts[1]}-${parts[0]}T00:00:00Z';
      }

      final data = <String, dynamic>{
        'name': _nameController.text,
        'email': _emailController.text,
        'phone': _phoneController.text,
        'cpf': _cpfController.text,
        'cep': _cepController.text,
        'street': _streetController.text,
        'neighborhood': _neighborhoodController.text,
        'number': _numberController.text,
        'weight': _weightController.text.isNotEmpty ? double.tryParse(_weightController.text.replaceAll(',', '.')) : 0,
      };
      if (formattedDate != null) {
        data['birth_date'] = formattedDate;
      }

      if (widget.patient == null) {
        await repo.createPatient(data);
      } else {
        await repo.updatePatient(widget.patient!.id, data);
      }

      ref.invalidate(patientsListProvider);
      if (!mounted) return;
      Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Erro ao salvar paciente: $e')),
      );
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isNew = widget.patient == null;
    final initialChar = isNew ? '+' : (widget.patient?.name != null && widget.patient!.name.isNotEmpty ? widget.patient!.name[0].toUpperCase() : 'P');

    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Container(
        width: 600,
        constraints: const BoxConstraints(maxWidth: 600, maxHeight: 700),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Header
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
                borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
                border: Border(bottom: BorderSide(color: Theme.of(context).dividerColor)),
              ),
              child: Row(
                children: [
                  Container(
                    width: 48,
                    height: 48,
                    decoration: BoxDecoration(
                      gradient: const LinearGradient(
                        colors: [Colors.blueAccent, Colors.deepPurpleAccent],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Center(
                      child: Text(
                        initialChar,
                        style: const TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.bold),
                      ),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          isNew ? 'Novo Paciente' : widget.patient!.name,
                          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                        ),
                        if (!isNew)
                          Text(
                            'Paciente ID: #${widget.patient!.id}',
                            style: TextStyle(color: Theme.of(context).textTheme.bodySmall?.color, fontSize: 14),
                          ),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
            
            // Tab Header (Visual only for now)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              decoration: BoxDecoration(border: Border(bottom: BorderSide(color: Theme.of(context).dividerColor))),
              child: Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 8),
                    decoration: BoxDecoration(
                      border: Border(bottom: BorderSide(color: Theme.of(context).colorScheme.primary, width: 2)),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.person, size: 18, color: Theme.of(context).colorScheme.primary),
                        const SizedBox(width: 8),
                        Text('Dados Gerais', style: TextStyle(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.bold)),
                      ],
                    ),
                  ),
                ],
              ),
            ),

            // Form Content
            Flexible(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      TextFormField(
                        controller: _nameController,
                        decoration: const InputDecoration(
                          labelText: 'Nome Completo *',
                          prefixIcon: Icon(Icons.person_outline),
                          border: OutlineInputBorder(),
                        ),
                        validator: (value) => value!.isEmpty ? 'Campo obrigatório' : null,
                      ),
                      const SizedBox(height: 16),
                      Row(
                        children: [
                          Expanded(
                            child: TextFormField(
                              controller: _cpfController,
                              inputFormatters: [cpfMask],
                              decoration: const InputDecoration(
                                labelText: 'CPF *',
                                prefixIcon: Icon(Icons.numbers),
                                border: OutlineInputBorder(),
                              ),
                              validator: (value) => value!.isEmpty ? 'Campo obrigatório' : null,
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            child: TextFormField(
                              controller: _phoneController,
                              inputFormatters: [phoneMask],
                              decoration: const InputDecoration(
                                labelText: 'Telefone *',
                                prefixIcon: Icon(Icons.phone_outlined),
                                border: OutlineInputBorder(),
                              ),
                              validator: (value) => value!.isEmpty ? 'Campo obrigatório' : null,
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
                              controller: _emailController,
                              keyboardType: TextInputType.emailAddress,
                              decoration: const InputDecoration(
                                labelText: 'E-mail *',
                                prefixIcon: Icon(Icons.email_outlined),
                                border: OutlineInputBorder(),
                              ),
                              validator: (value) => value!.isEmpty ? 'Campo obrigatório' : null,
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            flex: 1,
                            child: TextFormField(
                              controller: _birthDateController,
                              inputFormatters: [dateMask],
                              decoration: const InputDecoration(
                                labelText: 'Nascimento',
                                prefixIcon: Icon(Icons.calendar_today_outlined),
                                border: OutlineInputBorder(),
                                hintText: 'DD/MM/AAAA',
                              ),
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            flex: 1,
                            child: TextFormField(
                              controller: _weightController,
                              keyboardType: const TextInputType.numberWithOptions(decimal: true),
                              decoration: const InputDecoration(
                                labelText: 'Peso (kg)',
                                prefixIcon: Icon(Icons.monitor_weight_outlined),
                                border: OutlineInputBorder(),
                                hintText: 'Ex: 70.5',
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),
                      // Endereço (Linha 1)
                      Row(
                        children: [
                          Expanded(
                            flex: 1,
                            child: TextFormField(
                              controller: _cepController,
                              inputFormatters: [cepMask],
                              decoration: const InputDecoration(
                                labelText: 'CEP',
                                prefixIcon: Icon(Icons.location_on_outlined),
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            flex: 2,
                            child: TextFormField(
                              controller: _neighborhoodController,
                              decoration: const InputDecoration(
                                labelText: 'Bairro',
                                prefixIcon: Icon(Icons.map_outlined),
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),
                      // Endereço (Linha 2)
                      Row(
                        children: [
                          Expanded(
                            flex: 2,
                            child: TextFormField(
                              controller: _streetController,
                              decoration: const InputDecoration(
                                labelText: 'Rua',
                                prefixIcon: Icon(Icons.home_outlined),
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            flex: 1,
                            child: TextFormField(
                              controller: _numberController,
                              decoration: const InputDecoration(
                                labelText: 'Número',
                                prefixIcon: Icon(Icons.tag),
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),

            // Actions
            Container(
              padding: const EdgeInsets.all(24),
              decoration: BoxDecoration(
                border: Border(top: BorderSide(color: Theme.of(context).dividerColor)),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: _isLoading ? null : () => Navigator.of(context).pop(),
                    style: TextButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
                    ),
                    child: const Text('Cancelar'),
                  ),
                  const SizedBox(width: 16),
                  ElevatedButton(
                    onPressed: _isLoading ? null : _save,
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                      backgroundColor: Theme.of(context).colorScheme.primary,
                      foregroundColor: Theme.of(context).colorScheme.onPrimary,
                    ),
                    child: _isLoading
                        ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                        : Text(isNew ? 'Salvar Novo Paciente' : 'Salvar Alterações'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
