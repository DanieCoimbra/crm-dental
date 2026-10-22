package crm_data.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

	@Test
	void anonymizedPatientExistsButIsNotWritable() {
		when(patients.anonymizedFlag(id)).thenReturn(List.of(true));

		assertThatCode(() -> lookup.requireExists(id)).doesNotThrowAnyException();
		assertThatThrownBy(() -> lookup.requireWritable(id)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).isEqualTo("Paciente anonimizado.");
		});
	}
}
