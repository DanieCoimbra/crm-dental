package crm_data.support;

/**
 * Limites dos testes de desempenho da Fase 1 (p95 de busca e de linha do tempo). Padrão de 100 ms (meta do contrato);
 * com a variável de ambiente {@code CI} (runner compartilhado, mais lento e mais variável) sobe para o dobro, como faz o
 * {@code LoginPerformanceIT}. {@code -Dperf.limit-ms=<ms>} sobrescreve nos dois casos.
 */
public final class PerfLimits {

	private static final long LOCAL_MS = 100L;
	private static final long CI_MS = 200L;

	private PerfLimits() {
	}

	public static long p95Ms() {
		return Long.getLong("perf.limit-ms", System.getenv("CI") != null ? CI_MS : LOCAL_MS);
	}
}
