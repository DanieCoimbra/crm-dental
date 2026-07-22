import 'package:flutter/material.dart';

class PasswordConfirmationDialog extends StatefulWidget {
  final String title;
  final String message;
  final Future<void> Function(String password) onConfirm;

  const PasswordConfirmationDialog({
    super.key,
    required this.title,
    required this.message,
    required this.onConfirm,
  });

  @override
  State<PasswordConfirmationDialog> createState() => _PasswordConfirmationDialogState();
}

class _PasswordConfirmationDialogState extends State<PasswordConfirmationDialog> {
  final _passwordController = TextEditingController();
  bool _isLoading = false;
  String? _errorText;

  @override
  void dispose() {
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _handleConfirm() async {
    final password = _passwordController.text;
    if (password.isEmpty) {
      setState(() => _errorText = 'A senha é obrigatória.');
      return;
    }

    setState(() {
      _isLoading = true;
      _errorText = null;
    });

    try {
      await widget.onConfirm(password);
      if (mounted) {
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorText = e.toString().replaceAll('Exception: ', '');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title, style: const TextStyle(color: Colors.red)),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(widget.message),
          const SizedBox(height: 16),
          TextField(
            controller: _passwordController,
            obscureText: true,
            decoration: InputDecoration(
              labelText: 'Senha do Administrador',
              border: const OutlineInputBorder(),
              errorText: _errorText,
            ),
            onSubmitted: (_) => _handleConfirm(),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: _isLoading ? null : () => Navigator.pop(context, false),
          child: const Text('Cancelar'),
        ),
        ElevatedButton(
          onPressed: _isLoading ? null : _handleConfirm,
          style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
          child: _isLoading
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2))
              : const Text('Confirmar'),
        ),
      ],
    );
  }
}

Future<bool?> showPasswordConfirmationDialog({
  required BuildContext context,
  required String title,
  required String message,
  required Future<void> Function(String password) onConfirm,
}) {
  return showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (context) => PasswordConfirmationDialog(
      title: title,
      message: message,
      onConfirm: onConfirm,
    ),
  );
}
