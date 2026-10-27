package crm_data.document.service;

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
 * Limites por minuto por usuário dos documentos clínicos (Bucket4j em memória, como o {@code DocumentRateLimiter} do
 * orçamento): um balde para emissões e outro para PDFs (padrão 30 cada). O estado se perde no restart e não é compartilhado
 * entre instâncias, o que é aceitável com 1 instância. Entradas ociosas são descartadas.
 */
@Component
public class ClinicalDocumentRateLimiter {

	private static final Duration WINDOW = Duration.ofMinutes(1);

	private final PerUser issues;
	private final PerUser pdfs;

	@Autowired
	public ClinicalDocumentRateLimiter(CrmProperties properties) {
		this(properties.clinicalDocuments().issuesPerMinute(), properties.clinicalDocuments().pdfsPerMinute(),
				TimeMeter.SYSTEM_MILLISECONDS);
	}

	ClinicalDocumentRateLimiter(int issuesPerMinute, int pdfsPerMinute, TimeMeter timeMeter) {
		this.issues = new PerUser(issuesPerMinute, timeMeter, "Muitas emissões de documentos. Tente novamente em instantes.");
		this.pdfs = new PerUser(pdfsPerMinute, timeMeter, "Muitos PDFs solicitados. Tente novamente em instantes.");
	}

	/** Conta uma emissão. @throws RateLimitedException 429 a partir da emissão seguinte ao limite no mesmo minuto */
	public void checkIssue(UUID userId) {
		issues.check(userId);
	}

	/** Conta um PDF. @throws RateLimitedException 429 a partir do PDF seguinte ao limite no mesmo minuto */
	public void checkPdf(UUID userId) {
		pdfs.check(userId);
	}

	private static final class PerUser {

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
		private final String message;
		private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
		private final AtomicInteger operations = new AtomicInteger();

		PerUser(int perMinute, TimeMeter timeMeter, String message) {
			this.perMinute = perMinute;
			this.timeMeter = timeMeter;
			this.message = message;
		}

		void check(UUID userId) {
			if (operations.incrementAndGet() % CLEANUP_EVERY == 0) {
				IdleEviction.evict(entries, e -> e.lastAccessNanos, IDLE_NANOS, MAX_KEYS);
			}
			Entry entry = entries.computeIfAbsent(userId, k -> new Entry(Bucket.builder().withCustomTimePrecision(timeMeter)
					.addLimit(limit -> limit.capacity(perMinute).refillIntervally(perMinute, WINDOW)).build()));
			entry.lastAccessNanos = System.nanoTime();
			var probe = entry.bucket.tryConsumeAndReturnRemaining(1);
			if (!probe.isConsumed()) {
				long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).plusMillis(999).toSeconds());
				throw new RateLimitedException(message, seconds);
			}
		}
	}
}
