import 'package:flutter/material.dart';

class RoleManagerDialog extends StatelessWidget {
  const RoleManagerDialog({super.key});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Gestão de Papéis e Permissões'),
      content: const Text('Configure os cargos dos membros da equipe.'),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Fechar')),
      ],
    );
  }
}
