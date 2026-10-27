package crm_data.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.patient.domain.Patient;
import crm_data.patient.domain.Sex;
import crm_data.patient.repository.PatientRepository;
import crm_data.patient.service.PatientService.PatientDetails;
import crm_data.patient.service.PatientService.Status;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

	@Mock
	PatientRepository patients;
	@Mock
	EntityManager entityManager;
	@Mock
	ApplicationEventPublisher events;

	PatientService service;
	UUID clinicId = UUID.randomUUID();
	UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new PatientService(patients, entityManager, Clock.fixed(NOW, ZoneOffset.UTC), events);
		TenantContext.set(clinicId, userId, Role.RECEPTIONIST);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	private static PatientDetails details(String cpf) {
		return new PatientDetails("Maria Souza", cpf, LocalDate.of(1988, 4, 12), null, null, "11987654321", null, null,
				null, null, null, null);
	}

	private Patient existing(long version) {
		Patient patient = new Patient(UUID.randomUUID(), userId);
		ReflectionTestUtils.setField(patient, "version", version);
		return patient;
	}

	@Test
	void createSetsServerSideFieldsAndDefaultsTheSex() {
		when(patients.saveAndFlush(any(Patient.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Patient created = service.create(details("52998224725"));

		assertThat(created.getId()).isNotNull();
		assertThat(created.getCreatedBy()).isEqualTo(userId);
		assertThat(created.getSex()).isEqualTo(Sex.NOT_INFORMED);
		assertThat(created.getName()).isEqualTo("Maria Souza");
		verify(entityManager).refresh(created);
	}

	@Test
	void duplicateCpfBecomesConflictWithoutTheCpfInTheMessage() {
		SQLException driver = new SQLException(
				"ERROR: duplicate key value violates unique constraint \"ux_patients_clinic_cpf\"\n"
						+ "  Detail: Key (clinic_id, cpf)=(x, 52998224725) already exists.", "23505");
		when(patients.saveAndFlush(any(Patient.class))).thenThrow(new DataIntegrityViolationException("x", driver));

		assertThatThrownBy(() -> service.create(details("52998224725")))
				.isInstanceOfSatisfying(BusinessException.class, ex -> {
					assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT);
					assertThat(ex.getMessage()).isEqualTo("CPF já cadastrado.").doesNotContain("52998224725");
				});
	}

	@Test
	void otherIntegrityViolationsAreRethrown() {
		DataIntegrityViolationException other = new DataIntegrityViolationException("outra",
				new SQLException("violates check constraint \"ck_patients_phone\"", "23514"));
		when(patients.saveAndFlush(any(Patient.class))).thenThrow(other);

		assertThatThrownBy(() -> service.create(details(null))).isSameAs(other);
	}

	@Test
	void updateWithStaleVersionIsConflictAndDoesNotWrite() {
		Patient patient = existing(3);
		when(patients.findOwnById(patient.getId())).thenReturn(Optional.of(patient));

		assertThatThrownBy(() -> service.update(patient.getId(), details(null), 2))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
		verify(patients, never()).saveAndFlush(any());
	}

	@Test
	void updateAppliesFieldsWhenVersionMatches() {
		Patient patient = existing(3);
		when(patients.findOwnById(patient.getId())).thenReturn(Optional.of(patient));
		when(patients.saveAndFlush(patient)).thenReturn(patient);

		Patient updated = service.update(patient.getId(), details("52998224725"), 3);

		assertThat(updated.getName()).isEqualTo("Maria Souza");
		assertThat(updated.getCpf()).isEqualTo("52998224725");
		verify(entityManager).refresh(patient);
	}

	@Test
	void updateOfAnonymizedPatientIsConflict() {
		Patient patient = existing(0);
		ReflectionTestUtils.setField(patient, "anonymizedAt", NOW);
		when(patients.findOwnById(patient.getId())).thenReturn(Optional.of(patient));

		assertThatThrownBy(() -> service.update(patient.getId(), details(null), 0))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getMessage()).isEqualTo("Paciente anonimizado."));
		verify(patients, never()).saveAndFlush(any());
	}

	/** {@code findById} não aplica o filtro de tenant; a busca por id passa pela consulta JPQL (auditoria 2026-10-27). */
	@Test
	void lookupByIdGoesThroughTheTenantFilteredQuery() {
		UUID id = UUID.randomUUID();
		when(patients.findOwnById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(id)).isInstanceOf(BusinessException.class);

		verify(patients).findOwnById(id);
		verify(patients, never()).findById(any());
	}

	@Test
	void unknownIdIsNotFound() {
		UUID id = UUID.randomUUID();
		when(patients.findOwnById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(id))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
		assertThatThrownBy(() -> service.update(id, details(null), 0)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.setActive(id, false)).isInstanceOf(BusinessException.class);
	}

	@Test
	void deactivateRecordsTheInstantAndReactivateClearsIt() {
		Patient patient = existing(0);
		when(patients.findOwnById(patient.getId())).thenReturn(Optional.of(patient));
		when(patients.saveAndFlush(patient)).thenReturn(patient);

		service.setActive(patient.getId(), false);
		assertThat(patient.isActive()).isFalse();
		assertThat(patient.getDeactivatedAt()).isEqualTo(NOW);

		service.setActive(patient.getId(), true);
		assertThat(patient.isActive()).isTrue();
		assertThat(patient.getDeactivatedAt()).isNull();
	}

	@Test
	void settingTheCurrentStateIsIdempotentAndWritesNothing() {
		Patient patient = existing(0);
		when(patients.findOwnById(patient.getId())).thenReturn(Optional.of(patient));

		Patient result = service.setActive(patient.getId(), true);

		assertThat(result).isSameAs(patient);
		verify(patients, never()).saveAndFlush(any());
	}

	@Test
	void anonymizedPatientIsNeverReactivated() {
		Patient patient = existing(0);
		patient.deactivate(NOW);
		ReflectionTestUtils.setField(patient, "anonymizedAt", NOW);
		when(patients.findOwnById(patient.getId())).thenReturn(Optional.of(patient));

		assertThatThrownBy(() -> service.setActive(patient.getId(), true))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
	}

	@Test
	void searchTermShorterThanTwoCharactersIsAValidationError() {
		assertThatThrownBy(() -> service.search("a", Status.ACTIVE, 0, 20))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.VALIDATION));
		assertThatThrownBy(() -> service.search(" a ", Status.ACTIVE, 0, 20)).isInstanceOf(BusinessException.class);
		verify(patients, never()).findAll(ArgumentMatchers.<Specification<Patient>>any(), any(org.springframework.data.domain.Pageable.class));
	}

	@Test
	void statusParsesTheContractValues() {
		assertThat(Status.parse("active")).isEqualTo(Status.ACTIVE);
		assertThat(Status.parse("inactive")).isEqualTo(Status.INACTIVE);
		assertThat(Status.parse("all")).isEqualTo(Status.ALL);
	}


	@Test
	void deactivatingPublishesTheSynchronousEventBeforeWritingAndTheEventCarriesOnlyIds() {
		Patient patient = existing(0);
		when(patients.findOwnById(patient.getId())).thenReturn(Optional.of(patient));
		when(patients.saveAndFlush(patient)).thenReturn(patient);

		service.setActive(patient.getId(), false);

		org.mockito.InOrder order = org.mockito.Mockito.inOrder(events, patients);
		order.verify(events).publishEvent(new PatientEvents.Deactivating(clinicId, patient.getId()));
		order.verify(patients).saveAndFlush(patient);
	}

	@Test
	void reactivatingAndRepeatingTheCurrentStatePublishNothing() {
		Patient inactive = existing(0);
		inactive.deactivate(NOW);
		Patient active = existing(0);
		when(patients.findOwnById(inactive.getId())).thenReturn(Optional.of(inactive));
		when(patients.findOwnById(active.getId())).thenReturn(Optional.of(active));
		when(patients.saveAndFlush(inactive)).thenReturn(inactive);

		service.setActive(inactive.getId(), true);
		service.setActive(inactive.getId(), true);
		service.setActive(active.getId(), true);

		org.mockito.Mockito.verifyNoInteractions(events);
	}

	@Test
	void inactivatingAnAlreadyInactivePatientIsIdempotentAndDoesNotCancelAgainNorPublish() {
		Patient inactive = existing(0);
		inactive.deactivate(NOW);
		when(patients.findOwnById(inactive.getId())).thenReturn(Optional.of(inactive));

		service.setActive(inactive.getId(), false);

		org.mockito.Mockito.verifyNoInteractions(events);
		verify(patients, never()).saveAndFlush(any());
	}

	@Test
	void aRefusalFromTheListenerKeepsThePatientActiveAndNothingIsWritten() {
		Patient patient = existing(0);
		when(patients.findOwnById(patient.getId())).thenReturn(Optional.of(patient));
		BusinessException refusal = new BusinessException(ProblemTypes.CONFLICT, "Finalize o atendimento antes de inativar o paciente.");
		org.mockito.Mockito.doThrow(refusal).when(events).publishEvent(any(PatientEvents.Deactivating.class));

		assertThatThrownBy(() -> service.setActive(patient.getId(), false)).isSameAs(refusal);

		assertThat(patient.isActive()).isTrue();
		assertThat(patient.getDeactivatedAt()).isNull();
		verify(patients, never()).saveAndFlush(any());
	}
}
