package crm_data.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.patient.service.PatientLookup;
import crm_data.record.domain.MedicalHistory;
import crm_data.record.repository.MedicalHistoryRepository;
import crm_data.record.service.MedicalHistoryService.Saved;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
class MedicalHistoryServiceTest {

	@Mock
	MedicalHistoryRepository histories;
	@Mock
	PatientLookup patients;
	@Mock
	EntityManager entityManager;

	MedicalHistoryService service;
	UUID patientId = UUID.randomUUID();
	UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new MedicalHistoryService(histories, patients, entityManager);
		TenantContext.set(UUID.randomUUID(), userId, Role.DENTIST_ADMIN);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	private MedicalHistory existing(long version) {
		MedicalHistory history = new MedicalHistory(UUID.randomUUID(), patientId, "antigo", userId);
		ReflectionTestUtils.setField(history, "version", version);
		return history;
	}

	private static void assertConflict(Throwable error) {
		assertThat(error).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
	}

	@Test
	void firstSaveCreatesIgnoringTheVersionAndStampsTheCreator() {
		when(histories.findByPatientId(patientId)).thenReturn(Optional.empty());
		when(histories.saveAndFlush(any(MedicalHistory.class))).thenAnswer(i -> i.getArgument(0));

		Saved saved = service.save(patientId, "  texto  com espaços  ", 99L);

		assertThat(saved.created()).isTrue();
		assertThat(saved.history().getContent()).isEqualTo("  texto  com espaços  "); // não é aparado
		assertThat(saved.history().getCreatedBy()).isEqualTo(userId);
		assertThat(saved.history().getPatientId()).isEqualTo(patientId);
		verify(patients).requireWritable(patientId);
		verify(entityManager).refresh(saved.history());
	}

	@Test
	void saveOnAnExistingHistoryWithTheCurrentVersionUpdates() {
		MedicalHistory history = existing(2);
		when(histories.findByPatientId(patientId)).thenReturn(Optional.of(history));
		when(histories.saveAndFlush(history)).thenReturn(history);

		Saved saved = service.save(patientId, "novo", 2L);

		assertThat(saved.created()).isFalse();
		assertThat(history.getContent()).isEqualTo("novo");
	}

	@Test
	void saveOnAnExistingHistoryWithoutVersionIsConflict() {
		when(histories.findByPatientId(patientId)).thenReturn(Optional.of(existing(2)));

		assertThatThrownBy(() -> service.save(patientId, "novo", null)).satisfies(MedicalHistoryServiceTest::assertConflict);
		verify(histories, never()).saveAndFlush(any());
	}

	@Test
	void saveWithStaleVersionIsConflictAndDoesNotChangeTheText() {
		MedicalHistory history = existing(2);
		when(histories.findByPatientId(patientId)).thenReturn(Optional.of(history));

		assertThatThrownBy(() -> service.save(patientId, "novo", 1L)).satisfies(MedicalHistoryServiceTest::assertConflict);
		assertThat(history.getContent()).isEqualTo("antigo");
	}

	@Test
	void creationIgnoresAnyVersionButUpdateTreatsANegativeOneAsStale() {
		when(histories.findByPatientId(patientId)).thenReturn(Optional.empty());
		when(histories.saveAndFlush(any(MedicalHistory.class))).thenAnswer(i -> i.getArgument(0));
		assertThat(service.save(patientId, "novo", -1L).created()).isTrue();

		when(histories.findByPatientId(patientId)).thenReturn(Optional.of(existing(0)));
		assertThatThrownBy(() -> service.save(patientId, "novo", -1L)).satisfies(MedicalHistoryServiceTest::assertConflict);
	}

	@Test
	void concurrentCreationBecomesConflictFromTheUniqueIndex() {
		when(histories.findByPatientId(patientId)).thenReturn(Optional.empty());
		SQLException driver = new SQLException("duplicate key value violates unique constraint \"ux_histories_patient\"", "23505");
		when(histories.saveAndFlush(any(MedicalHistory.class))).thenThrow(new DataIntegrityViolationException("x", driver));

		assertThatThrownBy(() -> service.save(patientId, "novo", null)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
			assertThat(ex.getMessage()).isEqualTo("Histórico já existe.");
		});
	}

	@Test
	void otherIntegrityViolationsAreRethrown() {
		when(histories.findByPatientId(patientId)).thenReturn(Optional.empty());
		DataIntegrityViolationException other = new DataIntegrityViolationException("x",
				new SQLException("violates check constraint \"ck_histories_content\"", "23514"));
		when(histories.saveAndFlush(any(MedicalHistory.class))).thenThrow(other);

		assertThatThrownBy(() -> service.save(patientId, "novo", null)).isSameAs(other);
	}

	@Test
	void anonymizedOrMissingPatientStopsBeforeTouchingTheHistory() {
		BusinessException anonymized = new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.");
		doThrow(anonymized).when(patients).requireWritable(patientId);

		assertThatThrownBy(() -> service.save(patientId, "novo", null)).isSameAs(anonymized);
		verify(histories, never()).findByPatientId(any());
	}

	@Test
	void getReturnsTheHistoryOr404() {
		MedicalHistory history = existing(0);
		when(histories.findByPatientId(patientId)).thenReturn(Optional.of(history));
		assertThat(service.get(patientId)).isSameAs(history);
		verify(patients).requireExists(patientId);

		UUID other = UUID.randomUUID();
		when(histories.findByPatientId(other)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.get(other)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}
}
