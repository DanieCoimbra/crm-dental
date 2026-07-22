import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/settings/data/room_model.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/settings/data/settings_repository.dart';

class RoomDialog extends ConsumerStatefulWidget {
  final Room? room;
  const RoomDialog({super.key, this.room});

  @override
  ConsumerState<RoomDialog> createState() => _RoomDialogState();
}

class _RoomDialogState extends ConsumerState<RoomDialog> {
  final _formKey = GlobalKey<FormState>();
  late TextEditingController _nameCtrl;
  late TextEditingController _descCtrl;
  bool _isActive = true;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.room?.name ?? '');
    _descCtrl = TextEditingController(text: widget.room?.description ?? '');
    if (widget.room != null) {
      _isActive = widget.room!.isActive;
    }
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _isLoading = true);
    
    try {
      final repo = ref.read(settingsRepositoryProvider);
      final data = {
        'name': _nameCtrl.text,
        'description': _descCtrl.text,
        'is_active': _isActive,
      };

      if (widget.room == null) {
        await repo.createRoom(data);
      } else {
        await repo.updateRoom(widget.room!.id, data);
      }
      
      if (mounted) {
        ref.invalidate(roomsProvider);
        Navigator.pop(context);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Sala salva com sucesso!'))
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.room == null ? 'Nova Sala' : 'Editar Sala'),
      content: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextFormField(
              controller: _nameCtrl,
              decoration: const InputDecoration(labelText: 'Nome da Sala'),
              validator: (v) => v!.isEmpty ? 'Obrigatório' : null,
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: _descCtrl,
              decoration: const InputDecoration(labelText: 'Descrição'),
            ),
            const SizedBox(height: 16),
            SwitchListTile(
              title: const Text('Ativa'),
              value: _isActive,
              onChanged: (val) => setState(() => _isActive = val),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancelar'),
        ),
        ElevatedButton(
          onPressed: _isLoading ? null : _save,
          child: _isLoading ? const CircularProgressIndicator() : const Text('Salvar'),
        ),
      ],
    );
  }
}
