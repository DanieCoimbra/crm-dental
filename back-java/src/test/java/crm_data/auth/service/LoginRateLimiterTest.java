package crm_data.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import crm_data.shared.error.RateLimitedException;
import io.github.bucket4j.TimeMeter;

class LoginRateLimiterTest {

	/** Relógio controlável: o teste "passa o tempo" sem dormir. */
	private static final class FakeTime implements TimeMeter {
		long nanos = 1_000_000_000L;

		void advance(Duration duration) {
			nanos += duration.toNanos();
		}

		@Override
		public long currentTimeNanos() {
			return nanos;
		}

		@Override
		public boolean isWallClockBased() {
			return false;
		}
	}

	private final FakeTime time = new FakeTime();
	private final LoginRateLimiter limiter = new LoginRateLimiter(time);

	// ---- por IP: 20 por minuto, por ação ----

	@Test
	void allows20RequestsPerMinutePerIpAndBlocksThe21st() {
		for (int i = 0; i < 20; i++) {
			limiter.checkIp("login", "203.0.113.1");
		}

		assertThatThrownBy(() -> limiter.checkIp("login", "203.0.113.1")).isInstanceOfSatisfying(RateLimitedException.class,
				ex -> assertThat(ex.retryAfterSeconds()).isBetween(1L, 60L));
	}

	@Test
	void ipBucketRefillsAfterOneMinute() {
		for (int i = 0; i < 20; i++) {
			limiter.checkIp("login", "203.0.113.1");
		}
		time.advance(Duration.ofSeconds(61));

		assertThatCode(() -> limiter.checkIp("login", "203.0.113.1")).doesNotThrowAnyException();
	}

	@Test
	void limitsAreSeparatePerActionAndPerIp() {
		for (int i = 0; i < 20; i++) {
			limiter.checkIp("login", "203.0.113.1");
		}

		assertThatCode(() -> limiter.checkIp("register", "203.0.113.1")).doesNotThrowAnyException();
		assertThatCode(() -> limiter.checkIp("login", "203.0.113.2")).doesNotThrowAnyException();
	}

	@Test
	void retryAfterReflectsTheRemainingWait() {
		for (int i = 0; i < 20; i++) {
			limiter.checkIp("login", "203.0.113.1");
		}
		time.advance(Duration.ofSeconds(50));

		assertThatThrownBy(() -> limiter.checkIp("login", "203.0.113.1")).isInstanceOfSatisfying(RateLimitedException.class,
				ex -> assertThat(ex.retryAfterSeconds()).isBetween(1L, 11L));
	}

	// ---- falhas de login: 5 por minuto por IP + e-mail ----

	@Test
	void fiveFailuresAreTolerated_theSixthAttemptIsBlocked() {
		for (int i = 0; i < 5; i++) {
			limiter.checkLoginAllowed("203.0.113.1", "ana@clinica.com"); // 1ª a 5ª tentativa passam
			limiter.recordLoginFailure("203.0.113.1", "ana@clinica.com");
		}

		assertThatThrownBy(() -> limiter.checkLoginAllowed("203.0.113.1", "ana@clinica.com"))
				.isInstanceOfSatisfying(RateLimitedException.class, ex -> assertThat(ex.retryAfterSeconds()).isBetween(1L, 60L));
	}

	@Test
	void checkingDoesNotConsume() {
		for (int i = 0; i < 100; i++) {
			limiter.checkLoginAllowed("203.0.113.1", "ana@clinica.com");
		}
		assertThatCode(() -> limiter.checkLoginAllowed("203.0.113.1", "ana@clinica.com")).doesNotThrowAnyException();
	}

	@Test
	void failuresDoNotAffectOtherEmailsOrIps() {
		for (int i = 0; i < 5; i++) {
			limiter.recordLoginFailure("203.0.113.1", "ana@clinica.com");
		}

		assertThatCode(() -> limiter.checkLoginAllowed("203.0.113.1", "outra@clinica.com")).doesNotThrowAnyException();
		assertThatCode(() -> limiter.checkLoginAllowed("203.0.113.2", "ana@clinica.com")).doesNotThrowAnyException();
	}

