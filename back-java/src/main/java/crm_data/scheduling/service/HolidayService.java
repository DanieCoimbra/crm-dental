package crm_data.scheduling.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.clinic.service.ClinicZones;
import crm_data.scheduling.domain.ClinicHoliday;
import crm_data.scheduling.domain.NationalHoliday;
import crm_data.scheduling.repository.ClinicHolidayRepository;
import crm_data.scheduling.repository.NationalHolidayRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * Feriados: leitura da união nacional ∪ da clínica (qualquer papel) e CRUD dos feriados da clínica (só o dentista).
 * Feriado nacional é dado global somente leitura. Criar ou mover um feriado devolve o aviso de agendamentos futuros
 * que caem na data (informativo; nada é cancelado).
 */
@Service
@RequiredArgsConstructor
public class HolidayService {

	public enum Source {
		NATIONAL, CLINIC
	}

	/** Um feriado na lista; {@code id} e {@code version} só existem para os da clínica. */
	public record HolidayView(LocalDate date, String name, Source source, UUID id, Long version) {
	}

	/** Feriado da clínica gravado, com o aviso de impacto. */
	public record HolidayChange(HolidayView holiday, AffectedAppointments affected) {
	}

	private static final String STALE = "O feriado foi alterado por outra pessoa. Recarregue e tente novamente.";

	private final ClinicHolidayRepository clinicHolidays;
	private final NationalHolidayRepository nationalHolidays;
	private final AffectedAppointmentsFinder finder;
	private final ClinicZones zones;
	private final ApplicationEventPublisher events;

	/**
	 * @throws BusinessException {@code validation} (400) se {@code to < from} ou a janela passar de 366 dias
	 */
	@Transactional(readOnly = true)
	public List<HolidayView> list(LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw new BusinessException(ProblemTypes.VALIDATION, "A data final deve ser igual ou posterior à inicial.");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > SchedulingRules.MAX_HOLIDAY_WINDOW_DAYS) {
			throw new BusinessException(ProblemTypes.VALIDATION,
					"A janela de consulta deve ter no máximo " + SchedulingRules.MAX_HOLIDAY_WINDOW_DAYS + " dias.");
		}
		List<HolidayView> views = new ArrayList<>();
		for (NationalHoliday holiday : nationalHolidays.findByDateBetweenOrderByDate(from, to)) {
			views.add(new HolidayView(holiday.getDate(), holiday.getName(), Source.NATIONAL, null, null));
		}
		for (ClinicHoliday holiday : clinicHolidays.findByDateBetweenOrderByDate(from, to)) {
			views.add(view(holiday));
		}
		// estável: na mesma data os nacionais (adicionados primeiro) vêm antes dos da clínica
		views.sort(Comparator.comparing(HolidayView::date));
		return views;
	}

	/** Datas de feriado (nacionais ∪ da clínica) do período, para a regra de horário. */
	@Transactional(readOnly = true)
	public Set<LocalDate> holidayDates(LocalDate from, LocalDate to) {
		Set<LocalDate> dates = new HashSet<>();
		nationalHolidays.findByDateBetweenOrderByDate(from, to).forEach(h -> dates.add(h.getDate()));
		clinicHolidays.findByDateBetweenOrderByDate(from, to).forEach(h -> dates.add(h.getDate()));
		return dates;
	}

	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public HolidayChange create(LocalDate date, String name) {
		ClinicHoliday holiday = clinicHolidays
				.saveAndFlush(new ClinicHoliday(UUID.randomUUID(), date, name, TenantContext.userId()));
		return changed(holiday, "criado");
	}

	/**
	 * @throws BusinessException {@code not-found} (404) se o feriado não é da clínica; {@code conflict} (409) se a
	 *         {@code version} estiver defasada
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public HolidayChange update(UUID id, LocalDate date, String name, long expectedVersion) {
		ClinicHoliday holiday = load(id);
		if (!Objects.equals(holiday.getVersion(), expectedVersion)) {
			throw new BusinessException(ProblemTypes.CONFLICT, STALE);
		}
		holiday.setDate(date);
		holiday.setName(name);
		return changed(clinicHolidays.saveAndFlush(holiday), "alterado");
	}

	/** Remoção física (única exceção a "sem DELETE"); não gera aviso. */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public void delete(UUID id) {
		ClinicHoliday holiday = load(id);
		clinicHolidays.delete(holiday);
		clinicHolidays.flush();
		events.publishEvent(new SchedulingEvents.ClinicHolidayChanged(TenantContext.clinicId(), TenantContext.userId(), id,
				"removido", 0));
	}

	private HolidayChange changed(ClinicHoliday holiday, String action) {
		AffectedAppointments affected = finder.findOnHoliday(holiday.getDate(), zones.zone());
		events.publishEvent(new SchedulingEvents.ClinicHolidayChanged(TenantContext.clinicId(), TenantContext.userId(),
				holiday.getId(), action, affected.count()));
		return new HolidayChange(view(holiday), affected);
	}

	private ClinicHoliday load(UUID id) {
		return clinicHolidays.findById(id)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Feriado não encontrado."));
	}

	private static HolidayView view(ClinicHoliday holiday) {
		return new HolidayView(holiday.getDate(), holiday.getName(), Source.CLINIC, holiday.getId(), holiday.getVersion());
	}
}
