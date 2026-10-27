package crm_data.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;

class DocumentDownloadRecorderTest {

	private final EntityManager entityManager = mock(EntityManager.class);
	private final Query query = mock(Query.class);
	private final DocumentDownloadRecorder recorder = new DocumentDownloadRecorder(entityManager);

	DocumentDownloadRecorderTest() {
		when(entityManager.createNativeQuery(anyString())).thenReturn(query);
		when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
	}

	private void failsWith(String sqlState) {
		when(query.getSingleResult()).thenThrow(new PersistenceException("falha", new SQLException("ERROR: x", sqlState)));
	}

	@Test
	void successRecordsWithoutError() {
		when(query.getSingleResult()).thenReturn("");

		assertThatCode(() -> recorder.record(UUID.randomUUID())).doesNotThrowAnyException();
	}

	@Test
	void vanishedDocumentIs404() {
		failsWith("P0002");

		assertThatThrownBy(() -> recorder.record(UUID.randomUUID())).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}

	@Test
	void anonymizedPatientIs409() {
		failsWith("CR003");

		assertThatThrownBy(() -> recorder.record(UUID.randomUUID())).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));
	}

	@Test
	void otherDatabaseErrorsPropagateToTheGlobalHandler() {
		failsWith("42501");

		assertThatThrownBy(() -> recorder.record(UUID.randomUUID())).isInstanceOf(PersistenceException.class);
	}
}