	@Test
	void successfulLoginClearsTheFailures() {
		for (int i = 0; i < 4; i++) {
			limiter.recordLoginFailure("203.0.113.1", "ana@clinica.com");
		}
		limiter.resetLoginFailures("203.0.113.1", "ana@clinica.com");
		for (int i = 0; i < 4; i++) {
			limiter.recordLoginFailure("203.0.113.1", "ana@clinica.com");
		}

		assertThatCode(() -> limiter.checkLoginAllowed("203.0.113.1", "ana@clinica.com")).doesNotThrowAnyException();
	}

	@Test
	void failureWindowExpiresAfterOneMinute() {
		for (int i = 0; i < 5; i++) {
			limiter.recordLoginFailure("203.0.113.1", "ana@clinica.com");
		}
		time.advance(Duration.ofSeconds(61));

		assertThatCode(() -> limiter.checkLoginAllowed("203.0.113.1", "ana@clinica.com")).doesNotThrowAnyException();
	}

	// ---- troca de senha: 5 senhas atuais erradas por minuto, por usuário ----

	@Test
	void sixthWrongCurrentPasswordInAMinuteIsBlocked() {
		java.util.UUID user = java.util.UUID.randomUUID();
		for (int i = 0; i < 5; i++) {
			limiter.checkPasswordChangeAllowed(user);
			limiter.recordPasswordChangeFailure(user);
		}

		assertThatThrownBy(() -> limiter.checkPasswordChangeAllowed(user)).isInstanceOfSatisfying(RateLimitedException.class,
				ex -> assertThat(ex.retryAfterSeconds()).isBetween(1L, 60L));
	}

	@Test
	void passwordChangeFailuresAreIndependentPerUserAndSeparateFromLoginFailures() {
		java.util.UUID user = java.util.UUID.randomUUID();
		for (int i = 0; i < 5; i++) {
			limiter.recordPasswordChangeFailure(user);
		}

		assertThatCode(() -> limiter.checkPasswordChangeAllowed(java.util.UUID.randomUUID())).doesNotThrowAnyException();
		assertThatCode(() -> limiter.checkLoginAllowed("203.0.113.1", user.toString())).doesNotThrowAnyException();
	}

	@Test
	void passwordChangeCounterResetsOnSuccessAndExpiresAfterOneMinute() {
		java.util.UUID user = java.util.UUID.randomUUID();
		for (int i = 0; i < 4; i++) {
			limiter.recordPasswordChangeFailure(user);
		}
		limiter.resetPasswordChangeFailures(user);
		for (int i = 0; i < 4; i++) {
			limiter.recordPasswordChangeFailure(user);
		}
		assertThatCode(() -> limiter.checkPasswordChangeAllowed(user)).doesNotThrowAnyException();

		limiter.recordPasswordChangeFailure(user);
		assertThatThrownBy(() -> limiter.checkPasswordChangeAllowed(user)).isInstanceOf(RateLimitedException.class);
		time.advance(Duration.ofSeconds(61));
		assertThatCode(() -> limiter.checkPasswordChangeAllowed(user)).doesNotThrowAnyException();
	}

	@Test
	void isSafeUnderConcurrentUse() throws Exception {
		Thread[] threads = new Thread[8];
		java.util.concurrent.atomic.AtomicInteger blocked = new java.util.concurrent.atomic.AtomicInteger();
		for (int t = 0; t < threads.length; t++) {
			threads[t] = new Thread(() -> {
				for (int i = 0; i < 10; i++) {
					try {
						limiter.checkIp("login", "203.0.113.50");
					}
					catch (RateLimitedException ex) {
						blocked.incrementAndGet();
					}
				}
			});
			threads[t].start();
		}
		for (Thread thread : threads) {
			thread.join();
		}

		assertThat(blocked.get()).isEqualTo(80 - 20); // exatamente 20 passam, nunca mais
	}
}
