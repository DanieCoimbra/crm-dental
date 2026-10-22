package crm_data.record.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import crm_data.shared.error.RateLimitedException;
import io.github.bucket4j.TimeMeter;

class UploadRateLimiterTest {

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
	private final UploadRateLimiter limiter = new UploadRateLimiter(clock);

	@Test
	void allowsTenUploadsPerMinuteAndBlocksTheEleventhWithRetryAfter() {
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 10; i++) {
			limiter.check(user);
		}

		assertThatThrownBy(() -> limiter.check(user)).isInstanceOfSatisfying(RateLimitedException.class,
				ex -> assertThat(ex.retryAfterSeconds()).isBetween(1L, 60L));
	}

	@Test
	void theLimitIsPerUser() {
		UUID first = UUID.randomUUID();
		for (int i = 0; i < 10; i++) {
			limiter.check(first);
		}

		assertThatCode(() -> limiter.check(UUID.randomUUID())).doesNotThrowAnyException();
	}

	@Test
	void theWindowRefillsAfterAMinute() {
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 10; i++) {
			limiter.check(user);
		}
		assertThatThrownBy(() -> limiter.check(user)).isInstanceOf(RateLimitedException.class);

		nanos.addAndGet(Duration.ofSeconds(61).toNanos());

		assertThatCode(() -> limiter.check(user)).doesNotThrowAnyException();
	}
}
