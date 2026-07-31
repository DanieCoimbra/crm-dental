import 'package:flutter/material.dart';

class PatientFormDialog extends StatefulWidget {
  final dynamic patient;

  const PatientFormDialog({super.key, this.patient});

  @override
  State<PatientFormDialog> createState() => _PatientFormDialogState();
}

class _PatientFormDialogState extends State<PatientFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _nameController;
  late TextEditingController _cpfController;
  late TextEditingController _phoneController;
  late TextEditingController _emailController;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: (widget.patient is Map ? widget.patient['name'] : widget.patient?.name) ?? '');
    _cpfController = TextEditingController(text: (widget.patient is Map ? widget.patient['cpf'] : widget.patient?.cpf) ?? '');
    _phoneController = TextEditingController(text: (widget.patient is Map ? widget.patient['phone'] : widget.patient?.phone) ?? '');
    _emailController = TextEditingController(text: (widget.patient is Map ? widget.patient['email'] : widget.patient?.email) ?? '');
  }

  @override
  void dispose() {
    _nameController.dispose();
    _cpfController.dispose();
    _phoneController.dispose();
    _emailController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isEditing = widget.patient != null;

    return AlertDialog(
      title: Text(isEditing ? 'Editar Paciente' : 'Novo Paciente'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _nameController,
                decoration: const InputDecoration(labelText: 'Nome Completo *', border: OutlineInputBorder()),
                validator: (val) => val == null || val.trim().isEmpty ? 'Informe o nome' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _cpfController,
                decoration: const InputDecoration(labelText: 'CPF', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _phoneController,
                decoration: const InputDecoration(labelText: 'Telefone', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _emailController,
                decoration: const InputDecoration(labelText: 'E-mail', border: OutlineInputBorder()),
              ),
            ],
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
              Navigator.of(context).pop(true);
            }
          },
          child: Text(isEditing ? 'Atualizar' : 'Salvar'),
        ),
      ],
    );
  }
}
