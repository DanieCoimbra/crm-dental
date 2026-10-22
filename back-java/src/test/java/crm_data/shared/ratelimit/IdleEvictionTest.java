package crm_data.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

class IdleEvictionTest {

	private static final long IDLE = Duration.ofMinutes(10).toNanos();

	@Test
	void removesIdleEntriesAndKeepsRecentOnes() {
		long now = System.nanoTime();
		Map<String, Long> entries = new ConcurrentHashMap<>(Map.of("velha", now - IDLE * 2, "recente", now));

		IdleEviction.evict(entries, Long::longValue, IDLE, 100);

		assertThat(entries).containsOnlyKeys("recente");
	}

	@Test
	void atTheCeilingDropsOnlyTheOldestTenPercentInsteadOfEverything() {
		long now = System.nanoTime();
		Map<Integer, Long> entries = new ConcurrentHashMap<>();
		for (int i = 0; i < 100; i++) {
			entries.put(i, now - (100 - i)); // 0 é a mais antiga, 99 a mais recente; nenhuma ociosa
		}

		IdleEviction.evict(entries, Long::longValue, IDLE, 100);

		assertThat(entries).hasSize(90);
		assertThat(entries).doesNotContainKeys(0, 1, 2, 3, 4, 5, 6, 7, 8, 9).containsKeys(10, 50, 99);
	}

	@Test
	void belowTheCeilingNothingRecentIsRemoved() {
		long now = System.nanoTime();
		Map<Integer, Long> entries = new ConcurrentHashMap<>(Map.of(1, now, 2, now, 3, now));

		IdleEviction.evict(entries, Long::longValue, IDLE, 100);

		assertThat(entries).hasSize(3);
	}
}
