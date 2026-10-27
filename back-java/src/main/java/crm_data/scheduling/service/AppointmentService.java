package crm_data.scheduling.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.catalog.service.ProcedureBrief;
import crm_data.catalog.service.ProcedureTypeLookup;
import crm_data.clinic.service.ClinicZones;
import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.scheduling.service.AppointmentSlotRules.Reason;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * Agendamentos: criar, consultar, listar a agenda por janela local e remarcar. O conflito de horário é decidido pelo
 * banco ({@code ex_appt_no_overlap}); o {@code flush} explícito faz a violação (23P01) estourar aqui, dentro da
 * transação, e o handler global a traduz em 409 sem expor o outro agendamento. Não há lock nem pré-checagem de
 * sobreposição. Expediente, feriado e passado são regras da recepção; o dentista pode encaixar e registrar no passado.
 * As transições de status (confirmar, cancelar, iniciar…) ficam na F04.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DENTIST_ADMIN', 'RECEPTIONIST')")
public class AppointmentService {

	/**
	 * Agendamento com o paciente resumido (nome e telefone vêm de {@code patient}, em lote) e o procedimento resumido
	 * (de {@code catalog}, também em lote; {@code null} se o agendamento não tem procedimento).
	 */
	public record AppointmentView(Appointment appointment, PatientBrief patient, ProcedureBrief procedure) {

		public AppointmentView(Appointment appointment, PatientBrief patient) {
			this(appointment, patient, null);
		}
	}

	private static final String ONLY_DENTIST_FITS = "Somente o dentista pode agendar fora do expediente.";

	private final AppointmentRepository appointments;
	private final PatientLookup patients;
	private final ClinicZones zones;
	private final BusinessHoursService businessHours;
	private final HolidayService holidays;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final ProcedureTypeLookup procedures;

	/** Cria um agendamento sem procedimento. {@code @Transactional} próprio: a chamada interna não passa pelo proxy. */
	@Transactional
	public AppointmentView create(UUID patientId, Instant startsAt, Instant endsAt, String notes, boolean allowOutsideHours) {
		return create(patientId, startsAt, endsAt, notes, allowOutsideHours, null);
	}

	/**
	 * @param allowOutsideHours encaixe: ignora expediente, almoço e feriado; só o dentista pode (a recepção recebe 403)
	 * @param procedureTypeId procedimento do atendimento; {@code null} = sem procedimento
	 * @throws BusinessException 403 (encaixe da recepção), 404/409 (paciente), 404 (procedimento inexistente), 422 com
	 *         {@code reason} (regra de horário ou {@code PROCEDURE_INACTIVE}), 409 {@code "Horário já ocupado."} pela
	 *         constraint
	 */
	@Transactional
	public AppointmentView create(UUID patientId, Instant startsAt, Instant endsAt, String notes, boolean allowOutsideHours,
			UUID procedureTypeId) {
		Role role = TenantContext.role();
		requireMayFit(role, allowOutsideHours);
		patients.requireSchedulable(patientId);
		requireProcedure(procedureTypeId);
		boolean fitsOnlyByException = enforceSlotRules(role, startsAt, endsAt, allowOutsideHours);

		UUID userId = TenantContext.userId();
		Appointment appointment = SlotRace.saveAndFlush(appointments,
				new Appointment(UUID.randomUUID(), patientId, startsAt, endsAt, notes, userId, procedureTypeId));

		UUID clinicId = TenantContext.clinicId();
		events.publishEvent(new SchedulingEvents.AppointmentCreated(clinicId, appointment.getId(), patientId, userId));
		if (fitsOnlyByException) {
			events.publishEvent(new SchedulingEvents.OutsideHoursBooked(clinicId, appointment.getId(), userId));
		}
		return view(appointment);
	}

	/**
	 * Agenda da janela local {@code [from 00:00, to+1 00:00)} no fuso da clínica: inclui quem começou antes e entra nela.
	 *
	 * @param statuses filtro opcional; vazio devolve todos os status
	 * @throws BusinessException {@code validation} (400) se {@code to < from} ou a janela passar de 42 dias
	 */
	@Transactional(readOnly = true)
	public List<AppointmentView> list(LocalDate from, LocalDate to, Collection<AppointmentStatus> statuses) {
		if (to.isBefore(from)) {
			throw new BusinessException(ProblemTypes.VALIDATION, "A data final deve ser igual ou posterior à inicial.");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > SchedulingRules.MAX_AGENDA_DAYS) {
			throw new BusinessException(ProblemTypes.VALIDATION,
					"A janela da agenda deve ter no máximo " + SchedulingRules.MAX_AGENDA_DAYS + " dias.");
		}
		ZoneId zone = zones.zone();
		Instant start = from.atStartOfDay(zone).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
		Instant lower = start.minus(SchedulingRules.SEARCH_LOOKBACK);
		List<Appointment> found = statuses == null || statuses.isEmpty() ? appointments.findWindow(start, end, lower)
				: appointments.findWindowByStatus(start, end, lower, statuses);
		return views(found);
	}

