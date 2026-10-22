package crm_data.auth.service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import crm_data.shared.error.RateLimitedException;

/**
 * Limita quantos hashes BCrypt (login e registro) rodam ao mesmo tempo. O limite por IP não protege a CPU de um ataque
 * distribuído; com o portão, o excedente espera pouco e, se não conseguir vaga, recebe 429 em vez de enfileirar até o
 * timeout do cliente.
 */
@Component
public class HashingGate {

	static final Duration MAX_WAIT = Duration.ofSeconds(3);
	private static final long RETRY_AFTER_SECONDS = 3;

	private final Semaphore permits;
	private final Duration maxWait;

	@Autowired
	public HashingGate() {
		this(Math.max(2, Runtime.getRuntime().availableProcessors()), MAX_WAIT);
	}

	/** Também usado por testes de outros pacotes que montam o service à mão. */
	public HashingGate(int concurrency, Duration maxWait) {
		this.permits = new Semaphore(concurrency, true);
		this.maxWait = maxWait;
	}

	/** Executa o trabalho de hash dentro do portão. @throws RateLimitedException 429 se não houver vaga a tempo */
	public <T> T run(Supplier<T> hashing) {
		boolean acquired;
		try {
			acquired = permits.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw busy();
		}
		if (!acquired) {
			throw busy();
		}
		try {
			return hashing.get();
		}
		finally {
			permits.release();
		}
	}

	private static RateLimitedException busy() {
		return new RateLimitedException("Serviço ocupado. Tente novamente em instantes.", RETRY_AFTER_SECONDS);
	}
}
