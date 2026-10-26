package crm_data.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import crm_data.patient.repository.PatientRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;

@ExtendWith(MockitoExtension.class)
class PatientLookupServiceTest {

	@Mock
	PatientRepository patients;
	@InjectMocks
	PatientLookupService lookup;

	private final UUID id = UUID.randomUUID();

	@Test
	void unknownPatientIsNotFoundForBothChecks() {
		when(patients.anonymizedFlag(id)).thenReturn(List.of());

		assertThatThrownBy(() -> lookup.requireExists(id)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND);
			assertThat(ex.getMessage()).isEqualTo("Paciente não encontrado.");
		});
		assertThatThrownBy(() -> lookup.requireWritable(id))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}

	@Test
	void activePatientPassesBothChecks() {
		when(patients.anonymizedFlag(id)).thenReturn(List.of(false));

		assertThatCode(() -> lookup.requireExists(id)).doesNotThrowAnyException();
		assertThatCode(() -> lookup.requireWritable(id)).doesNotThrowAnyException();
	}

	private PatientRepository.SchedulingState state(boolean active, boolean anonymized) {
		PatientRepository.SchedulingState state = mock(PatientRepository.SchedulingState.class);
		Mockito.lenient().when(state.getActive()).thenReturn(active);
		Mockito.lenient().when(state.getAnonymized()).thenReturn(anonymized);
		return state;
	}

	@Test
	void schedulableRequiresAnExistingActiveNotAnonymizedPatient() {
		PatientRepository.SchedulingState schedulable = state(true, false);
		when(patients.schedulingState(id)).thenReturn(List.of(schedulable));

		assertThatCode(() -> lookup.requireSchedulable(id)).doesNotThrowAnyException();
	}

	@Test
	void unknownPatientIsNotFoundWhenScheduling() {
		when(patients.schedulingState(id)).thenReturn(List.of());

		assertThatThrownBy(() -> lookup.requireSchedulable(id)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND);
			assertThat(ex.getMessage()).isEqualTo("Paciente não encontrado.");
		});
	}

	@Test
	void anonymizedPatientCannotBeScheduledEvenIfInactive() {
		PatientRepository.SchedulingState anonymized = state(false, true);
		when(patients.schedulingState(id)).thenReturn(List.of(anonymized));

		assertThatThrownBy(() -> lookup.requireSchedulable(id)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).isEqualTo("Paciente anonimizado.");
		});
	}

	@Test
	void inactivePatientCannotBeScheduled() {
		PatientRepository.SchedulingState inactive = state(false, false);
		when(patients.schedulingState(id)).thenReturn(List.of(inactive));

		assertThatThrownBy(() -> lookup.requireSchedulable(id)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).isEqualTo("Paciente inativo.");
		});
	}

	@Test
	void briefsMapsRowsByIdAndSkipsTheQueryForNoIds() {
		PatientRepository.BriefRow row = mock(PatientRepository.BriefRow.class);
		when(row.getId()).thenReturn(id);
		when(row.getName()).thenReturn("Maria Souza");
		when(row.getPhone()).thenReturn("11987654321");
		when(patients.briefRows(List.of(id))).thenReturn(List.of(row));

		Map<UUID, PatientBrief> briefs = lookup.briefs(List.of(id));

		assertThat(briefs).containsOnlyKeys(id);
		assertThat(briefs.get(id)).isEqualTo(new PatientBrief(id, "Maria Souza", "11987654321"));
		assertThat(lookup.briefs(List.of())).isEmpty();
		verify(patients, never()).briefRows(List.of());
	}

	@Test
	void briefToStringNeverShowsNameOrPhone() {
		assertThat(new PatientBrief(id, "Maria Souza", "11987654321").toString()).doesNotContain("Maria", "11987654321");
	}

	@Test
	void anonymizedPatientExistsButIsNotWritable() {
		when(patients.anonymizedFlag(id)).thenReturn(List.of(true));

		assertThatCode(() -> lookup.requireExists(id)).doesNotThrowAnyException();
		assertThatThrownBy(() -> lookup.requireWritable(id)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).isEqualTo("Paciente anonimizado.");
		});
	}

	@Test
	void lockWritableUsesTheLockingQueryAndRefusesAnonymizedOrUnknownPatients() {
		when(patients.anonymizedFlagLocked(id)).thenReturn(List.of(false));
		assertThatCode(() -> lookup.lockWritable(id)).doesNotThrowAnyException();

		UUID anonymized = UUID.randomUUID();
		when(patients.anonymizedFlagLocked(anonymized)).thenReturn(List.of(true));
		assertThatThrownBy(() -> lookup.lockWritable(anonymized)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).isEqualTo("Paciente anonimizado.");
		});
		UUID unknown = UUID.randomUUID();
		when(patients.anonymizedFlagLocked(unknown)).thenReturn(List.of());
		assertThatThrownBy(() -> lookup.lockWritable(unknown)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		verify(patients, never()).anonymizedFlag(id); // a checagem sem trava não é usada quando se pede a trava
	}
}
