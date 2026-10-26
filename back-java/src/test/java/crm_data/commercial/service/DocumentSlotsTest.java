package crm_data.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import crm_data.shared.config.CrmProperties;

class DocumentSlotsTest {

	@Test
	void grantsExactlyTheConfiguredNumberOfSlotsAndRefusesTheNextOneAfterTheWait() {
		DocumentSlots slots = new DocumentSlots(3);

		assertThat(slots.tryAcquire(Duration.ofMillis(10))).isTrue();
		assertThat(slots.tryAcquire(Duration.ofMillis(10))).isTrue();
		assertThat(slots.tryAcquire(Duration.ofMillis(10))).isTrue();
		long started = System.nanoTime();
		boolean fourth = slots.tryAcquire(Duration.ofMillis(150));
		long waitedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

		assertThat(fourth).isFalse();
		assertThat(waitedMillis).as("espera antes de desistir").isGreaterThanOrEqualTo(100);
		assertThat(slots.available()).isZero();
	}

	@Test
	void releasingAFreeSlotLetsTheNextOneIn() {
		DocumentSlots slots = new DocumentSlots(1);
		assertThat(slots.tryAcquire(Duration.ofMillis(10))).isTrue();
		assertThat(slots.tryAcquire(Duration.ofMillis(10))).isFalse();

		slots.release();

		assertThat(slots.tryAcquire(Duration.ofMillis(10))).isTrue();
	}

	@Test
	void aWaitingCallerGetsTheSlotAsSoonAsAnotherOneIsReleased() throws Exception {
		DocumentSlots slots = new DocumentSlots(1);
		slots.tryAcquire(Duration.ofMillis(10));
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<Boolean> waiting = pool.submit(() -> slots.tryAcquire(Duration.ofSeconds(2)));
			Thread.sleep(100);
			slots.release();

			assertThat(waiting.get()).isTrue();
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void neverGrantsMoreThanTheLimitUnderConcurrency() throws Exception {
		DocumentSlots slots = new DocumentSlots(3);
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger maxInside = new AtomicInteger();
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(12);
		try {
			java.util.List<Future<?>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < 12; i++) {
				futures.add(pool.submit(() -> {
					go.await();
					if (slots.tryAcquire(Duration.ofSeconds(5))) {
						try {
							maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
							Thread.sleep(20);
						}
						finally {
							inside.decrementAndGet();
							slots.release();
						}
					}
					return null;
				}));
			}
			go.countDown();
			for (Future<?> future : futures) {
				future.get();
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(maxInside.get()).isLessThanOrEqualTo(3).isGreaterThanOrEqualTo(1);
		assertThat(slots.available()).isEqualTo(3);
	}

	@Test
	void theLimitComesFromTheProperties() {
		DocumentSlots slots = new DocumentSlots(new CrmProperties(java.util.List.of(), null, false,
				new CrmProperties.Documents(null, 2, null, null)));

		assertThat(slots.available()).isEqualTo(2);
	}
}
