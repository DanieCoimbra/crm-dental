package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import crm_data.shared.config.CrmProperties;
import crm_data.shared.error.RateLimitedException;
import io.github.bucket4j.TimeMeter;

class DocumentRateLimiterTest {

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
	private final DocumentRateLimiter limiter = new DocumentRateLimiter(6, clock);

	@Test
	void allowsSixSendsPerMinuteAndBlocksTheSeventhWithRetryAfter() {
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 6; i++) {
			limiter.check(user);
		}

		assertThatThrownBy(() -> limiter.check(user)).isInstanceOfSatisfying(RateLimitedException.class, ex -> {
			assertThat(ex.retryAfterSeconds()).isBetween(1L, 60L);
			assertThat(ex.getMessage()).isEqualTo("Muitos envios de orçamento. Tente novamente em instantes.");
		});
	}

	@Test
	void theLimitIsPerUser() {
		UUID first = UUID.randomUUID();
		for (int i = 0; i < 6; i++) {
			limiter.check(first);
		}

		assertThatCode(() -> limiter.check(UUID.randomUUID())).doesNotThrowAnyException();
	}

	@Test
	void theWindowRefillsAfterAMinute() {
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 6; i++) {
			limiter.check(user);
		}
		assertThatThrownBy(() -> limiter.check(user)).isInstanceOf(RateLimitedException.class);

		nanos.addAndGet(Duration.ofSeconds(61).toNanos());

		assertThatCode(() -> limiter.check(user)).doesNotThrowAnyException();
	}

	@Test
	void theLimitComesFromTheProperties() {
		DocumentRateLimiter custom = new DocumentRateLimiter(new CrmProperties(java.util.List.of(), null, false,
				new CrmProperties.Documents(null, null, 2, null)));
		UUID user = UUID.randomUUID();
		custom.check(user);
		custom.check(user);

		assertThatThrownBy(() -> custom.check(user)).isInstanceOf(RateLimitedException.class);
	}
}
