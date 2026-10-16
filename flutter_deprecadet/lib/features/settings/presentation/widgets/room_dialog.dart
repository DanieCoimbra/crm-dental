import 'package:flutter/material.dart';

class RoomDialog extends StatelessWidget {
  final dynamic room;

  const RoomDialog({super.key, this.room});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(room != null ? 'Editar Consultório' : 'Novo Consultório'),
      content: const Text('Informe os dados da sala / consultório.'),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancelar')),
        ElevatedButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Salvar')),
      ],
    );
  }
}
