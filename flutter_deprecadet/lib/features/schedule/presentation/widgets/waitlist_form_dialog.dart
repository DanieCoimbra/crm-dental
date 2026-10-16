import 'package:flutter/material.dart';

class WaitlistFormDialog extends StatelessWidget {
  final dynamic waitlist;

  const WaitlistFormDialog({super.key, this.waitlist});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Adicionar à Lista de Espera'),
      content: const Text('Selecione o paciente e as preferências de horário.'),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancelar')),
        ElevatedButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Salvar')),
      ],
    );
  }
}
