package crm_data.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.service.AppointmentLookup;
import crm_data.record.domain.ClinicalNote;
import crm_data.record.repository.ClinicalNoteRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
class ClinicalNoteServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

	@Mock
	ClinicalNoteRepository notes;
	@Mock
	PatientLookup patients;
	@Mock
	AppointmentLookup appointments;
	@Mock
	EntityManager entityManager;

	ClinicalNoteService service;
	UUID patientId = UUID.randomUUID();
	UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new ClinicalNoteService(notes, patients, appointments, entityManager, Clock.fixed(NOW, ZoneOffset.UTC));
		TenantContext.set(UUID.randomUUID(), userId, Role.DENTIST_ADMIN);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	private ClinicalNote existing(long version) {
		ClinicalNote note = new ClinicalNote(UUID.randomUUID(), patientId, NOW.minusSeconds(3600), "antigo", userId);
		ReflectionTestUtils.setField(note, "version", version);
		return note;
	}

	private static void assertType(Throwable error, ProblemTypes type) {
		assertThat(error).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(type));
	}

	@Test
	void createWithoutNotedAtUsesNowAndStampsTheCreator() {
		when(notes.saveAndFlush(any(ClinicalNote.class))).thenAnswer(i -> i.getArgument(0));

		ClinicalNote note = service.create(patientId, " texto ", null, null);

		assertThat(note.getNotedAt()).isEqualTo(NOW);
		assertThat(note.getContent()).isEqualTo(" texto "); // não é aparado
		assertThat(note.getCreatedBy()).isEqualTo(userId);
		assertThat(note.getPatientId()).isEqualTo(patientId);
		verify(patients).requireWritable(patientId);
		verify(entityManager).refresh(note);
	}

	@Test
	void notedAtUpToFiveMinutesAheadIsAccepted() {
		when(notes.saveAndFlush(any(ClinicalNote.class))).thenAnswer(i -> i.getArgument(0));

		Instant limit = NOW.plusSeconds(300);

		assertThat(service.create(patientId, "x", limit, null).getNotedAt()).isEqualTo(limit);
		assertThat(service.create(patientId, "x", NOW.minusSeconds(86_400 * 30L), null).getNotedAt()).isEqualTo(NOW.minusSeconds(86_400 * 30L));
	}

	@Test
	void notedAtBeyondFiveMinutesIsAValidationErrorAndNothingIsSaved() {
		assertThatThrownBy(() -> service.create(patientId, "x", NOW.plusSeconds(301), null))
				.satisfies(e -> assertType(e, ProblemTypes.VALIDATION));
		verify(notes, never()).saveAndFlush(any());
	}

	@Test
	void anonymizedPatientBlocksCreateAndUpdate() {
		BusinessException anonymized = new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.");
		doThrow(anonymized).when(patients).requireWritable(patientId);

		assertThatThrownBy(() -> service.create(patientId, "x", null, null)).isSameAs(anonymized);
		assertThatThrownBy(() -> service.update(patientId, UUID.randomUUID(), "x", NOW, null, 0)).isSameAs(anonymized);
		verify(notes, never()).saveAndFlush(any());
	}

	@Test
	void updateChangesTextAndDateWhenTheVersionMatches() {
		ClinicalNote note = existing(3);
		when(notes.findByIdAndPatientId(note.getId(), patientId)).thenReturn(Optional.of(note));
		when(notes.saveAndFlush(note)).thenReturn(note);

		ClinicalNote updated = service.update(patientId, note.getId(), "novo", NOW.minusSeconds(60), null, 3);

		assertThat(updated.getContent()).isEqualTo("novo");
		assertThat(updated.getNotedAt()).isEqualTo(NOW.minusSeconds(60));
		verify(entityManager).refresh(note);
	}

	@Test
	void updateWithStaleVersionIsConflictAndKeepsTheText() {
		ClinicalNote note = existing(3);
		when(notes.findByIdAndPatientId(note.getId(), patientId)).thenReturn(Optional.of(note));

		assertThatThrownBy(() -> service.update(patientId, note.getId(), "novo", NOW, null, 2))
				.satisfies(e -> assertType(e, ProblemTypes.CONFLICT));
		assertThat(note.getContent()).isEqualTo("antigo");
	}

	@Test
	void noteOfAnotherPatientIs404() {
		UUID noteId = UUID.randomUUID();
		when(notes.findByIdAndPatientId(noteId, patientId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.update(patientId, noteId, "x", NOW, null, 0)).satisfies(e -> assertType(e, ProblemTypes.NOT_FOUND));
	}

	@Test
	void updateWithFutureNotedAtIsAValidationError() {
		ClinicalNote note = existing(0);
		when(notes.findByIdAndPatientId(note.getId(), patientId)).thenReturn(Optional.of(note));

		assertThatThrownBy(() -> service.update(patientId, note.getId(), "x", NOW.plusSeconds(3600), null, 0))
				.satisfies(e -> assertType(e, ProblemTypes.VALIDATION));
		verify(notes, never()).saveAndFlush(any());
	}

	@Test
	void listIsNewestFirstByNotedAtThenId() {
		Page<ClinicalNote> page = new PageImpl<>(List.of(existing(0)));
		when(notes.findByPatientId(any(UUID.class), any(Pageable.class))).thenReturn(page);

		assertThat(service.list(patientId, 2, 30)).isSameAs(page);

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(notes).findByPatientId(any(UUID.class), captor.capture());
		assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
		assertThat(captor.getValue().getPageSize()).isEqualTo(30);
		assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("notedAt"), Sort.Order.desc("id")));
		verify(patients).requireExists(patientId);
	}


	private final UUID appointmentId = UUID.randomUUID();

	@Test
	void createWithAnAppointmentLocksItChecksUniquenessAndLinksTheNote() {
		when(notes.saveAndFlush(any(ClinicalNote.class))).thenAnswer(i -> i.getArgument(0));

		ClinicalNote note = service.create(patientId, "texto", null, appointmentId);

		assertThat(note.getAppointmentId()).isEqualTo(appointmentId);
		org.mockito.InOrder order = org.mockito.Mockito.inOrder(appointments, notes);
		order.verify(appointments).lockOfPatient(appointmentId, patientId);
		order.verify(notes).existsByAppointmentIdAndIdNot(appointmentId, note.getId());
		order.verify(notes).saveAndFlush(note);
	}

	@Test
	void createWithoutAnAppointmentNeverTouchesTheAgenda() {
		when(notes.saveAndFlush(any(ClinicalNote.class))).thenAnswer(i -> i.getArgument(0));

		ClinicalNote note = service.create(patientId, "texto", null, null);

		assertThat(note.getAppointmentId()).isNull();
		org.mockito.Mockito.verifyNoInteractions(appointments);
		verify(notes, never()).existsByAppointmentIdAndIdNot(any(), any());
	}

	@Test
	void aSecondNoteForTheSameAppointmentIs409AndNothingIsSaved() {
		when(notes.existsByAppointmentIdAndIdNot(any(), any())).thenReturn(true);

		assertThatThrownBy(() -> service.create(patientId, "texto", null, appointmentId)).satisfies(e -> assertType(e, ProblemTypes.CONFLICT));

		verify(notes, never()).saveAndFlush(any());
	}

	@Test
	void anAppointmentOfAnotherPatientOrUnknownStopsBeforeTheUniquenessCheck() {
		BusinessException other = new BusinessException(ProblemTypes.CONFLICT, "O atendimento é de outro paciente.");
		doThrow(other).when(appointments).lockOfPatient(appointmentId, patientId);

		assertThatThrownBy(() -> service.create(patientId, "texto", null, appointmentId)).isSameAs(other);

		verify(notes, never()).existsByAppointmentIdAndIdNot(any(), any());
		verify(notes, never()).saveAndFlush(any());
	}

	@Test
	void updateReplacesTheLinkAndTheNoteItselfIsExcludedFromTheUniquenessCheck() {
		ClinicalNote note = existing(2);
		note.setAppointmentId(appointmentId);
		when(notes.findByIdAndPatientId(note.getId(), patientId)).thenReturn(Optional.of(note));
		when(notes.saveAndFlush(note)).thenReturn(note);
		UUID other = UUID.randomUUID();

		service.update(patientId, note.getId(), "novo", NOW, appointmentId, 2); // reeditar a nota já vinculada
		service.update(patientId, note.getId(), "novo", NOW, other, 2); // trocar de atendimento

		verify(notes).existsByAppointmentIdAndIdNot(appointmentId, note.getId());
		verify(notes).existsByAppointmentIdAndIdNot(other, note.getId());
		assertThat(note.getAppointmentId()).isEqualTo(other);
	}

	@Test
	void updateWithoutAnAppointmentUnlinksWithoutLockingOrChecking() {
		ClinicalNote note = existing(2);
		note.setAppointmentId(appointmentId);
		when(notes.findByIdAndPatientId(note.getId(), patientId)).thenReturn(Optional.of(note));
		when(notes.saveAndFlush(note)).thenReturn(note);

		ClinicalNote unlinked = service.update(patientId, note.getId(), "novo", NOW, null, 2);

		assertThat(unlinked.getAppointmentId()).isNull();
		org.mockito.Mockito.verifyNoInteractions(appointments);
		verify(notes, never()).existsByAppointmentIdAndIdNot(any(), any());
	}

	@Test
	void updateToAnAppointmentThatAlreadyHasAnotherNoteIs409AndKeepsTheOldLink() {
		ClinicalNote note = existing(2);
		note.setAppointmentId(appointmentId);
		when(notes.findByIdAndPatientId(note.getId(), patientId)).thenReturn(Optional.of(note));
		UUID taken = UUID.randomUUID();
		when(notes.existsByAppointmentIdAndIdNot(taken, note.getId())).thenReturn(true);

		assertThatThrownBy(() -> service.update(patientId, note.getId(), "novo", NOW, taken, 2)).satisfies(e -> assertType(e, ProblemTypes.CONFLICT));

		verify(notes, never()).saveAndFlush(any());
	}
}
