package crm_data.scheduling.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.scheduling.service.AppointmentSlotRules.Reason;
import lombok.RequiredArgsConstructor;

/**
 * Só leitura: descobre quais agendamentos futuros em aberto passam a violar uma configuração proposta (expediente) ou
 * um feriado novo. Não altera nenhum agendamento: quem decide o que fazer é o dentista.
 */
@Component
@RequiredArgsConstructor
public class AffectedAppointmentsFinder {

	private final AppointmentRepository appointments;
	private final PatientLookup patients;
	private final Clock clock;

	/**
	 * Avalia contra a configuração resultante completa (dia sem linha = fechado). Feriados ficam de fora: têm aviso
	 * próprio ({@link #findOnHoliday}). Considera dia fechado, fora do horário e almoço.
	 */
	@Transactional(readOnly = true)
	public AffectedAppointments find(Map<DayOfWeek, DaySchedule> resulting, ZoneId zone) {
		return collect(a -> AppointmentSlotRules.checkHours(a.getStartsAt(), a.getEndsAt(), zone, resulting, Set.of()));
	}

	/** Agendamentos futuros em aberto que tocam a data do feriado (motivo {@code HOLIDAY}). */
	@Transactional(readOnly = true)
	public AffectedAppointments findOnHoliday(LocalDate date, ZoneId zone) {
		return collect(a -> AppointmentSlotRules.touchesDate(a.getStartsAt(), a.getEndsAt(), zone, date)
				? Optional.of(Reason.HOLIDAY) : Optional.empty());
	}

	private AffectedAppointments collect(java.util.function.Function<Appointment, Optional<Reason>> rule) {
		Instant now = clock.instant();
		List<Appointment> open = appointments.findOpenFuture(now, now.minus(SchedulingRules.SEARCH_LOOKBACK));
		List<Appointment> affected = new ArrayList<>();
		List<Reason> reasons = new ArrayList<>();
		for (Appointment appointment : open) {
			Optional<Reason> reason = rule.apply(appointment);
			if (reason.isPresent()) {
				affected.add(appointment);
				reasons.add(reason.get());
			}
		}
		if (affected.isEmpty()) {
			return AffectedAppointments.NONE;
		}
		int shown = Math.min(affected.size(), SchedulingRules.MAX_AFFECTED_ITEMS);
		List<UUID> patientIds = affected.subList(0, shown).stream().map(Appointment::getPatientId).distinct().toList();
		Map<UUID, PatientBrief> briefs = patients.briefs(patientIds);
		List<AffectedAppointments.Item> items = new ArrayList<>(shown);
		for (int i = 0; i < shown; i++) {
			Appointment a = affected.get(i);
			items.add(new AffectedAppointments.Item(a.getId(), briefs.get(a.getPatientId()), a.getStartsAt(), a.getEndsAt(),
					a.getStatus(), reasons.get(i)));
		}
		return new AffectedAppointments(items, affected.size(), affected.size() > shown);
	}
}
