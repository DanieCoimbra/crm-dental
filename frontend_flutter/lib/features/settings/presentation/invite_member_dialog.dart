import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/settings/data/settings_provider.dart';
import 'package:frontend_flutter/features/settings/data/settings_repository.dart';
import 'package:frontend_flutter/core/network/api_client.dart';

import 'package:frontend_flutter/features/settings/data/user_model.dart';

class MemberDialog extends ConsumerStatefulWidget {
  final User? user;
  const MemberDialog({super.key, this.user});

  @override
  ConsumerState<MemberDialog> createState() => _MemberDialogState();
}

class _MemberDialogState extends ConsumerState<MemberDialog> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  int? _selectedRoleId;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    if (widget.user != null) {
      _nameCtrl.text = widget.user!.name;
      _emailCtrl.text = widget.user!.email;
      _selectedRoleId = widget.user!.role?.id;
    }
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_selectedRoleId == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Selecione um papel')));
      return;
    }
    
    setState(() => _isLoading = true);
    
    try {
      final dio = ref.read(dioProvider);
      final repo = SettingsRepository(dio);
      final roles = ref.read(rolesProvider).value ?? [];
      final roleName = roles.firstWhere((r) => r.id == _selectedRoleId).name;

      final data = <String, dynamic>{
        'name': _nameCtrl.text,
        'email': _emailCtrl.text,
        'role': roleName,
      };

      if (widget.user == null) {
        data['password'] = _passwordCtrl.text.isNotEmpty ? _passwordCtrl.text : 'password123';
      } else if (_passwordCtrl.text.isNotEmpty) {
        data['password'] = _passwordCtrl.text;
      }

      if (widget.user == null) {
        await repo.createTeamMember(data);
      } else {
        await repo.updateTeamMember(widget.user!.id, data);
      }
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(widget.user == null ? 'Membro adicionado com sucesso!' : 'Membro atualizado com sucesso!')));
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final rolesAsync = ref.watch(rolesProvider);

    return AlertDialog(
      title: Text(widget.user == null ? 'Adicionar Novo Membro' : 'Editar Membro'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _nameCtrl,
                decoration: const InputDecoration(labelText: 'Nome Completo', border: OutlineInputBorder()),
                validator: (v) => v!.isEmpty ? 'Obrigatório' : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _emailCtrl,
                decoration: const InputDecoration(labelText: 'E-mail Profissional', border: OutlineInputBorder()),
                validator: (v) => v!.isEmpty ? 'Obrigatório' : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _passwordCtrl,
                obscureText: true,
                decoration: InputDecoration(
                  labelText: widget.user == null ? 'Senha (padrão: password123)' : 'Nova Senha (opcional)', 
                  border: const OutlineInputBorder(),
                  helperText: widget.user == null ? 'Deixe em branco para usar a senha padrão' : 'Deixe em branco para não alterar',
                ),
              ),
              const SizedBox(height: 16),
              rolesAsync.when(
                data: (roles) => DropdownButtonFormField<int>(
                  decoration: const InputDecoration(labelText: 'Função (Role)', border: OutlineInputBorder()),
                  initialValue: _selectedRoleId,
                  items: roles.map((r) => DropdownMenuItem(
                    value: r.id,
                    child: Text(r.name),
                  )).toList(),
                  onChanged: (val) => setState(() => _selectedRoleId = val),
                  validator: (val) => val == null ? 'Obrigatório' : null,
                ),
                loading: () => const CircularProgressIndicator(),
                error: (e, _) => Text('Erro ao carregar roles: $e'),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancelar')),
        ElevatedButton(
          onPressed: _isLoading ? null : _submit,
          child: _isLoading ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)) : Text(widget.user == null ? 'Adicionar' : 'Salvar'),
        ),
      ],
    );
  }
}
