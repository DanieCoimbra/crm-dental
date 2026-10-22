package crm_data.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import crm_data.shared.error.RateLimitedException;

class HashingGateTest {

	@Test
	void returnsTheResultAndReleasesThePermit() {
		HashingGate gate = new HashingGate(1, Duration.ofMillis(50));

		assertThat(gate.run(() -> "a")).isEqualTo("a");
		assertThat(gate.run(() -> "b")).isEqualTo("b"); // a vaga voltou
	}

	@Test
	void releasesThePermitWhenTheWorkThrows() {
		HashingGate gate = new HashingGate(1, Duration.ofMillis(50));

		assertThatThrownBy(() -> gate.run(() -> {
			throw new IllegalStateException("falha");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(gate.run(() -> "ok")).isEqualTo("ok");
	}

	@Test
	void withoutAPermitWithinTheWaitTheCallGets429WithRetryAfter() {
		HashingGate gate = new HashingGate(1, Duration.ofMillis(30));

		gate.run(() -> {
			assertThatThrownBy(() -> gate.run(() -> "nunca")).isInstanceOfSatisfying(RateLimitedException.class,
					ex -> assertThat(ex.retryAfterSeconds()).isPositive());
			return null;
		});
	}

	@Test
	void neverRunsMoreThanThePermittedHashesAtTheSameTime() throws Exception {
		HashingGate gate = new HashingGate(2, Duration.ofSeconds(5));
		AtomicInteger running = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(8);
		for (int i = 0; i < 8; i++) {
			Thread.ofVirtual().start(() -> {
				gate.run(() -> {
					peak.accumulateAndGet(running.incrementAndGet(), Math::max);
					try {
						Thread.sleep(20);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					running.decrementAndGet();
					return null;
				});
				done.countDown();
			});
		}

		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(peak.get()).isEqualTo(2);
	}
}
