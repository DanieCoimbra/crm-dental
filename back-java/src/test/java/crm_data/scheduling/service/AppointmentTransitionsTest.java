package crm_data.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.service.AppointmentTransitions.Action;
import crm_data.shared.security.Role;

/** A tabela da seção 5 do PRD global, cobrindo todos os pares ação × status (permitidos e proibidos). */
class AppointmentTransitionsTest {

	private static final Map<Action, Set<AppointmentStatus>> ALLOWED = new EnumMap<>(Action.class);

	static {
		ALLOWED.put(Action.CONFIRM, EnumSet.of(AppointmentStatus.SCHEDULED));
		ALLOWED.put(Action.CANCEL, EnumSet.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED));
		ALLOWED.put(Action.NO_SHOW, EnumSet.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED));
		ALLOWED.put(Action.START, EnumSet.of(AppointmentStatus.CONFIRMED));
		ALLOWED.put(Action.FINISH, EnumSet.of(AppointmentStatus.IN_PROGRESS));
		ALLOWED.put(Action.REACTIVATE, EnumSet.of(AppointmentStatus.CANCELED, AppointmentStatus.NO_SHOW));
	}

	@ParameterizedTest
	@EnumSource(Action.class)
	void everyStatusIsEitherAllowedOrForbiddenExactlyAsTheTable(Action action) {
		for (AppointmentStatus status : AppointmentStatus.values()) {
			assertThat(AppointmentTransitions.allowedFrom(action, status)).as("%s a partir de %s", action, status)
					.isEqualTo(ALLOWED.get(action).contains(status));
		}
		assertThat(AppointmentTransitions.sources(action)).isEqualTo(ALLOWED.get(action));
	}

	@Test
	void targetsAreTheTableDestinations() {
		assertThat(AppointmentTransitions.target(Action.CONFIRM)).isEqualTo(AppointmentStatus.CONFIRMED);
		assertThat(AppointmentTransitions.target(Action.CANCEL)).isEqualTo(AppointmentStatus.CANCELED);
		assertThat(AppointmentTransitions.target(Action.NO_SHOW)).isEqualTo(AppointmentStatus.NO_SHOW);
		assertThat(AppointmentTransitions.target(Action.START)).isEqualTo(AppointmentStatus.IN_PROGRESS);
		assertThat(AppointmentTransitions.target(Action.FINISH)).isEqualTo(AppointmentStatus.COMPLETED);
		assertThat(AppointmentTransitions.target(Action.REACTIVATE)).isEqualTo(AppointmentStatus.SCHEDULED);
	}

	@Test
	void receptionistConfirmsCancelsAndMarksNoShowButNeverStartsFinishesOrReactivates() {
		for (Action action : Set.of(Action.CONFIRM, Action.CANCEL, Action.NO_SHOW)) {
			assertThat(AppointmentTransitions.mayPerform(action, Role.RECEPTIONIST)).as("%s", action).isTrue();
			assertThat(AppointmentTransitions.mayPerform(action, Role.DENTIST_ADMIN)).as("%s", action).isTrue();
		}
		for (Action action : Set.of(Action.START, Action.FINISH, Action.REACTIVATE)) {
			assertThat(AppointmentTransitions.mayPerform(action, Role.RECEPTIONIST)).as("%s", action).isFalse();
			assertThat(AppointmentTransitions.mayPerform(action, Role.DENTIST_ADMIN)).as("%s", action).isTrue();
		}
	}

	@Test
	void completedIsTerminalAndInProgressOnlyLeavesByFinish() {
		for (Action action : Action.values()) {
			assertThat(AppointmentTransitions.allowedFrom(action, AppointmentStatus.COMPLETED)).as("%s", action).isFalse();
			assertThat(AppointmentTransitions.allowedFrom(action, AppointmentStatus.IN_PROGRESS)).as("%s", action).isEqualTo(action == Action.FINISH);
		}
	}

	@Test
	void everyTargetIsReachableFromItsSourcesOnlyAndNeverBackToTheSameStatus() {
		for (Action action : Action.values()) {
			assertThat(AppointmentTransitions.sources(action)).as("%s", action).doesNotContain(AppointmentTransitions.target(action));
		}
	}
}
