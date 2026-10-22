package crm_data.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PerfLimitsTest {

	private static final String PROPERTY = "perf.limit-ms";
	private final String original = System.getProperty(PROPERTY);

	@AfterEach
	void restore() {
		if (original == null) {
			System.clearProperty(PROPERTY);
		}
		else {
			System.setProperty(PROPERTY, original);
		}
	}

	@Test
	void defaultsToTheContractLimitLocallyAndToDoubleWhenRunningInCi() {
		System.clearProperty(PROPERTY);

		assertThat(PerfLimits.p95Ms()).isEqualTo(System.getenv("CI") != null ? 200L : 100L);
	}

	@Test
	void theSystemPropertyAlwaysWins() {
		System.setProperty(PROPERTY, "350");

		assertThat(PerfLimits.p95Ms()).isEqualTo(350L);
	}
}
