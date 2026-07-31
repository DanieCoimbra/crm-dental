import 'package:flutter/material.dart';

class ShiftAssignmentFormDialog extends StatelessWidget {
  const ShiftAssignmentFormDialog({super.key});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Alocação de Turno'),
      content: const Text('Defina a escala e o profissional responsável.'),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancelar')),
        ElevatedButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Salvar')),
      ],
    );
  }
}
