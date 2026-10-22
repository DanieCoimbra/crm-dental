package crm_data.auth.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import crm_data.shared.error.RateLimitedException;
import crm_data.shared.ratelimit.IdleEviction;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;
import io.github.bucket4j.TimeMeter;

/**
 * Limites em memória (Bucket4j): 20 requisições/min por IP em cada ação (login, registro, refresh), 5 falhas de
 * login/min por IP+e-mail e 20 falhas de login a cada 15 min por e-mail, somando os IPs que nunca entraram nessa
 * conta (freia o ataque distribuído; IP com login bem-sucedido nos últimos 7 dias fica fora dessa soma, então um
 * terceiro não impede o dono de entrar do lugar de sempre). O estado se
 * perde a cada restart e não é compartilhado entre instâncias, o que é aceitável com 1 instância. As chaves de e-mail
 * são guardadas como hash. Entradas ociosas são descartadas e, no teto de chaves, as menos recentes.
 */
@Component
public class LoginRateLimiter {

	static final int REQUESTS_PER_MINUTE_PER_IP = 20;
	static final int LOGIN_FAILURES_PER_MINUTE = 5;
	static final int LOGIN_FAILURES_PER_EMAIL = 20;
	private static final Duration WINDOW = Duration.ofMinutes(1);
	private static final Duration EMAIL_WINDOW = Duration.ofMinutes(15);
	/** Maior que a maior janela: uma entrada com falhas pendentes nunca é descartada antes de a janela expirar. */
	private static final long IDLE_NANOS = Duration.ofMinutes(20).toNanos();
	/** Quanto tempo um IP continua "conhecido" para a conta após um login bem-sucedido (a vida do refresh token). */
	private static final long KNOWN_LOCATION_NANOS = Duration.ofDays(7).toNanos();
	private static final int CLEANUP_EVERY = 512;
	private static final int MAX_KEYS = 100_000;

	private static final class Entry {
		final Bucket bucket;
		volatile long lastAccessNanos = System.nanoTime();

		Entry(Bucket bucket) {
			this.bucket = bucket;
		}
	}

	/** Último login bem-sucedido de um par IP+e-mail (sem bucket). */
	private static final class Seen {
		volatile long lastAccessNanos = System.nanoTime();
	}

	private final TimeMeter timeMeter;
	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Seen> knownLocations = new ConcurrentHashMap<>();
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
		Entry entry = entry("ip|" + action + "|" + ip, REQUESTS_PER_MINUTE_PER_IP, WINDOW);
		var probe = entry.bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			throw limited(probe.getNanosToWaitForRefill());
		}
	}

	/**
	 * Bloqueia o login se o IP+e-mail esgotou as falhas do minuto ou, para um IP que nunca entrou nessa conta, se o
	 * e-mail esgotou as falhas dos 15 min (não consome). IP com login bem-sucedido nos últimos 7 dias não é afetado
	 * pelo limite por e-mail, então terceiros não conseguem travar o dono da conta.
	 */
	public void checkLoginAllowed(String ip, String email) {
		EstimationProbe byPair = entry(failureKey(ip, email), LOGIN_FAILURES_PER_MINUTE, WINDOW).bucket.estimateAbilityToConsume(1);
		if (!byPair.canBeConsumed()) {
			throw limited(byPair.getNanosToWaitForRefill());
		}
		if (isKnownLocation(ip, email)) {
			return;
		}
		EstimationProbe byEmail = entry(emailKey(email), LOGIN_FAILURES_PER_EMAIL, EMAIL_WINDOW).bucket.estimateAbilityToConsume(1);
		if (!byEmail.canBeConsumed()) {
			throw limited(byEmail.getNanosToWaitForRefill());
		}
	}

	public void recordLoginFailure(String ip, String email) {
		entry(failureKey(ip, email), LOGIN_FAILURES_PER_MINUTE, WINDOW).bucket.tryConsume(1);
		if (!isKnownLocation(ip, email)) {
			entry(emailKey(email), LOGIN_FAILURES_PER_EMAIL, EMAIL_WINDOW).bucket.tryConsume(1);
		}
	}

	/** Login bem-sucedido: zera as falhas do par IP+e-mail e do e-mail e passa a reconhecer o IP para essa conta. */
	public void resetLoginFailures(String ip, String email) {
		entries.remove(failureKey(ip, email));
		entries.remove(emailKey(email));
		cleanupSometimes();
		knownLocations.computeIfAbsent(failureKey(ip, email), k -> new Seen()).lastAccessNanos = System.nanoTime();
	}

	private boolean isKnownLocation(String ip, String email) {
		Seen seen = knownLocations.get(failureKey(ip, email));
		return seen != null && System.nanoTime() - seen.lastAccessNanos <= KNOWN_LOCATION_NANOS;
	}

	/**
	 * Bloqueia a troca de senha do usuário se ele já esgotou as falhas do minuto na "senha atual" (não consome).
	 * A chave é o usuário, não o IP: quem tenta adivinhar a senha com um access token roubado não escapa trocando de IP.
	 */
	public void checkPasswordChangeAllowed(UUID userId) {
		EstimationProbe probe = entry(passwordChangeKey(userId), LOGIN_FAILURES_PER_MINUTE, WINDOW).bucket.estimateAbilityToConsume(1);
		if (!probe.canBeConsumed()) {
			throw limited(probe.getNanosToWaitForRefill());
		}
	}

	public void recordPasswordChangeFailure(UUID userId) {
		entry(passwordChangeKey(userId), LOGIN_FAILURES_PER_MINUTE, WINDOW).bucket.tryConsume(1);
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

	private static String emailKey(String email) {
		return "email|" + Hashing.sha256Hex(email);
	}

	private Entry entry(String key, int capacity, Duration window) {
		cleanupSometimes();
		Entry entry = entries.computeIfAbsent(key, k -> new Entry(Bucket.builder()
				.withCustomTimePrecision(timeMeter)
				.addLimit(limit -> limit.capacity(capacity).refillIntervally(capacity, window))
				.build()));
		entry.lastAccessNanos = System.nanoTime();
		return entry;
	}

	private void cleanupSometimes() {
		if (operations.incrementAndGet() % CLEANUP_EVERY != 0) {
			return;
		}
		// limite de memória sob ataque com milhares de chaves distintas: remove as menos recentes, nunca tudo
		IdleEviction.evict(entries, e -> e.lastAccessNanos, IDLE_NANOS, MAX_KEYS);
		IdleEviction.evict(knownLocations, s -> s.lastAccessNanos, KNOWN_LOCATION_NANOS, MAX_KEYS);
	}

	private static RateLimitedException limited(long nanosToWait) {
		long seconds = Math.max(1, Duration.ofNanos(nanosToWait).plusMillis(999).toSeconds());
		return new RateLimitedException("Muitas tentativas. Tente novamente em instantes.", seconds);
	}
}
