package crm_data.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.clinic.service.UserNames;
import crm_data.record.domain.AuditAction;
import crm_data.record.domain.AuditLog;
import crm_data.record.repository.AuditLogRepository;
import crm_data.record.service.AuditQueryService.Entry;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;

@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

	@Mock
	AuditLogRepository audit;
	@Mock
	UserNames userNames;

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

	private AuditQueryService service() {
		return new AuditQueryService(audit, userNames);
	}

	private static AuditLog log(long id, UUID user, String table, UUID record, AuditAction action) {
		AuditLog log = (AuditLog) org.springframework.objenesis.ObjenesisHelper.newInstance(AuditLog.class);
		ReflectionTestUtils.setField(log, "id", id);
		ReflectionTestUtils.setField(log, "userId", user);
		ReflectionTestUtils.setField(log, "tableName", table);
		ReflectionTestUtils.setField(log, "recordId", record);
		ReflectionTestUtils.setField(log, "action", action);
		ReflectionTestUtils.setField(log, "occurredAt", Instant.parse("2026-10-22T12:00:00Z"));
		return log;
	}

	private static void assertValidation(Throwable error) {
		assertThat(error).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.VALIDATION));
	}

	@Test
	void recordIdWithoutTableNameIsAValidationError() {
		assertThatThrownBy(() -> service().list(null, UUID.randomUUID(), 0, 20)).satisfies(AuditQueryServiceTest::assertValidation);
		assertThatThrownBy(() -> service().list("  ", UUID.randomUUID(), 0, 20)).satisfies(AuditQueryServiceTest::assertValidation);
		verifyNoInteractions(audit, userNames);
	}

	@Test
	void unknownTableNameIsAValidationError() {
		for (String bad : List.of("users", "audit_log", "patients; drop table x", "PATIENTS", "core.patients")) {
			assertThatThrownBy(() -> service().list(bad, null, 0, 20)).satisfies(AuditQueryServiceTest::assertValidation);
		}
		verifyNoInteractions(audit);
	}

	@Test
	void withoutFiltersListsTheWholeTrailNewestFirstAndAttachesTheNames() {
		UUID ana = UUID.randomUUID();
		UUID ghost = UUID.randomUUID();
		AuditLog first = log(2, ana, "patients", UUID.randomUUID(), AuditAction.UPDATE);
		AuditLog second = log(1, ghost, "clinical_notes", UUID.randomUUID(), AuditAction.INSERT);
		when(audit.findAllBy(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));
		when(userNames.namesOf(any())).thenReturn(Map.of(ana, "Dra. Ana"));

		Page<Entry> page = service().list(null, null, 0, 20);

		assertThat(page.getContent()).extracting(Entry::userName).containsExactly("Dra. Ana", null);
		assertThat(page.getContent()).extracting(e -> e.log().getId()).containsExactly(2L, 1L);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(audit).findAllBy(pageable.capture());
		assertThat(pageable.getValue().getSort()).isEqualTo(NEWEST_FIRST);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
		verify(userNames).namesOf(ids.capture());
		assertThat(ids.getValue()).containsExactlyInAnyOrder(ana, ghost);
	}

	@Test
	void usersRepeatedInThePageAreResolvedOnce() {
		UUID ana = UUID.randomUUID();
		when(audit.findAllBy(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
				log(3, ana, "patients", UUID.randomUUID(), AuditAction.INSERT),
				log(2, ana, "patients", UUID.randomUUID(), AuditAction.UPDATE),
				log(1, ana, "patients", UUID.randomUUID(), AuditAction.UPDATE))));
		when(userNames.namesOf(any())).thenReturn(Map.of(ana, "Dra. Ana"));

		service().list(null, null, 0, 20);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
		verify(userNames).namesOf(ids.capture());
		assertThat(ids.getValue()).containsExactly(ana);
	}

	@Test
	void filtersByTableAndByRecord() {
		UUID record = UUID.randomUUID();
		when(audit.findByTableName(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
		when(audit.findByTableNameAndRecordId(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
		when(userNames.namesOf(any())).thenReturn(Map.of());

		service().list("patient_files", null, 1, 10);
		service().list("medical_histories", record, 0, 50);

		verify(audit).findByTableName(org.mockito.ArgumentMatchers.eq("patient_files"), any(Pageable.class));
		verify(audit).findByTableNameAndRecordId(org.mockito.ArgumentMatchers.eq("medical_histories"),
				org.mockito.ArgumentMatchers.eq(record), any(Pageable.class));
		verify(audit, never()).findAllBy(any());
	}
}
