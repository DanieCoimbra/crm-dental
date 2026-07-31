import 'package:flutter/material.dart';

class SmartBookingDialog extends StatelessWidget {
  final List<dynamic>? matches;
  final dynamic availableTime;
  final dynamic doctorId;

  const SmartBookingDialog({
    super.key,
    this.matches,
    this.availableTime,
    this.doctorId,
  });

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Agendamento Inteligente'),
      content: const Text('Encontre os melhores horários vagos automaticamente.'),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Fechar')),
      ],
    );
  }
}
