package crm_data.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import crm_data.shared.config.CrmProperties;
import crm_data.shared.error.RateLimitedException;
import io.github.bucket4j.TimeMeter;

class ClinicalDocumentRateLimiterTest {

	private final AtomicLong nanos = new AtomicLong();
	private final TimeMeter clock = new TimeMeter() {
		@Override
		public long currentTimeNanos() {
			return nanos.get();
		}

		@Override
		public boolean isWallClockBased() {
			return false;
		}
	};
	private final ClinicalDocumentRateLimiter limiter = new ClinicalDocumentRateLimiter(30, 30, clock);

	@Test
	void allowsThirtyIssuesPerMinuteAndBlocksTheNextWithRetryAfter() {
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 30; i++) {
			limiter.checkIssue(user);
		}

		assertThatThrownBy(() -> limiter.checkIssue(user)).isInstanceOfSatisfying(RateLimitedException.class,
				ex -> assertThat(ex.retryAfterSeconds()).isBetween(1L, 60L));
	}

	@Test
	void issuesAndPdfsHaveSeparateBuckets() {
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 30; i++) {
			limiter.checkIssue(user);
		}

		assertThatCode(() -> limiter.checkPdf(user)).doesNotThrowAnyException();
	}

	@Test
	void theLimitIsPerUserAndRefillsAfterAMinute() {
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 30; i++) {
			limiter.checkPdf(user);
		}
		assertThatThrownBy(() -> limiter.checkPdf(user)).isInstanceOf(RateLimitedException.class);
		assertThatCode(() -> limiter.checkPdf(UUID.randomUUID())).doesNotThrowAnyException();

		nanos.addAndGet(Duration.ofSeconds(61).toNanos());

		assertThatCode(() -> limiter.checkPdf(user)).doesNotThrowAnyException();
	}

	@Test
	void theLimitsComeFromTheProperties() {
		ClinicalDocumentRateLimiter custom = new ClinicalDocumentRateLimiter(new CrmProperties(List.of(), null, false, null,
				new CrmProperties.ClinicalDocuments(2, 1)));
		UUID user = UUID.randomUUID();
		custom.checkIssue(user);
		custom.checkIssue(user);
		custom.checkPdf(user);

		assertThatThrownBy(() -> custom.checkIssue(user)).isInstanceOf(RateLimitedException.class);
		assertThatThrownBy(() -> custom.checkPdf(user)).isInstanceOf(RateLimitedException.class);
	}
}
