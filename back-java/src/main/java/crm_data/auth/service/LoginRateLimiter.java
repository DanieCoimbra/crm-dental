package crm_data.auth.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import crm_data.shared.error.RateLimitedException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;
import io.github.bucket4j.TimeMeter;

/**
 * Limites em memória (Bucket4j): 20 requisições/min por IP em cada ação (login, registro, refresh) e 5 falhas de
 * login/min por IP+e-mail. O estado se perde a cada restart e não é compartilhado entre instâncias, o que é aceitável
 * com 1 instância. As chaves de e-mail são guardadas como hash. Entradas ociosas são descartadas.
 */
@Component
public class LoginRateLimiter {

	static final int REQUESTS_PER_MINUTE_PER_IP = 20;
	static final int LOGIN_FAILURES_PER_MINUTE = 5;
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
	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
	private final AtomicInteger operations = new AtomicInteger();

	@Autowired
	public LoginRateLimiter() {
		this(TimeMeter.SYSTEM_MILLISECONDS);
	}

	LoginRateLimiter(TimeMeter timeMeter) {
		this.timeMeter = timeMeter;
	}

	/** Conta uma requisição da ação (por IP). @throws RateLimitedException 429 acima de 20 por minuto */
	public void checkIp(String action, String ip) {
		Entry entry = entry("ip|" + action + "|" + ip, REQUESTS_PER_MINUTE_PER_IP);
		var probe = entry.bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			throw limited(probe.getNanosToWaitForRefill());
		}
	}

	/** Bloqueia o login se o IP+e-mail já esgotou as falhas do minuto (não consome). */
	public void checkLoginAllowed(String ip, String email) {
		EstimationProbe probe = entry(failureKey(ip, email), LOGIN_FAILURES_PER_MINUTE).bucket.estimateAbilityToConsume(1);
		if (!probe.canBeConsumed()) {
			throw limited(probe.getNanosToWaitForRefill());
		}
	}

	public void recordLoginFailure(String ip, String email) {
		entry(failureKey(ip, email), LOGIN_FAILURES_PER_MINUTE).bucket.tryConsume(1);
	}

	/** Login bem-sucedido: zera as falhas do par IP+e-mail. */
	public void resetLoginFailures(String ip, String email) {
		entries.remove(failureKey(ip, email));
	}

	/**
	 * Bloqueia a troca de senha do usuário se ele já esgotou as falhas do minuto na "senha atual" (não consome).
	 * A chave é o usuário, não o IP: quem tenta adivinhar a senha com um access token roubado não escapa trocando de IP.
	 */
	public void checkPasswordChangeAllowed(UUID userId) {
		EstimationProbe probe = entry(passwordChangeKey(userId), LOGIN_FAILURES_PER_MINUTE).bucket.estimateAbilityToConsume(1);
		if (!probe.canBeConsumed()) {
			throw limited(probe.getNanosToWaitForRefill());
		}
	}

	public void recordPasswordChangeFailure(UUID userId) {
		entry(passwordChangeKey(userId), LOGIN_FAILURES_PER_MINUTE).bucket.tryConsume(1);
	}

	/** Troca de senha concluída: zera as falhas do usuário. */
	public void resetPasswordChangeFailures(UUID userId) {
		entries.remove(passwordChangeKey(userId));
	}

	private static String passwordChangeKey(UUID userId) {
		return "pwd|" + userId;
	}

	private static String failureKey(String ip, String email) {
		return "fail|" + ip + "|" + Hashing.sha256Hex(email);
	}

	private Entry entry(String key, int perMinute) {
		cleanupSometimes();
		Entry entry = entries.computeIfAbsent(key, k -> new Entry(Bucket.builder()
				.withCustomTimePrecision(timeMeter)
				.addLimit(limit -> limit.capacity(perMinute).refillIntervally(perMinute, WINDOW))
				.build()));
		entry.lastAccessNanos = System.nanoTime();
		return entry;
	}

	private void cleanupSometimes() {
		if (operations.incrementAndGet() % CLEANUP_EVERY != 0) {
			return;
		}
		long now = System.nanoTime();
		entries.entrySet().removeIf(e -> now - e.getValue().lastAccessNanos > IDLE_NANOS);
		if (entries.size() >= MAX_KEYS) {
			entries.clear(); // limite de memória sob ataque com milhares de chaves distintas
		}
	}

	private static RateLimitedException limited(long nanosToWait) {
		long seconds = Math.max(1, Duration.ofNanos(nanosToWait).plusMillis(999).toSeconds());
		return new RateLimitedException("Muitas tentativas. Tente novamente em instantes.", seconds);
	}
}
