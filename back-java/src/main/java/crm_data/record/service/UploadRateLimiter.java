package crm_data.record.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import crm_data.shared.error.RateLimitedException;
import crm_data.shared.ratelimit.IdleEviction;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;

/**
 * Limite de 10 uploads por minuto por usuário (Bucket4j, em memória, como o {@code LoginRateLimiter}): o estado se
 * perde a cada restart e não é compartilhado entre instâncias, o que é aceitável com 1 instância. Entradas ociosas
 * são descartadas.
 */
@Component
public class UploadRateLimiter {

	static final int UPLOADS_PER_MINUTE = 10;
	private static final Duration WINDOW = Duration.ofMinutes(1);
	private static final long IDLE_NANOS = Duration.ofMinutes(10).toNanos();
	private static final int CLEANUP_EVERY = 512;
	private static final int MAX_KEYS = 100_000;

	private static final class Entry {
		final Bucket bucket;
		volatile long lastAccessNanos = System.nanoTime();

		Entry(Bucket bucket) {
			this.bucket = bucket;
		}
	}

	private final TimeMeter timeMeter;
	private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
	private final AtomicInteger operations = new AtomicInteger();

	@Autowired
	public UploadRateLimiter() {
		this(TimeMeter.SYSTEM_MILLISECONDS);
	}

	UploadRateLimiter(TimeMeter timeMeter) {
		this.timeMeter = timeMeter;
	}

	/** Conta um upload do usuário. @throws RateLimitedException 429 a partir do 11º no mesmo minuto */
	public void check(UUID userId) {
		cleanupSometimes();
		Entry entry = entries.computeIfAbsent(userId, k -> new Entry(Bucket.builder()
				.withCustomTimePrecision(timeMeter)
				.addLimit(limit -> limit.capacity(UPLOADS_PER_MINUTE).refillIntervally(UPLOADS_PER_MINUTE, WINDOW))
				.build()));
		entry.lastAccessNanos = System.nanoTime();
		var probe = entry.bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
			throw new RateLimitedException("Muitos envios de arquivo. Tente novamente em instantes.", seconds);
		}
	}

	private void cleanupSometimes() {
		if (operations.incrementAndGet() % CLEANUP_EVERY != 0) {
			return;
		}
		IdleEviction.evict(entries, e -> e.lastAccessNanos, IDLE_NANOS, MAX_KEYS); // no teto remove as menos recentes, nunca tudo
	}
}
