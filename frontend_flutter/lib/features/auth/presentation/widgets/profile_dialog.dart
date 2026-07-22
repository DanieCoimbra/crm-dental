import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/auth/data/auth_repository.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:file_picker/file_picker.dart';

class ProfileDialog extends ConsumerStatefulWidget {
  const ProfileDialog({super.key});

  @override
  ConsumerState<ProfileDialog> createState() => _ProfileDialogState();
}

class _ProfileDialogState extends ConsumerState<ProfileDialog> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _emailCtrl = TextEditingController();
  String? _avatarUrl;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _loadProfile();
  }

  Future<void> _loadProfile() async {
    setState(() => _isLoading = true);
    try {
      final repo = ref.read(authRepositoryProvider);
      final user = await repo.getProfile();
      if (mounted) {
        setState(() {
          _nameCtrl.text = user.name;
          _emailCtrl.text = user.email;
          _avatarUrl = user.avatar;
        });
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _updateProfile() async {
    if (!_formKey.currentState!.validate()) return;
    
    setState(() => _isLoading = true);
    try {
      final repo = ref.read(authRepositoryProvider);
      final data = <String, dynamic>{
        'name': _nameCtrl.text,
        'email': _emailCtrl.text,
      };
      
      await repo.updateProfile(data);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Perfil atualizado com sucesso!')));
        Navigator.pop(context);
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _changeAvatar() async {
    final result = await FilePicker.pickFiles(type: FileType.image, withData: true);
    if (result == null || result.files.single.bytes == null) return;

    setState(() => _isLoading = true);
    try {
      final repo = ref.read(authRepositoryProvider);
      final file = result.files.single;
      final user = await repo.updateAvatar(file.bytes!, file.name);
      if (mounted) {
        setState(() => _avatarUrl = user.avatar);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Avatar atualizado com sucesso!')));
      }
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro: $e')));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Meu Perfil'),
      content: SizedBox(
        width: 400,
        child: _isLoading && _nameCtrl.text.isEmpty
            ? const Center(child: CircularProgressIndicator())
            : Form(
                key: _formKey,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    GestureDetector(
                      onTap: _changeAvatar,
                      child: Stack(
                        children: [
                          CircleAvatar(
                            radius: 40,
                            backgroundImage: _avatarUrl != null 
                              ? NetworkImage(_avatarUrl!.startsWith('/uploads') ? 'http://localhost:8080$_avatarUrl' : _avatarUrl!) 
                              : null,
                            child: _avatarUrl == null ? const Icon(LucideIcons.user, size: 40) : null,
                          ),
                          Positioned(
                            bottom: 0,
                            right: 0,
                            child: Container(
                              padding: const EdgeInsets.all(4),
                              decoration: const BoxDecoration(color: Colors.blue, shape: BoxShape.circle),
                              child: const Icon(LucideIcons.camera, size: 16, color: Colors.white),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 24),
                    TextFormField(
                      controller: _nameCtrl,
                      decoration: const InputDecoration(labelText: 'Nome Completo', border: OutlineInputBorder()),
                      validator: (v) => v!.isEmpty ? 'Obrigatório' : null,
                    ),
                    const SizedBox(height: 16),
                    TextFormField(
                      controller: _emailCtrl,
                      decoration: const InputDecoration(labelText: 'E-mail', border: OutlineInputBorder()),
                      validator: (v) => v!.isEmpty ? 'Obrigatório' : null,
                    ),
                  ],
                ),
              ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancelar')),
        ElevatedButton(
          onPressed: _isLoading ? null : _updateProfile,
          child: _isLoading 
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)) 
              : const Text('Salvar'),
        ),
      ],
    );
  }
}
