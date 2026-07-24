import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:calendar_view/calendar_view.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_provider.dart';
import 'package:frontend_flutter/features/schedule/data/appointment_model.dart';
import 'package:frontend_flutter/features/schedule/data/waitlist_model.dart';
import 'package:frontend_flutter/features/schedule/presentation/widgets/appointment_form_dialog.dart';
import 'package:frontend_flutter/features/schedule/presentation/widgets/waitlist_form_dialog.dart';
import 'package:frontend_flutter/features/schedule/presentation/widgets/smart_booking_dialog.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_repository.dart';
import 'package:frontend_flutter/features/auth/providers/auth_provider.dart';

class ScheduleScreen extends ConsumerStatefulWidget {
  const ScheduleScreen({super.key});

  @override
  ConsumerState<ScheduleScreen> createState() => _ScheduleScreenState();
}

class _ScheduleScreenState extends ConsumerState<ScheduleScreen> {
  final EventController<Appointment> _eventController = EventController<Appointment>();
  final GlobalKey<WeekViewState> _weekViewStateKey = GlobalKey<WeekViewState>();
  List<Waitlist>? _smartBookingMatches;
  DateTime? _freedTime;
  int _freedDoctorId = 0;

  @override
  Widget build(BuildContext context) {
    final appointmentsAsync = ref.watch(appointmentsProvider(null));
    final currentUserAsync = ref.watch(currentUserProvider);
    final currentUser = currentUserAsync.value;

    // Quando os dados chegarem, atualizamos os eventos do calendário
    appointmentsAsync.whenData((appointments) {
      _eventController.removeWhere((e) => true); // limpa antigos
      
      final events = appointments.map((appt) {
        return CalendarEventData<Appointment>(
          date: appt.startTime,
          startTime: appt.startTime,
          endTime: appt.endTime,
          title: '${appt.patient?.name ?? 'Paciente sem nome'} - ${appt.appointmentType?.name ?? 'Consulta'}',
          description: appt.notes,
          color: _getColor(appt.appointmentType?.color),
          event: appt,
        );
      }).toList();
      
      _eventController.addAll(events);
    });

    return Scaffold(
      body: Container(
        color: Theme.of(context).scaffoldBackgroundColor,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Calendário Principal
            Expanded(
              flex: 3,
              child: Column(
                children: [
                  if (_smartBookingMatches != null && _smartBookingMatches!.isNotEmpty)
                    Container(
                      margin: const EdgeInsets.all(16),
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.primaryContainer.withValues(alpha: 0.2),
                        border: Border.all(color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.3)),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(
                        children: [
                          Icon(LucideIcons.lightbulb, color: Theme.of(context).colorScheme.primary),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Text(
                              'Há ${_smartBookingMatches!.length} paciente(s) na fila de espera que podem ser encaixados no horário liberado.',
                              style: TextStyle(color: Theme.of(context).textTheme.bodyMedium?.color),
                            ),
                          ),
                          TextButton(
                            onPressed: () {
                              showDialog(
                                context: context,
                                builder: (_) => SmartBookingDialog(
                                  matches: _smartBookingMatches!,
                                  availableTime: _freedTime!,
                                  doctorId: _freedDoctorId,
                                ),
                              ).then((_) {
                                setState(() => _smartBookingMatches = null);
                              });
                            },
                            child: const Text('Ver Sugestões'),
                          ),
                          Semantics(
                            label: 'Fechar sugestões de encaixe',
                            button: true,
                            child: IconButton(
                              icon: Icon(LucideIcons.x, color: Theme.of(context).colorScheme.primary),
                              onPressed: () => setState(() => _smartBookingMatches = null),
                            ),
                          )
                        ],
                      ),
                    ),
                  Expanded(
                    child: appointmentsAsync.when(
                      loading: () => const Center(child: CircularProgressIndicator()),
                      error: (e, _) => Center(child: Text('Erro ao carregar agenda: $e')),
                      data: (appointments) {
                  return LayoutBuilder(
                    builder: (context, constraints) {
                      return WeekView<Appointment>(
                        key: _weekViewStateKey,
                        controller: _eventController,
                        showLiveTimeLineInAllDays: true,
                        width: constraints.maxWidth,
                        weekTitleHeight: 65,
                        minDay: DateTime(2020),
                        maxDay: DateTime(2030),
                        initialDay: DateTime.now(),
                        startHour: 0,
                        showHalfHours: true,
                        halfHourIndicatorSettings: HourIndicatorSettings(
                          color: Theme.of(context).dividerTheme.color ?? Theme.of(context).colorScheme.outlineVariant,
                        ),
                        weekNumberBuilder: (date) => const SizedBox.shrink(),
                        heightPerMinute: 1.5,
                        timeLineWidth: 60,
                        timeLineBuilder: (date) {
                          return Transform.translate(
                            offset: const Offset(0, -7.5),
                            child: Padding(
                              padding: const EdgeInsets.only(right: 8.0),
                              child: Text(
                                DateFormat('HH:mm').format(date),
                                textAlign: TextAlign.right,
                                style: TextStyle(fontSize: 12, color: Theme.of(context).textTheme.bodySmall?.color ?? Theme.of(context).colorScheme.onSurfaceVariant),
                              ),
                            ),
                          );
                        },
                        weekPageHeaderBuilder: (startDate, endDate) {
                          return Container(
                            width: double.infinity,
                            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                            decoration: BoxDecoration(

                              border: Border(bottom: BorderSide(color: Theme.of(context).dividerTheme.color ?? Theme.of(context).colorScheme.outlineVariant)),
                            ),
                            child: Wrap(
                              alignment: WrapAlignment.spaceBetween,
                              crossAxisAlignment: WrapCrossAlignment.center,
                              runSpacing: 12.0,
                              children: [
                                Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Semantics(
                                      label: 'Semana anterior',
                                      button: true,
                                      child: IconButton(
                                        icon: const Icon(LucideIcons.chevronLeft),
                                        onPressed: () => _weekViewStateKey.currentState?.previousPage(),
                                        tooltip: 'Semana Anterior',
                                      ),
                                    ),
                                    const SizedBox(width: 4),
                                    Text(
                                      '${DateFormat('dd/MM/yyyy').format(startDate)} a ${DateFormat('dd/MM/yyyy').format(endDate)}',
                                      style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: Theme.of(context).textTheme.bodyMedium?.color),
                                    ),
                                    const SizedBox(width: 4),
                                    Semantics(
                                      label: 'Próxima semana',
                                      button: true,
                                      child: IconButton(
                                        icon: const Icon(LucideIcons.chevronRight),
                                        onPressed: () => _weekViewStateKey.currentState?.nextPage(),
                                        tooltip: 'Próxima Semana',
                                      ),
                                    ),
                                  ],
                                ),
                                Wrap(
                                  spacing: 8.0,
                                  runSpacing: 8.0,
                                  crossAxisAlignment: WrapCrossAlignment.center,
                                  children: [
                                    TextButton.icon(
                                      icon: const Icon(LucideIcons.calendar, size: 18),
                                      label: const Text('Hoje'),
                                      onPressed: () {
                                        _weekViewStateKey.currentState?.jumpToWeek(DateTime.now());
                                      },
                                    ),
                                    Semantics(
                                      label: 'Atualizar agenda',
                                      button: true,
                                      child: IconButton(
                                        icon: const Icon(LucideIcons.refreshCw),
                                        tooltip: 'Atualizar',
                                        onPressed: () => ref.invalidate(appointmentsProvider(null)),
                                      ),
                                    ),
                                    ElevatedButton.icon(
                                      icon: const Icon(LucideIcons.plus, size: 18),
                                      label: const Text('Nova Consulta'),
                                      onPressed: () async {
                                        final result = await showDialog(
                                          context: context,
                                          builder: (_) => const AppointmentFormDialog(),
                                        );
                                        if (result == true) {}
                                      },
                                    ),
                                  ],
                                )
                              ],
                            ),
                          );
                        },
                        eventTileBuilder: (date, events, boundary, start, end) {
                      if (events.isEmpty) return const SizedBox.shrink();
                      final event = events.first;
                      return ClipRect(
                        child: Container(
                          decoration: BoxDecoration(
                            color: event.color,
                            borderRadius: BorderRadius.circular(4),
                          ),
                          padding: const EdgeInsets.all(4.0),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                event.title,
                                style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              if (event.description != null && event.description!.isNotEmpty)
                                Flexible(
                                  child: Text(
                                    event.description!,
                                    style: const TextStyle(color: Colors.white, fontSize: 10),
                                    maxLines: 2,
                                    overflow: TextOverflow.fade,
                                  ),
                                ),
                            ],
                          ),
                        ),
                      );
                    },
                    onEventTap: (events, date) {
                      final appt = events.first.event;
                      if (appt != null) {
                        showDialog(
                          context: context,
                          builder: (dialogContext) {
                            final isDoctorOrReceptionist = currentUser?.role?.name == 'doctor' || currentUser?.role?.name == 'dentist' || currentUser?.role?.name == 'receptionist';
                            final scheduledStart = DateFormat('dd/MM/yyyy HH:mm').format(appt.startTime);
                            final actualStart = appt.actualStartTime != null ? DateFormat('HH:mm:ss').format(appt.actualStartTime!) : null;
                            final actualEnd = appt.actualEndTime != null ? DateFormat('HH:mm:ss').format(appt.actualEndTime!) : null;
                            
                            String statusLabel = 'Agendado';
                            Color statusColor = Colors.blue;
                            if (appt.status == 'in_progress') {
                              statusLabel = 'Em Andamento';
                              statusColor = Colors.green;
                            } else if (appt.status == 'completed') {
                              statusLabel = 'Finalizado';
                              statusColor = Colors.grey;
                            }

                            return AlertDialog(
                              title: const Text('Detalhes do Agendamento', style: TextStyle(fontWeight: FontWeight.bold)),
                              content: SingleChildScrollView(
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      children: [
                                        const Text('Status: ', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                                        Container(
                                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                          decoration: BoxDecoration(
                                            color: statusColor.withValues(alpha: 0.1),
                                            borderRadius: BorderRadius.circular(4),
                                          ),
                                          child: Text(
                                            statusLabel, 
                                            style: TextStyle(color: statusColor, fontWeight: FontWeight.bold, fontSize: 14)
                                          ),
                                        ),
                                      ],
                                    ),
                                    const SizedBox(height: 12),
                                    Text('Tipo: ${appt.appointmentType?.name ?? 'Agendamento'}'),
                                    const SizedBox(height: 4),
                                    Text('Paciente: ${appt.patient?.name ?? 'Desconhecido'} (ID: ${appt.patientId})'),
                                    const SizedBox(height: 4),
                                    Text('Médico: ${appt.doctor?.name ?? 'Desconhecido'} (ID: ${appt.doctorId})'),
                                    const SizedBox(height: 4),
                                    Text('Início Previsto: $scheduledStart'),
                                    if (actualStart != null) ...[
                                      const SizedBox(height: 4),
                                      Text('Início Real: $actualStart', style: const TextStyle(color: Colors.green, fontWeight: FontWeight.w500)),
                                    ],
                                    if (actualEnd != null) ...[
                                      const SizedBox(height: 4),
                                      Text('Fim Real: $actualEnd', style: TextStyle(color: Theme.of(context).textTheme.bodySmall?.color, fontWeight: FontWeight.w500)),
                                    ],
                                    const SizedBox(height: 12),
                                    const Text('Comentário:', style: TextStyle(fontWeight: FontWeight.bold)),
                                    SelectableText(appt.notes.isEmpty ? 'Nenhum comentário.' : appt.notes),
                                  ],
                                ),
                              ),
                              actionsAlignment: MainAxisAlignment.spaceBetween,
                              actions: [
                                Row(
                                  children: [
                                    if (isDoctorOrReceptionist && (appt.status == 'scheduled' || appt.status.isEmpty))
                                      ElevatedButton(
                                        style: ElevatedButton.styleFrom(backgroundColor: Colors.teal),
                                        onPressed: () async {
                                          Navigator.pop(dialogContext);
                                          try {
                                            final repo = ref.read(scheduleRepositoryProvider);
                                            await repo.startAppointment(appt.id);
                                            ref.invalidate(appointmentsProvider(null));
                                            if (!context.mounted) return;
                                            ScaffoldMessenger.of(context).showSnackBar(
                                              const SnackBar(content: Text('Atendimento iniciado com sucesso.')),
                                            );
                                          } catch (e) {
                                            if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro ao iniciar: $e')));
                                          }
                                        },
                                        child: const Text('Iniciar Atendimento', style: TextStyle(color: Colors.white)),
                                      ),
                                    if (isDoctorOrReceptionist && appt.status == 'in_progress')
                                      ElevatedButton(
                                        style: ElevatedButton.styleFrom(backgroundColor: Colors.green),
                                        onPressed: () async {
                                          Navigator.pop(dialogContext);
                                          try {
                                            final repo = ref.read(scheduleRepositoryProvider);
                                            await repo.finishAppointment(appt.id);
                                            ref.invalidate(appointmentsProvider(null));
                                            if (!context.mounted) return;
                                            ScaffoldMessenger.of(context).showSnackBar(
                                              const SnackBar(content: Text('Consulta encerrada com sucesso.')),
                                            );

                                            final allAppointments = ref.read(appointmentsProvider(null)).value ?? [];
                                            final now = DateTime.now();
                                            final doctorAppts = allAppointments.where((a) => a.doctorId == appt.doctorId && a.startTime.isAfter(now)).toList();
                                            doctorAppts.sort((a, b) => a.startTime.compareTo(b.startTime));
                                            final nextAppt = doctorAppts.isNotEmpty ? doctorAppts.first : null;

                                            DateTime limitTime;
                                            if (nextAppt != null) {
                                              limitTime = nextAppt.startTime;
                                            } else {
                                              limitTime = DateTime(now.year, now.month, now.day, 18, 0); 
                                            }

                                            if (limitTime.difference(now).inMinutes >= 60) {
                                              final matches = await repo.checkMatches(appt.doctorId, now.toIso8601String());
                                              if (matches.isNotEmpty && context.mounted) {
                                                setState(() {
                                                  _smartBookingMatches = matches;
                                                  _freedTime = now;
                                                  _freedDoctorId = appt.doctorId;
                                                });
                                              }
                                            }
                                          } catch (e) {
                                            if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro ao encerrar: $e')));
                                          }
                                        },
                                        child: const Text('Finalizar Atendimento', style: TextStyle(color: Colors.white)),
                                      ),
                                  ],
                                ),
                                Row(
                                  children: [
                                    if (currentUser?.role?.name != 'doctor' && currentUser?.role?.name != 'dentist')
                                      ElevatedButton(
                                        style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                                        onPressed: () async {
                                          Navigator.pop(dialogContext);
                                          try {
                                            final repo = ref.read(scheduleRepositoryProvider);
                                            await repo.deleteAppointment(appt.id);
                                            ref.invalidate(appointmentsProvider(null));
                                            if (!context.mounted) return;
                                            ScaffoldMessenger.of(context).showSnackBar(
                                              const SnackBar(content: Text('Agendamento cancelado com sucesso.')),
                                            );
                                            final matches = await repo.checkMatches(appt.doctorId, appt.startTime.toIso8601String());
                                            if (matches.isNotEmpty && context.mounted) {
                                              setState(() {
                                                _smartBookingMatches = matches;
                                                _freedTime = appt.startTime;
                                                _freedDoctorId = appt.doctorId;
                                              });
                                            }
                                          } catch (e) {
                                            if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro ao cancelar: $e')));
                                          }
                                        },
                                        child: const Text('Cancelar Consulta', style: TextStyle(color: Colors.white)),
                                      ),
                                    const SizedBox(width: 8),
                                    TextButton(
                                      onPressed: () => Navigator.pop(dialogContext),
                                      child: const Text('Fechar'),
                                    ),
                                  ],
                                ),
                              ],
                            );
                          },
                        );
                      }
                    },
                  );
                },
              );
            },
          ),
        ),
      ],
        ),
      ),
            
            // Painel da Lista de Espera (Waitlist)
            Container(
              width: 320,
              decoration: BoxDecoration(

                border: Border(left: BorderSide(color: Theme.of(context).dividerTheme.color ?? Theme.of(context).colorScheme.outlineVariant)),
              ),
              child: const _WaitlistPanel(),
            ),
          ],
        ),
      ),
    );
  }

  Color _getColor(String? hexColor) {
    if (hexColor == null || hexColor.isEmpty) return const Color(0xFF2563EB); // default blue
    hexColor = hexColor.toUpperCase().replaceAll('#', '');
    if (hexColor.length == 6) {
      hexColor = 'FF$hexColor';
    }
    return Color(int.parse(hexColor, radix: 16));
  }
}