	/**
	 * Histórico do paciente (todos os status), do mais recente ao mais antigo. Paciente inativo ou anonimizado continua
	 * consultável.
	 *
	 * @throws BusinessException {@code not-found} (404) se o paciente não existe na clínica
	 */
	@Transactional(readOnly = true)
	public Page<AppointmentView> historyOf(UUID patientId, int page, int size) {
		patients.requireExists(patientId);
		Page<Appointment> found = appointments.findByPatientId(patientId,
				PageRequest.of(page, size, Sort.by(Sort.Order.desc("startsAt"), Sort.Order.desc("id"))));
		PatientBrief brief = patients.briefs(List.of(patientId)).get(patientId);
		Map<UUID, ProcedureBrief> procedureBriefs = procedureBriefsOf(found.getContent());
		return found.map(appointment -> new AppointmentView(appointment, brief,
				procedureOf(procedureBriefs, appointment.getProcedureTypeId())));
	}

	/** @throws BusinessException {@code not-found} (404) se não existe na clínica */
	@Transactional(readOnly = true)
	public AppointmentView get(UUID id) {
		return view(load(id));
	}

	/**
	 * Remarca e/ou edita a observação, removendo o procedimento (o {@code PUT} substitui o vínculo).
	 * {@code @Transactional} próprio: a chamada interna não passa pelo proxy.
	 */
	@Transactional
	public AppointmentView reschedule(UUID id, Instant startsAt, Instant endsAt, String notes, boolean allowOutsideHours,
			long expectedVersion) {
		return reschedule(id, startsAt, endsAt, notes, allowOutsideHours, null, expectedVersion);
	}

