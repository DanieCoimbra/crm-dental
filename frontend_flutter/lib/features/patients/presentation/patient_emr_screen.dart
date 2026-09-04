import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/patients/data/patients_provider.dart';
import 'package:frontend_flutter/shared/widgets/require_role.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/patient_summary_tab.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/odontogram_tab.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/clinical_evolution_tab.dart';
import 'package:frontend_flutter/features/patients/presentation/widgets/patient_files_tab.dart';

class PatientEmrScreen extends ConsumerStatefulWidget {
  final int patientId;
  const PatientEmrScreen({super.key, required this.patientId});

  @override
  ConsumerState<PatientEmrScreen> createState() => _PatientEmrScreenState();
}

class _PatientEmrScreenState extends ConsumerState<PatientEmrScreen> {
  @override
  Widget build(BuildContext context) {
    final patientAsync = ref.watch(patientDetailProvider(widget.patientId));
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(LucideIcons.arrowLeft),
          onPressed: () {
            if (context.canPop()) {
              context.pop();
            } else {
              context.go('/patients');
            }
          },
        ),
        title: const Text('Prontuário Eletrônico (EMR)'),
        scrolledUnderElevation: 0,
      ),
      body: patientAsync.when(
        data: (patient) {
          return DefaultTabController(
            length: 4,
            child: Column(
              children: [
                // Top Patient Header Bar
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 16.0),
                  decoration: BoxDecoration(
                    color: theme.cardColor,
                    border: Border(bottom: BorderSide(color: theme.dividerColor)),
                  ),
                  child: Row(
                    children: [
                      // Initials Avatar
                      CircleAvatar(
                        radius: 28,
                        backgroundColor: theme.colorScheme.primary.withValues(alpha: 0.15),
                        child: Text(
                          patient.initials,
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: theme.colorScheme.primary,
                          ),
                        ),
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Flexible(
                                  child: Text(
                                    patient.name,
                                    style: TextStyle(
                                      fontSize: 20,
                                      fontWeight: FontWeight.bold,
                                      color: theme.textTheme.titleLarge?.color,
                                    ),
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                                if (patient.healthInsurance != null && patient.healthInsurance!.isNotEmpty) ...[
                                  const SizedBox(width: 10),
                                  Container(
                                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
                                    decoration: BoxDecoration(
                                      color: Colors.blue.withValues(alpha: 0.1),
                                      borderRadius: BorderRadius.circular(10),
                                      border: Border.all(color: Colors.blue.withValues(alpha: 0.3)),
                                    ),
                                    child: Text(
                                      patient.healthInsurance!,
                                      style: const TextStyle(
                                        fontSize: 12,
                                        color: Colors.blue,
                                        fontWeight: FontWeight.bold,
                                      ),
                                    ),
                                  ),
                                ],
                              ],
                            ),
                            const SizedBox(height: 4),
                            Wrap(
                              spacing: 16,
                              children: [
                                Text(
                                  'CPF: ${patient.cpf ?? 'Não informado'}',
                                  style: TextStyle(fontSize: 13, color: theme.textTheme.bodyMedium?.color),
                                ),
                                Text(
                                  'Nasc: ${patient.birthDate ?? 'Não informado'}',
                                  style: TextStyle(fontSize: 13, color: theme.textTheme.bodyMedium?.color),
                                ),
                                Text(
                                  'Tel: ${patient.phone ?? 'Não informado'}',
                                  style: TextStyle(fontSize: 13, color: theme.textTheme.bodyMedium?.color),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),

                // Core Clinical Tabs Header
                Material(
                  color: theme.cardColor,
                  child: TabBar(
                    labelColor: theme.colorScheme.primary,
                    unselectedLabelColor: theme.textTheme.bodyMedium?.color,
                    indicatorColor: theme.colorScheme.primary,
                    indicatorWeight: 3,
                    tabs: const [
                      Tab(
                        icon: Icon(LucideIcons.user, size: 18),
                        text: 'Resumo & Anamnese',
                      ),
                      Tab(
                        icon: Icon(LucideIcons.sparkles, size: 18),
                        text: 'Odontograma Interativo',
                      ),
                      Tab(
                        icon: Icon(LucideIcons.fileText, size: 18),
                        text: 'Evolução Clínica',
                      ),
                      Tab(
                        icon: Icon(LucideIcons.fileImage, size: 18),
                        text: 'Exames & Radiografias',
                      ),
                    ],
                  ),
                ),
                const Divider(height: 1),

                // Core Clinical TabBarViews
                Expanded(
                  child: TabBarView(
                    children: [
                      // TAB 1: Resumo & Anamnese
                      PatientSummaryTab(patient: patient),

                      // TAB 2: Odontograma Interativo (Adulto & Decíduo)
                      OdontogramTab(patientId: widget.patientId),

                      // TAB 3: Evolução Clínica (Timeline LGPD)
                      RequireRole(
                        allowedRoles: const ['admin', 'owner', 'doctor', 'dentist'],
                        fallback: Center(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(LucideIcons.lock, size: 64, color: Colors.red.shade300),
                              const SizedBox(height: 16),
                              const Text(
                                'Acesso Restrito',
                                style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                              ),
                              const SizedBox(height: 8),
                              const Text('Apenas dentistas/médicos possuem acesso à Evolução Clínica (LGPD).'),
                            ],
                          ),
                        ),
                        child: ClinicalEvolutionTab(patientId: widget.patientId),
                      ),

                      // TAB 4: Exames & Radiografias (Gallery & Lightbox)
                      PatientFilesTab(patientId: widget.patientId),
                    ],
                  ),
                ),
              ],
            ),
          );
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, stack) => Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(LucideIcons.alertTriangle, color: Colors.red, size: 48),
              const SizedBox(height: 12),
              Text('Erro ao carregar prontuário: $err'),
              const SizedBox(height: 16),
              ElevatedButton.icon(
                onPressed: () => ref.invalidate(patientDetailProvider(widget.patientId)),
                icon: const Icon(LucideIcons.refreshCw),
                label: const Text('Tentar Novamente'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
