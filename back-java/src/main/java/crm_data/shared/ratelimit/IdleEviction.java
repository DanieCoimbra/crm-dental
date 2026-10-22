package crm_data.shared.ratelimit;

import java.util.Comparator;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Limpeza dos mapas de limite em memória: descarta as entradas ociosas e, se o mapa ainda estiver no teto, as mais antigas
 * (10% do teto), nunca o mapa inteiro. Esvaziar tudo liberaria quem está bloqueado no momento em que um atacante enche o
 * mapa com chaves distintas.
 */
public final class IdleEviction {

	private IdleEviction() {
	}

	/**
	 * @param lastAccessNanos instante (nanoTime) do último acesso da entrada
	 * @param idleNanos entradas sem acesso há mais que isto são removidas
	 * @param maxKeys teto de chaves; ao alcançá-lo, remove as 10% menos recentes
	 */
	public static <K, V> void evict(Map<K, V> entries, ToLongFunction<V> lastAccessNanos, long idleNanos, int maxKeys) {
		long now = System.nanoTime();
		entries.entrySet().removeIf(e -> now - lastAccessNanos.applyAsLong(e.getValue()) > idleNanos);
		if (entries.size() < maxKeys) {
			return;
		}
		int toRemove = Math.max(1, maxKeys / 10);
		entries.entrySet().stream()
				.sorted(Comparator.comparingLong(e -> lastAccessNanos.applyAsLong(e.getValue())))
				.limit(toRemove)
				.map(Map.Entry::getKey)
				.toList()
				.forEach(entries::remove);
	}
}