	/**
	 * Remarca e/ou edita a observação de um agendamento {@code SCHEDULED}/{@code CONFIRMED}. As regras de horário só
	 * valem se o horário mudou; um {@code CONFIRMED} remarcado volta a {@code SCHEDULED}. O paciente nunca muda.
	 *
	 * @param notes substitui a observação ({@code null} limpa)
	 * @param procedureTypeId <strong>substitui</strong> o procedimento: {@code null} remove o vínculo e o cliente
	 *        reenvia o atual para mantê-lo (como {@code notes}); se preenchido, precisa existir e estar ativo
	 * @throws BusinessException 403, 404, 409 ({@code version} defasada ou status que não permite), 422
	 */
	@Transactional
	public AppointmentView reschedule(UUID id, Instant startsAt, Instant endsAt, String notes, boolean allowOutsideHours,
			UUID procedureTypeId, long expectedVersion) {
		Role role = TenantContext.role();
		requireMayFit(role, allowOutsideHours);
		Appointment appointment = load(id);
		if (!Objects.equals(appointment.getVersion(), expectedVersion)) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.APPOINTMENT_CHANGED);
		}
		AppointmentStatus status = appointment.getStatus();
		if (status != AppointmentStatus.SCHEDULED && status != AppointmentStatus.CONFIRMED) {
			throw new BusinessException(ProblemTypes.CONFLICT, "O agendamento está " + status + " e não pode ser alterado.");
		}
		requireProcedure(procedureTypeId);
		boolean timeChanged = !startsAt.equals(appointment.getStartsAt()) || !endsAt.equals(appointment.getEndsAt());
		boolean fitsOnlyByException = false;
		if (timeChanged) {
			fitsOnlyByException = enforceSlotRules(role, startsAt, endsAt, allowOutsideHours);
			appointment.setStartsAt(startsAt);
			appointment.setEndsAt(endsAt);
			if (status == AppointmentStatus.CONFIRMED) {
				appointment.setStatus(AppointmentStatus.SCHEDULED); // nova confirmação
			}
		}
		appointment.setNotes(notes);
		appointment.setProcedureTypeId(procedureTypeId);
		Appointment saved = SlotRace.saveAndFlush(appointments, appointment);

		UUID clinicId = TenantContext.clinicId();
		UUID userId = TenantContext.userId();
		if (timeChanged) {
			events.publishEvent(new SchedulingEvents.AppointmentRescheduled(clinicId, saved.getId(), userId));
		}
		if (fitsOnlyByException) {
			events.publishEvent(new SchedulingEvents.OutsideHoursBooked(clinicId, saved.getId(), userId));
		}
		return view(saved);
	}

	// ---- regras ----

	/** Procedimento preenchido precisa existir na clínica (404) e estar ativo (422 {@code PROCEDURE_INACTIVE}). */
	private void requireProcedure(UUID procedureTypeId) {
		if (procedureTypeId != null) {
			procedures.requireActive(procedureTypeId);
		}
	}

	/** O encaixe é do dentista: a recepção que o pede recebe 403 antes de qualquer outra regra de negócio. */
	private static void requireMayFit(Role role, boolean allowOutsideHours) {
		if (allowOutsideHours && role != Role.DENTIST_ADMIN) {
			throw new BusinessException(ProblemTypes.FORBIDDEN, ONLY_DENTIST_FITS);
		}
	}

	/**
	 * Aplica passado e horário. A recepção é bloqueada por qualquer motivo; o dentista nunca é bloqueado pelo passado e
	 * só pelo horário quando não pediu o encaixe.
	 *
	 * @return {@code true} se o dentista encaixou onde a regra teria bloqueado a recepção (rastro do encaixe)
	 */
	private boolean enforceSlotRules(Role role, Instant startsAt, Instant endsAt, boolean allowOutsideHours) {
		ZoneId zone = zones.zone();
		Instant now = clock.instant();
		Optional<Reason> violation = role == Role.DENTIST_ADMIN
				? hoursViolation(startsAt, endsAt, zone)
				: AppointmentSlotRules.isPast(startsAt, now) ? Optional.of(Reason.PAST) : hoursViolation(startsAt, endsAt, zone);
		if (violation.isEmpty()) {
			return false;
		}
		if (role == Role.DENTIST_ADMIN && allowOutsideHours) {
			return true;
		}
		throw new BusinessException(ProblemTypes.BUSINESS_RULE, messageOf(violation.get()), violation.get().name());
	}

	private Optional<Reason> hoursViolation(Instant startsAt, Instant endsAt, ZoneId zone) {
		LocalDate firstDay = startsAt.atZone(zone).toLocalDate();
		LocalDate lastDay = endsAt.atZone(zone).toLocalDate();
		Set<LocalDate> holidayDates = holidays.holidayDates(firstDay.minusDays(1), lastDay.plusDays(1));
		return AppointmentSlotRules.checkHours(startsAt, endsAt, zone, businessHours.weekSchedule(), holidayDates);
	}

	private static String messageOf(Reason reason) {
		return switch (reason) {
			case PAST -> "O horário já passou.";
			case HOLIDAY -> "A clínica não atende em feriado.";
			case CLOSED_DAY -> "A clínica não atende neste dia.";
			case OUTSIDE_HOURS -> "O horário está fora do expediente.";
			case LUNCH -> "O horário cai no intervalo de almoço.";
		};
	}

	// ---- leitura ----

	private Appointment load(UUID id) {
		return appointments.findOwnById(id)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Agendamento não encontrado."));
	}

	private AppointmentView view(Appointment appointment) {
		return views(List.of(appointment)).get(0);
	}

	private List<AppointmentView> views(List<Appointment> found) {
		Map<UUID, PatientBrief> briefs = patients.briefs(found.stream().map(Appointment::getPatientId).distinct().toList());
		Map<UUID, ProcedureBrief> procedureBriefs = procedureBriefsOf(found);
		List<AppointmentView> views = new ArrayList<>(found.size());
		for (Appointment appointment : found) {
			views.add(new AppointmentView(appointment, briefs.get(appointment.getPatientId()),
					procedureOf(procedureBriefs, appointment.getProcedureTypeId())));
		}
		return views;
	}

	/** Resumos dos procedimentos da lista numa só consulta (sem N+1); vazio se nenhum agendamento tem procedimento. */
	private Map<UUID, ProcedureBrief> procedureBriefsOf(List<Appointment> found) {
		return procedures.briefs(found.stream().map(Appointment::getProcedureTypeId).filter(Objects::nonNull).distinct().toList());
	}

	private static ProcedureBrief procedureOf(Map<UUID, ProcedureBrief> briefs, UUID procedureTypeId) {
		return procedureTypeId == null ? null : briefs.get(procedureTypeId);
	}
}
