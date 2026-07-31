import 'package:flutter/material.dart';

class ImportExportDialog extends StatelessWidget {
  const ImportExportDialog({super.key});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Importar / Exportar Dados'),
      content: const Text('Selecione o formato para exportação ou faça o upload de arquivos CSV/Excel.'),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Fechar')),
      ],
    );
  }
}
