package crm_data.scheduling.service;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.clinic.service.ClinicZones;
import crm_data.scheduling.domain.BusinessHour;
import crm_data.scheduling.repository.BusinessHourRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * Expediente semanal da clínica: leitura dos 7 dias (qualquer papel) e configuração em lote (só o dentista). Dia sem
 * linha é "não configurado" e conta como fechado. A mudança nunca é bloqueada por agendamentos existentes: o aviso de
 * impacto é informativo ({@link AffectedAppointmentsFinder}).
 */
@Service
@RequiredArgsConstructor
public class BusinessHoursService {

	private static final String STALE = "O horário comercial foi alterado por outra pessoa. Recarregue e tente novamente.";

	/** Um dia da semana como o cliente o vê. {@code version} é nula se o dia ainda não foi configurado. */
	public record DayView(DayOfWeek day, boolean configured, DaySchedule schedule, Long version) {
	}

	/** Entrada de um dia no {@code PUT}; {@code version} é nula só ao criar o dia. */
	public record DayInput(DayOfWeek day, DaySchedule schedule, Long version) {
	}

	/** Resultado do {@code PUT}: {@code saved=false} em {@code dryRun}. */
	public record UpdateResult(boolean saved, List<DayView> days, AffectedAppointments affected) {
	}

	private final BusinessHourRepository hours;
	private final AffectedAppointmentsFinder finder;
	private final ClinicZones zones;
	private final ApplicationEventPublisher events;

	/** Expediente por dia da semana, para a regra de horário (F03); dia ausente = fechado. */
	@Transactional(readOnly = true)
	public Map<DayOfWeek, DaySchedule> weekSchedule() {
		Map<DayOfWeek, DaySchedule> schedule = new EnumMap<>(DayOfWeek.class);
		for (BusinessHour hour : hours.findAll()) {
			schedule.put(hour.dayOfWeek(), DaySchedule.of(hour));
		}
		return schedule;
	}

	@Transactional(readOnly = true)
	public List<DayView> week() {
		return views(byDay(hours.findAll()));
	}

	/**
	 * @param dryRun valida e calcula o aviso sem gravar nem publicar evento
	 * @throws BusinessException {@code conflict} (409) se a {@code version} de um dia existente estiver ausente ou defasada
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public UpdateResult update(List<DayInput> inputs, boolean dryRun) {
		Map<DayOfWeek, BusinessHour> current = byDay(hours.findAll());
		for (DayInput input : inputs) {
			BusinessHour existing = current.get(input.day());
			Long expected = existing == null ? null : existing.getVersion();
			if (!java.util.Objects.equals(expected, input.version())) {
				throw new BusinessException(ProblemTypes.CONFLICT, STALE);
			}
		}
		// configuração resultante: dias do corpo sobre o estado atual; dia sem linha e fora do corpo = fechado
		Map<DayOfWeek, DaySchedule> resulting = new EnumMap<>(DayOfWeek.class);
		current.forEach((day, hour) -> resulting.put(day, DaySchedule.of(hour)));
		inputs.forEach(input -> resulting.put(input.day(), input.schedule()));
		AffectedAppointments affected = finder.find(resulting, zones.zone());
		if (dryRun) {
			return new UpdateResult(false, viewsOf(resulting, current, inputs), affected);
		}
		UUID userId = TenantContext.userId();
		List<BusinessHour> changed = new ArrayList<>();
		for (DayInput input : inputs) {
			BusinessHour hour = current.get(input.day());
			if (hour == null) {
				hour = new BusinessHour(UUID.randomUUID(), input.day(), userId);
				current.put(input.day(), hour);
			}
			apply(hour, input.schedule());
			changed.add(hour);
		}
		hours.saveAllAndFlush(changed);
		events.publishEvent(new SchedulingEvents.BusinessHoursChanged(TenantContext.clinicId(), userId, affected.count()));
		return new UpdateResult(true, views(current), affected);
	}

	private static void apply(BusinessHour hour, DaySchedule schedule) {
		hour.setOpen(schedule.open());
		hour.setOpensAt(schedule.opensAt());
		hour.setClosesAt(schedule.closesAt());
		hour.setLunchStartsAt(schedule.lunchStartsAt());
		hour.setLunchEndsAt(schedule.lunchEndsAt());
	}

	private static Map<DayOfWeek, BusinessHour> byDay(List<BusinessHour> all) {
		Map<DayOfWeek, BusinessHour> map = new EnumMap<>(DayOfWeek.class);
		all.forEach(hour -> map.put(hour.dayOfWeek(), hour));
		return map;
	}

	private static List<DayView> views(Map<DayOfWeek, BusinessHour> byDay) {
		List<DayView> views = new ArrayList<>(7);
		for (DayOfWeek day : DayOfWeek.values()) {
			BusinessHour hour = byDay.get(day);
			views.add(hour == null ? new DayView(day, false, DaySchedule.CLOSED, null)
					: new DayView(day, true, DaySchedule.of(hour), hour.getVersion()));
		}
		return views;
	}

	/** Visão do que ficaria gravado (em {@code dryRun}): a versão é a atual, pois nada foi alterado. */
	private static List<DayView> viewsOf(Map<DayOfWeek, DaySchedule> resulting, Map<DayOfWeek, BusinessHour> current,
			List<DayInput> inputs) {
		List<DayView> views = new ArrayList<>(7);
		for (DayOfWeek day : DayOfWeek.values()) {
			BusinessHour hour = current.get(day);
			boolean configured = hour != null || inputs.stream().anyMatch(input -> input.day() == day);
			views.add(new DayView(day, configured, resulting.getOrDefault(day, DaySchedule.CLOSED),
					hour == null ? null : hour.getVersion()));
		}
		return views;
	}
}
