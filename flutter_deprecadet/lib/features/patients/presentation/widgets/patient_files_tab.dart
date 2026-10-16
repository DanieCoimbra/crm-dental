import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:file_picker/file_picker.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/patient_file_model.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/image_lightbox_modal.dart';

class PatientFilesTab extends ConsumerStatefulWidget {
  final int patientId;

  const PatientFilesTab({super.key, required this.patientId});

  @override
  ConsumerState<PatientFilesTab> createState() => _PatientFilesTabState();
}

class _PatientFilesTabState extends ConsumerState<PatientFilesTab> {
  String _selectedCategoryFilter = 'TODOS';
  bool _isUploading = false;

  final List<String> _categories = [
    'TODOS',
    'RADIOGRAFIA',
    'FOTO_INTRAORAL',
    'EXAME_LABORATORIAL',
    'OUTRO',
  ];

  Future<void> _handleUpload() async {
    final result = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['pdf', 'jpg', 'jpeg', 'png'],
      withData: true,
    );

    if (result == null || result.files.isEmpty) return;
    final pickedFile = result.files.first;

    String uploadCategory = 'RADIOGRAFIA';

    if (!mounted) return;

    final categoryConfirmed = await showDialog<String>(
      context: context,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (context, setStateDialog) {
            return AlertDialog(
              title: const Text('Selecionar Categoria do Anexo'),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Arquivo selecionado: ${pickedFile.name}'),
                  const SizedBox(height: 16),
                  DropdownButtonFormField<String>(
                    initialValue: uploadCategory,
                    decoration: const InputDecoration(
                      labelText: 'Categoria do Documento / Anexo',
                      border: OutlineInputBorder(),
                    ),
                    items: const [
                      DropdownMenuItem(value: 'RADIOGRAFIA', child: Text('Radiografia Intraoral / Panorâmica')),
                      DropdownMenuItem(value: 'FOTO_INTRAORAL', child: Text('Foto Intraoral')),
                      DropdownMenuItem(value: 'EXAME_LABORATORIAL', child: Text('Exame Laboratorial')),
                      DropdownMenuItem(value: 'OUTRO', child: Text('Outro Documento')),
                    ],
                    onChanged: (val) {
                      if (val != null) {
                        setStateDialog(() {
                          uploadCategory = val;
                        });
                      }
                    },
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(ctx).pop(null),
                  child: const Text('Cancelar'),
                ),
                ElevatedButton.icon(
                  onPressed: () => Navigator.of(ctx).pop(uploadCategory),
                  icon: const Icon(LucideIcons.upload),
                  label: const Text('Confirmar Envio'),
                ),
              ],
            );
          },
        );
      },
    );

    if (categoryConfirmed == null) return;

    setState(() {
      _isUploading = true;
    });

    try {
      final repo = ref.read(patientsRepositoryProvider);
      await repo.uploadPatientFile(widget.patientId, pickedFile, categoryConfirmed);

      ref.invalidate(patientFilesProvider(widget.patientId));

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Arquivo enviado com sucesso!'),
            backgroundColor: Colors.green,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Erro ao enviar arquivo: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isUploading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final filesAsync = ref.watch(patientFilesProvider(widget.patientId));
    final theme = Theme.of(context);

    return Scaffold(
      backgroundColor: theme.scaffoldBackgroundColor,
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _isUploading ? null : _handleUpload,
        icon: _isUploading
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2),
              )
            : const Icon(LucideIcons.uploadCloud),
        label: Text(_isUploading ? 'Enviando...' : 'Novo Anexo / Radiografia'),
      ),
      body: Column(
        children: [
          // Filter Bar
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            color: theme.cardColor,
            child: Row(
              children: [
                const Icon(LucideIcons.filter, size: 18),
                const SizedBox(width: 8),
                const Text('Filtrar Categoria:', style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(width: 12),
                Expanded(
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: _categories.map((cat) {
                        final isSel = _selectedCategoryFilter == cat;
                        return Padding(
                          padding: const EdgeInsets.only(right: 8.0),
                          child: ChoiceChip(
                            label: Text(cat == 'TODOS' ? 'Todos os Exames' : cat),
                            selected: isSel,
                            onSelected: (val) {
                              if (val) {
                                setState(() {
                                  _selectedCategoryFilter = cat;
                                });
                              }
                            },
                          ),
                        );
                      }).toList(),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const Divider(height: 1),

          // File Gallery Grid
          Expanded(
            child: filesAsync.when(
              data: (filesList) {
                final filtered = _selectedCategoryFilter == 'TODOS'
                    ? filesList
                    : filesList.where((f) => f.category == _selectedCategoryFilter).toList();

                if (filtered.isEmpty) {
                  return Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(LucideIcons.fileImage, size: 64, color: theme.disabledColor),
                        const SizedBox(height: 16),
                        Text(
                          'Nenhum anexo ou radiografia encontrada.',
                          style: TextStyle(fontSize: 16, color: theme.textTheme.bodyMedium?.color),
                        ),
                        const SizedBox(height: 8),
                        const Text('Clique em "Novo Anexo / Radiografia" para fazer o upload.'),
                      ],
                    ),
                  );
                }

                return GridView.builder(
                  padding: const EdgeInsets.all(20),
                  gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                    maxCrossAxisExtent: 260,
                    childAspectRatio: 0.85,
                    crossAxisSpacing: 16,
                    mainAxisSpacing: 16,
                  ),
                  itemCount: filtered.length,
                  itemBuilder: (ctx, idx) {
                    final file = filtered[idx];
                    return _buildFileCard(context, file, theme);
                  },
                );
              },
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (err, _) => Center(child: Text('Erro ao carregar arquivos: $err')),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFileCard(BuildContext context, PatientFile file, ThemeData theme) {
    return Card(
      elevation: 0,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: theme.dividerColor),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // File Preview Area
          Expanded(
            child: GestureDetector(
              onTap: () {
                if (file.isImage) {
                  ImageLightboxModal.show(context, file);
                } else {
                  ref.read(patientsRepositoryProvider).openPatientFile(file.fileUrl);
                }
              },
              child: Stack(
                fit: StackFit.expand,
                children: [
                  if (file.isImage)
                    Image.network(
                      file.fileUrl,
                      fit: BoxFit.cover,
                      errorBuilder: (ctx, err, stack) => Container(
                        color: Colors.grey.shade200,
                        child: const Icon(LucideIcons.imageOff, size: 40, color: Colors.grey),
                      ),
                    )
                  else
                    Container(
                      color: const Color(0xFF2563EB).withValues(alpha: 0.1),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(
                            file.isPdf ? LucideIcons.fileText : LucideIcons.file,
                            size: 48,
                            color: const Color(0xFF2563EB),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            file.isPdf ? 'Documento PDF' : 'Anexo',
                            style: const TextStyle(fontWeight: FontWeight.bold, color: Color(0xFF2563EB)),
                          ),
                        ],
                      ),
                    ),
                  Positioned(
                    top: 8,
                    right: 8,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: 0.65),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        file.category,
                        style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.bold),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),

          // File Footer Details
          Padding(
            padding: const EdgeInsets.all(12.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  file.fileName,
                  style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 4),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      file.formattedSize,
                      style: TextStyle(fontSize: 11, color: theme.textTheme.bodySmall?.color),
                    ),
                    Row(
                      children: [
                        IconButton(
                          icon: Icon(file.isImage ? LucideIcons.eye : LucideIcons.externalLink, size: 18),
                          tooltip: file.isImage ? 'Visualizar Radiografia (Lightbox)' : 'Abrir Arquivo',
                          onPressed: () {
                            if (file.isImage) {
                              ImageLightboxModal.show(context, file);
                            } else {
                              ref.read(patientsRepositoryProvider).openPatientFile(file.fileUrl);
                            }
                          },
                        ),
                        IconButton(
                          icon: const Icon(LucideIcons.trash2, size: 18, color: Colors.red),
                          tooltip: 'Excluir Arquivo',
                          onPressed: () async {
                            final confirm = await showDialog<bool>(
                              context: context,
                              builder: (ctx) => AlertDialog(
                                title: const Text('Excluir Anexo'),
                                content: Text('Deseja realmente excluir o arquivo "${file.fileName}"?'),
                                actions: [
                                  TextButton(
                                    onPressed: () => Navigator.of(ctx).pop(false),
                                    child: const Text('Cancelar'),
                                  ),
                                  ElevatedButton(
                                    style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                                    onPressed: () => Navigator.of(ctx).pop(true),
                                    child: const Text('Excluir', style: TextStyle(color: Colors.white)),
                                  ),
                                ],
                              ),
                            );

                            if (confirm == true) {
                              await ref.read(patientsRepositoryProvider).deletePatientFile(file.id);
                              ref.invalidate(patientFilesProvider(widget.patientId));
                            }
                          },
                        ),
                      ],
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