class _WaitlistPanel extends ConsumerWidget {
  const _WaitlistPanel();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final waitlistAsync = ref.watch(waitlistsProvider);
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: theme.dividerTheme.color ?? theme.colorScheme.outlineVariant)),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: [
                  Icon(LucideIcons.list, size: 20, color: theme.textTheme.bodyMedium?.color),
                  const SizedBox(width: 8),
                  const Text(
                    'Lista de Espera',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                  ),
                ],
              ),
              Semantics(
                label: 'Adicionar paciente na fila de espera',
                button: true,
                child: IconButton(
                  icon: Icon(LucideIcons.plus, color: theme.colorScheme.primary),
                  onPressed: () {
                    showDialog(
                      context: context,
                      builder: (context) => const WaitlistFormDialog(),
                    );
                  },
                  tooltip: 'Adicionar Paciente',
                ),
              ),
            ],
          ),
        ),
        Expanded(
          child: waitlistAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text('Erro: $e')),
            data: (list) {
              if (list.isEmpty) {
                return Center(
                  child: Text('Nenhum paciente na fila.', style: TextStyle(color: theme.textTheme.bodyMedium?.color)),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: list.length,
                separatorBuilder: (_, _) => const SizedBox(height: 12),
                itemBuilder: (context, index) {
                  final w = list[index];
                  return InkWell(
                    onTap: () {
                      showDialog(
                        context: context,
                        builder: (context) => AlertDialog(
                          title: Text('Detalhes: ${w.patient?.name ?? 'Desconhecido'}'),
                          content: Column(
                            mainAxisSize: MainAxisSize.min,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('Telefone: ${w.patient?.phone ?? 'Não informado'}'),
                              const Divider(),
                              Text('Médico: ${w.doctor?.name ?? 'Qualquer Médico'}'),
                              Text('Especialidade: ${w.appointmentType?.name ?? 'Qualquer Tipo'}'),
                              const Divider(),
                              Text('Turno Preferencial: ${w.preferredTimeRange}'),
                              if (w.preferredDays.isNotEmpty) Text('Dias: ${w.preferredDays}'),
                              const SizedBox(height: 8),
                              Text('Urgência: ${w.urgencyLevel}'),
                              if (w.notes.isNotEmpty) ...[
                                const Divider(),
                                const Text('Observações:', style: TextStyle(fontWeight: FontWeight.bold)),
                                Text(w.notes),
                              ]
                            ],
                          ),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.of(context).pop(),
                              child: const Text('Fechar'),
                            ),
                            ElevatedButton(
                              onPressed: () {
                                Navigator.pop(context);
                                showDialog(
                                  context: context,
                                  builder: (_) => WaitlistFormDialog(waitlist: w),
                                );
                              },
                              child: const Text('Editar'),
                            ),
                            ElevatedButton(
                              style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                              onPressed: () async {
                                final confirm = await showDialog<bool>(
                                  context: context,
                                  builder: (ctx) => AlertDialog(
                                    title: const Text('Confirmar exclusão'),
                                    content: const Text('Deseja realmente remover este paciente da fila de espera?'),
                                    actions: [
                                      TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancelar')),
                                      ElevatedButton(
                                        style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                                        onPressed: () => Navigator.pop(ctx, true),
                                        child: const Text('Excluir', style: TextStyle(color: Colors.white)),
                                      ),
                                    ],
                                  )
                                );
                                if (confirm == true && context.mounted) {
                                  Navigator.pop(context);
                                  try {
                                    await ref.read(scheduleRepositoryProvider).deleteWaitlist(w.id);
                                    ref.invalidate(waitlistsProvider);
                                    if (context.mounted) {
                                      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Removido da fila com sucesso')));
                                    }
                                  } catch (e) {
                                    if (context.mounted) {
                                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erro ao excluir: $e')));
                                    }
                                  }
                                }
                              },
                              child: const Text('Excluir', style: TextStyle(color: Colors.white)),
                            ),
                          ],
                        ),
                      );
                    },
                    child: Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: theme.colorScheme.surface,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: theme.dividerTheme.color ?? theme.colorScheme.outlineVariant),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withValues(alpha: 0.02),
                            blurRadius: 4,
                            offset: const Offset(0, 2),
                          )
                        ],
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(w.patient?.name ?? 'Desconhecido', style: const TextStyle(fontWeight: FontWeight.w600)),
                          const SizedBox(height: 4),
                          Row(
                            children: [
                              const Icon(LucideIcons.alertTriangle, size: 14, color: Colors.orange),
                              const SizedBox(width: 4),
                              Text('Urgência: ${w.urgencyLevel}', style: TextStyle(fontSize: 12, color: theme.textTheme.bodySmall?.color)),
                            ],
                          ),
                          if (w.notes.isNotEmpty) ...[
                            const SizedBox(height: 8),
                            Text(w.notes, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, fontStyle: FontStyle.italic)),
                          ]
                        ],
                      ),
                    ),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}



