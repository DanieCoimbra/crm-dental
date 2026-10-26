package crm_data.commercial.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import crm_data.shared.config.CrmProperties;
import crm_data.shared.error.RateLimitedException;
import crm_data.shared.ratelimit.IdleEviction;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;

/**
 * Limite de envios e reemissões de PDF por minuto por usuário (padrão 6; Bucket4j em memória, como o
 * {@code UploadRateLimiter}): o estado se perde no restart e não é compartilhado entre instâncias, o que é aceitável com
 * 1 instância. Entradas ociosas são descartadas.
 */
@Component
public class DocumentRateLimiter {

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

	private final int perMinute;
	private final TimeMeter timeMeter;
	private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
	private final AtomicInteger operations = new AtomicInteger();

	@Autowired
	public DocumentRateLimiter(CrmProperties properties) {
		this(properties.documents().sendsPerMinute(), TimeMeter.SYSTEM_MILLISECONDS);
	}

	DocumentRateLimiter(int perMinute, TimeMeter timeMeter) {
		this.perMinute = perMinute;
		this.timeMeter = timeMeter;
	}

	/** Conta um envio do usuário. @throws RateLimitedException 429 a partir do envio seguinte ao limite no mesmo minuto */
	public void check(UUID userId) {
		cleanupSometimes();
		Entry entry = entries.computeIfAbsent(userId, k -> new Entry(Bucket.builder()
				.withCustomTimePrecision(timeMeter)
				.addLimit(limit -> limit.capacity(perMinute).refillIntervally(perMinute, WINDOW))
				.build()));
		entry.lastAccessNanos = System.nanoTime();
		var probe = entry.bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
			throw new RateLimitedException("Muitos envios de orçamento. Tente novamente em instantes.", seconds);
		}
	}

	private void cleanupSometimes() {
		if (operations.incrementAndGet() % CLEANUP_EVERY != 0) {
			return;
		}
		IdleEviction.evict(entries, e -> e.lastAccessNanos, IDLE_NANOS, MAX_KEYS);
	}
}
