package crm_data.commercial.service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import crm_data.shared.config.CrmProperties;

/**
 * Vagas de envio de PDF simultâneos por instância. O envio segura <strong>uma conexão do pool</strong> (10) durante a
 * geração e o upload ao Storage; limitar a poucos (padrão 3) deixa o resto do pool para as demais rotas. Sem vaga depois
 * da espera curta, o chamador responde 503 com {@code Retry-After}. A vaga é liberada sempre, inclusive quando o commit
 * falha ou a requisição é cancelada ({@code finally} do chamador).
 */
@Component
public class DocumentSlots {

	static final Duration MAX_WAIT = Duration.ofSeconds(2);

	private final Semaphore permits;

	@Autowired
	public DocumentSlots(CrmProperties properties) {
		this(properties.documents().maxConcurrent());
	}

	DocumentSlots(int maxConcurrent) {
		this.permits = new Semaphore(maxConcurrent, true);
	}

	/** @return {@code true} se conseguiu a vaga dentro da espera máxima */
	public boolean tryAcquire() {
		return tryAcquire(MAX_WAIT);
	}

	boolean tryAcquire(Duration wait) {
		try {
			return permits.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public void release() {
		permits.release();
	}

	/** Vagas livres (para teste e diagnóstico). */
	public int available() {
		return permits.availablePermits();
	}
}
