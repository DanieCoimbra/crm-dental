package crm_data.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DatabaseErrorsTest {

	private static SQLException sql(String message, String state) {
		return new SQLException(message, state);
	}

	@Test
	void findsSqlStateThroughTheCauseChain() {
		Throwable wrapped = new DataIntegrityViolationException("x", new RuntimeException(sql("falhou", "CR003")));

		assertThat(DatabaseErrors.sqlState(wrapped)).contains("CR003");
	}

	@Test
	void returnsEmptyWhenThereIsNoSqlException() {
		Throwable plain = new IllegalStateException("sem banco");

		assertThat(DatabaseErrors.sqlState(plain)).isEmpty();
		assertThat(DatabaseErrors.constraintName(plain)).isEmpty();
		assertThat(DatabaseErrors.retentionReleaseDate(plain)).isEmpty();
	}

	@Test
	void extractsConstraintNameWithoutTheValue() {
		SQLException duplicate = sql("ERROR: duplicate key value violates unique constraint \"ux_patients_clinic_cpf\"\n"
				+ "  Detail: Key (clinic_id, cpf)=(0a0a0a0a-0000-0000-0000-000000000000, 52998224725) already exists.",
				"23505");

		assertThat(DatabaseErrors.constraintName(duplicate)).contains(ConstraintViolations.PATIENT_CPF);
		assertThat(DatabaseErrors.constraintName(duplicate).orElseThrow()).doesNotContain("52998224725");
	}

	@Test
	void extractsRetentionReleaseDateFromTheDetail() {
		SQLException retention = sql("ERROR: retenção vigente\n  Detail: liberado em 2046-10-22", "CR002");

		assertThat(DatabaseErrors.retentionReleaseDate(retention)).contains(LocalDate.of(2046, 10, 22));
	}

	@Test
	void releaseDateOutOfFormatIsIgnored() {
		assertThat(DatabaseErrors.retentionReleaseDate(sql("liberado em amanhã", "CR002"))).isEmpty();
		assertThat(DatabaseErrors.retentionReleaseDate(sql("liberado em 2046-13-45", "CR002"))).isEmpty();
	}

	@Test
	void readsTheChainedSqlException() {
		SQLException first = sql("batch falhou", "23000");
		first.setNextException(sql("violates check constraint \"ck_patients_name\"", "23514"));

		assertThat(DatabaseErrors.constraintName(first)).contains("ck_patients_name");
	}
}
