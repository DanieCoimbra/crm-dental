package crm_data.scheduling.service;

import static crm_data.scheduling.domain.AppointmentStatus.CANCELED;
import static crm_data.scheduling.domain.AppointmentStatus.COMPLETED;
import static crm_data.scheduling.domain.AppointmentStatus.CONFIRMED;
import static crm_data.scheduling.domain.AppointmentStatus.IN_PROGRESS;
import static crm_data.scheduling.domain.AppointmentStatus.SCHEDULED;

import java.util.EnumSet;
import java.util.Set;

import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.shared.security.Role;

/**
 * Máquina de estados do agendamento, pura e sem Spring. O banco só valida a lista de status e o que a recepção grava
 * (RLS); a ordem das transições é daqui. {@code COMPLETED} é terminal e {@code IN_PROGRESS} só sai por {@code FINISH}.
 */
public final class AppointmentTransitions {

	/** Ações (uma rota cada). Não existe "mudar status" genérico. */
	public enum Action {

		CONFIRM(EnumSet.of(SCHEDULED), CONFIRMED, EnumSet.of(Role.DENTIST_ADMIN, Role.RECEPTIONIST)),
		CANCEL(EnumSet.of(SCHEDULED, CONFIRMED), CANCELED, EnumSet.of(Role.DENTIST_ADMIN, Role.RECEPTIONIST)),
		NO_SHOW(EnumSet.of(SCHEDULED, CONFIRMED), AppointmentStatus.NO_SHOW, EnumSet.of(Role.DENTIST_ADMIN, Role.RECEPTIONIST)),
		START(EnumSet.of(CONFIRMED), IN_PROGRESS, EnumSet.of(Role.DENTIST_ADMIN)),
		FINISH(EnumSet.of(IN_PROGRESS), COMPLETED, EnumSet.of(Role.DENTIST_ADMIN)),
		REACTIVATE(EnumSet.of(CANCELED, AppointmentStatus.NO_SHOW), SCHEDULED, EnumSet.of(Role.DENTIST_ADMIN));

		private final Set<AppointmentStatus> sources;
		private final AppointmentStatus target;
		private final Set<Role> roles;

		Action(Set<AppointmentStatus> sources, AppointmentStatus target, Set<Role> roles) {
			this.sources = sources;
			this.target = target;
			this.roles = roles;
		}
	}

	private AppointmentTransitions() {
	}

	/** Status de origem em que a ação é permitida. */
	public static Set<AppointmentStatus> sources(Action action) {
		return action.sources;
	}

	public static AppointmentStatus target(Action action) {
		return action.target;
	}

	/** Papéis que podem executar a ação (a recepção nunca inicia, finaliza nem reativa). */
	public static Set<Role> roles(Action action) {
		return action.roles;
	}

	public static boolean allowedFrom(Action action, AppointmentStatus current) {
		return action.sources.contains(current);
	}

	public static boolean mayPerform(Action action, Role role) {
		return action.roles.contains(role);
	}
}
