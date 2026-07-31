import 'package:flutter/material.dart';

class AppointmentTypeDialog extends StatelessWidget {
  final dynamic type;

  const AppointmentTypeDialog({super.key, this.type});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(type != null ? 'Editar Tipo de Consulta' : 'Novo Tipo de Consulta'),
      content: const Text('Informe a descrição e a duração estimada.'),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancelar')),
        ElevatedButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Salvar')),
      ],
    );
  }
}
