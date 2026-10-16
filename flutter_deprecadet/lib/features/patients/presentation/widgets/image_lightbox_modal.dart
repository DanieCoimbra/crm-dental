import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/patient_file_model.dart';
import 'package:frontend_flutter/features/patients/data/patients_repository.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class ImageLightboxModal extends ConsumerStatefulWidget {
  final PatientFile file;

  const ImageLightboxModal({super.key, required this.file});

  static void show(BuildContext context, PatientFile file) {
    showDialog(
      context: context,
      barrierColor: Colors.black.withValues(alpha: 0.9),
      builder: (_) => ImageLightboxModal(file: file),
    );
  }

  @override
  ConsumerState<ImageLightboxModal> createState() => _ImageLightboxModalState();
}

class _ImageLightboxModalState extends ConsumerState<ImageLightboxModal> {
  final TransformationController _transformationController = TransformationController();
  double _scale = 1.0;

  void _resetZoom() {
    setState(() {
      _transformationController.value = Matrix4.identity();
      _scale = 1.0;
    });
  }

  @override
  void dispose() {
    _transformationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Stack(
        children: [
          // Center Image with Interactive Zoom & Pan
          Center(
            child: InteractiveViewer(
              transformationController: _transformationController,
              minScale: 0.5,
              maxScale: 4.0,
              onInteractionUpdate: (details) {
                setState(() {
                  _scale = _transformationController.value.getMaxScaleOnAxis();
                });
              },
              child: Image.network(
                widget.file.fileUrl,
                fit: BoxFit.contain,
                loadingBuilder: (context, child, loadingProgress) {
                  if (loadingProgress == null) return child;
                  return Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      CircularProgressIndicator(
                        value: loadingProgress.expectedTotalBytes != null
                            ? loadingProgress.cumulativeBytesLoaded / loadingProgress.expectedTotalBytes!
                            : null,
                        color: Colors.white,
                      ),
                      const SizedBox(height: 16),
                      const Text('Carregando imagem em alta resolução...', style: TextStyle(color: Colors.white)),
                    ],
                  );
                },
                errorBuilder: (context, error, stackTrace) {
                  return Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Icon(LucideIcons.alertTriangle, color: Colors.amber, size: 48),
                      const SizedBox(height: 16),
                      const Text(
                        'Erro ao carregar imagem do servidor',
                        style: TextStyle(color: Colors.white, fontSize: 16),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        error.toString(),
                        style: const TextStyle(color: Colors.grey, fontSize: 12),
                      ),
                    ],
                  );
                },
              ),
            ),
          ),

          // Top Control Bar
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
              color: Colors.black.withValues(alpha: 0.7),
              child: SafeArea(
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            widget.file.fileName,
                            style: const TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.bold,
                              fontSize: 16,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Categoria: ${widget.file.category} | Tamanho: ${widget.file.formattedSize}',
                            style: const TextStyle(color: Colors.white70, fontSize: 12),
                          ),
                        ],
                      ),
                    ),
                    Row(
                      children: [
                        if (_scale != 1.0)
                          IconButton(
                            icon: const Icon(LucideIcons.rotateCcw, color: Colors.white),
                            tooltip: 'Resetar Zoom',
                            onPressed: _resetZoom,
                          ),
                        IconButton(
                          icon: const Icon(LucideIcons.externalLink, color: Colors.white),
                          tooltip: 'Abrir no navegador',
                          onPressed: () {
                            ref.read(patientsRepositoryProvider).openPatientFile(widget.file.fileUrl);
                          },
                        ),
                        IconButton(
                          icon: const Icon(LucideIcons.x, color: Colors.white, size: 28),
                          tooltip: 'Fechar',
                          onPressed: () => Navigator.of(context).pop(),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),

          // Bottom Zoom Indicator Bar
          Positioned(
            bottom: 20,
            left: 0,
            right: 0,
            child: Center(
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.black.withValues(alpha: 0.6),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(
                  'Zoom: ${(_scale * 100).toInt()}% | Use pinça ou roda do mouse para ampliar',
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
