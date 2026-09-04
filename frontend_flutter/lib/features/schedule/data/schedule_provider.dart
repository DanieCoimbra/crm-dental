import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:frontend_flutter/features/schedule/data/appointment_model.dart';
import 'package:frontend_flutter/features/schedule/data/appointment_type_model.dart';
import 'package:frontend_flutter/features/schedule/data/schedule_repository.dart';
import 'package:frontend_flutter/features/schedule/data/waitlist_model.dart';
import 'package:frontend_flutter/features/schedule/data/shift_assignment_model.dart';

final appointmentsProvider = FutureProvider.family<List<Appointment>, Map<String, String>?>((ref, query) {
  final repository = ref.watch(scheduleRepositoryProvider);
  return repository.getAppointments(query?['start'], query?['end']);
});

final appointmentTypesProvider = FutureProvider<List<AppointmentType>>((ref) {
  final repository = ref.watch(scheduleRepositoryProvider);
  return repository.getAppointmentTypes();
});

final waitlistsProvider = FutureProvider<List<Waitlist>>((ref) {
  final repository = ref.watch(scheduleRepositoryProvider);
  return repository.getWaitlists();
});

final shiftAssignmentsProvider = FutureProvider<List<ShiftAssignment>>((ref) {
  final repository = ref.watch(scheduleRepositoryProvider);
  return repository.getShiftAssignments();
});

final confirmAppointmentProvider = FutureProvider.family<Appointment, int>((ref, id) {
  final repository = ref.watch(scheduleRepositoryProvider);
  return repository.confirmAppointment(id);
});

final missAppointmentProvider = FutureProvider.family<Appointment, int>((ref, id) {
  final repository = ref.watch(scheduleRepositoryProvider);
  return repository.missAppointment(id);
});

final whatsAppLinkProvider = FutureProvider.family<String, int>((ref, id) {
  final repository = ref.watch(scheduleRepositoryProvider);
  return repository.getWhatsAppLink(id);
});
