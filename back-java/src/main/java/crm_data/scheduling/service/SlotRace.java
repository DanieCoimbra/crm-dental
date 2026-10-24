package crm_data.scheduling.service;

import java.util.Set;

import org.springframework.dao.PessimisticLockingFailureException;

import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.error.ProblemTypes;

/**
 * Gravação de agendamento com a corrida de horário traduzida. Duas transações que gravam horários sobrepostos ao mesmo
 * tempo podem se esperar uma à outra na exclusão {@code ex_appt_no_overlap} (cada uma vê a tupla pendente da outra): o
 * PostgreSQL desfaz uma delas com {@code 40P01} (deadlock) ou {@code 40001}. Para o usuário é o mesmo conflito de
 * horário (409), nunca um 500. Outras falhas de lock seguem seu caminho. Vale para criar, remarcar e reativar.
 */
final class SlotRace {

	/** {@code deadlock_detected} e {@code serialization_failure}: a transação perdedora da corrida de horário. */
	private static final Set<String> RACE_STATES = Set.of("40P01", "40001");

	private SlotRace() {
	}

	static Appointment saveAndFlush(AppointmentRepository appointments, Appointment appointment) {
		try {
			return appointments.saveAndFlush(appointment);
		}
		catch (PessimisticLockingFailureException ex) {
			if (DatabaseErrors.sqlState(ex).filter(RACE_STATES::contains).isPresent()) {
				throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.SLOT_TAKEN);
			}
			throw ex;
		}
	}
}
