package crm_data.scheduling.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
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
import crm_data.scheduling.service.AppointmentService.AppointmentView;
import crm_data.scheduling.service.AppointmentTransitions.Action;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * Transições do agendamento, uma por ação (não existe "mudar status" genérico). Ordem de cada chamada: papel (403) →
 * agendamento (404) → versão (409) → origem permitida (409 com o status atual) → regra extra (422/409) → gravação com
 * {@code flush}. Reativar reavalia o conflito de horário e o paciente no banco ({@code 23P01}, {@code CR005},
 * {@code CR003}, traduzidos pelo handler global) e não reaplica expediente, feriado nem passado. {@code start},
 * {@code finish} e {@code reactivate} são do dentista (rota e {@code @PreAuthorize}).
 */
@Service
@RequiredArgsConstructor
public class AppointmentStatusService {

	private final AppointmentRepository appointments;
	private final PatientLookup patients;
	private final ClinicZones zones;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final ProcedureTypeLookup procedures;

	@PreAuthorize("hasAnyRole('DENTIST_ADMIN', 'RECEPTIONIST')")
	@Transactional
	public AppointmentView confirm(UUID id, long expectedVersion) {
		return perform(id, expectedVersion, Action.CONFIRM);
	}

	@PreAuthorize("hasAnyRole('DENTIST_ADMIN', 'RECEPTIONIST')")
	@Transactional
	public AppointmentView cancel(UUID id, long expectedVersion) {
		return perform(id, expectedVersion, Action.CANCEL);
	}

	/** @throws BusinessException {@code business-rule} (422, {@code NOT_STARTED}) se o horário ainda não começou */
	@PreAuthorize("hasAnyRole('DENTIST_ADMIN', 'RECEPTIONIST')")
	@Transactional
	public AppointmentView noShow(UUID id, long expectedVersion) {
		return perform(id, expectedVersion, Action.NO_SHOW);
	}

	/**
	 * @throws BusinessException 422 ({@code NOT_TODAY}) fora do dia local do agendamento; 409 se já há outro atendimento
	 *         em andamento na clínica
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public AppointmentView start(UUID id, long expectedVersion) {
		return perform(id, expectedVersion, Action.START);
	}

	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public AppointmentView finish(UUID id, long expectedVersion) {
		return perform(id, expectedVersion, Action.FINISH);
	}

	/** @throws BusinessException 409 se o horário foi ocupado, ou se o paciente está inativo ou anonimizado */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public AppointmentView reactivate(UUID id, long expectedVersion) {
		return perform(id, expectedVersion, Action.REACTIVATE);
	}

	private AppointmentView perform(UUID id, long expectedVersion, Action action) {
		Role role = TenantContext.role();
		if (!AppointmentTransitions.mayPerform(action, role)) {
			throw new BusinessException(ProblemTypes.FORBIDDEN, "Você não tem permissão para esta operação.");
		}
		Appointment appointment = appointments.findById(id)
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Agendamento não encontrado."));
		if (!Objects.equals(appointment.getVersion(), expectedVersion)) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.APPOINTMENT_CHANGED);
		}
		AppointmentStatus from = appointment.getStatus();
		if (!AppointmentTransitions.allowedFrom(action, from)) {
			throw new BusinessException(ProblemTypes.CONFLICT, "O agendamento está " + from + " e não aceita esta ação.");
		}
		requireExtraRules(action, appointment);

		appointment.setStatus(AppointmentTransitions.target(action));
		Appointment saved = SlotRace.saveAndFlush(appointments, appointment);

		events.publishEvent(new SchedulingEvents.StatusChanged(TenantContext.clinicId(), saved.getId(), from.name(),
				saved.getStatus().name(), TenantContext.userId()));
		PatientBrief brief = patients.briefs(List.of(saved.getPatientId())).get(saved.getPatientId());
		UUID procedureId = saved.getProcedureTypeId();
		ProcedureBrief procedure = procedureId == null ? null : procedures.briefs(List.of(procedureId)).get(procedureId);
		return new AppointmentView(saved, brief, procedure);
	}

	private void requireExtraRules(Action action, Appointment appointment) {
		Instant now = clock.instant();
		switch (action) {
			case NO_SHOW -> {
				if (now.isBefore(appointment.getStartsAt())) {
					throw new BusinessException(ProblemTypes.BUSINESS_RULE, "O horário do agendamento ainda não começou.",
							"NOT_STARTED");
				}
			}
			case START -> {
				ZoneId zone = zones.zone();
				if (!appointment.getStartsAt().atZone(zone).toLocalDate().equals(now.atZone(zone).toLocalDate())) {
					throw new BusinessException(ProblemTypes.BUSINESS_RULE,
							"O atendimento só pode ser iniciado no dia do agendamento.", "NOT_TODAY");
				}
				if (appointments.existsByStatusAndIdNot(AppointmentStatus.IN_PROGRESS, appointment.getId())) {
					throw new BusinessException(ProblemTypes.CONFLICT, "Já existe um atendimento em andamento.");
				}
			}
			default -> {
				// as demais ações não têm regra além da origem e do papel
			}
		}
	}
}
