package crm_data.shared.security;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Corte de access tokens por usuário. Quando as sessões de um usuário são revogadas (desativação, redefinição ou troca
 * de senha, troca de e-mail), os access tokens emitidos ANTES desse instante deixam de valer, sem esperar os 15 min de
 * vida. Em memória e por instância, como o {@code LoginRateLimiter}: com mais de uma instância o corte precisa ir
 * para o banco. Só guarda quem foi revogado nos últimos 15 min, o que limita o mapa.
 */
public class AccessTokenRevocations {

	/** Vida do access token: depois disso nenhum token anterior ao corte pode estar vivo. */
	static final long TTL_SECONDS = 15 * 60;

	private final Clock clock;
	private final ConcurrentHashMap<UUID, Instant> cutoffs = new ConcurrentHashMap<>();

	/** Declarado como bean em {@code SecurityConfig}, para que as fatias web que importam a cadeia o recebam. */
	public AccessTokenRevocations(Clock clock) {
		this.clock = clock;
	}

	public void revoke(UUID userId) {
		Instant now = clock.instant();
		cutoffs.put(userId, now);
		cutoffs.values().removeIf(cutoff -> cutoff.isBefore(now.minusSeconds(TTL_SECONDS)));
	}

	/**
	 * @param issuedAt {@code iat} do token (em segundos); token sem {@code iat} é tratado como anterior ao corte
	 * @return {@code true} se o token foi emitido antes da revogação. O corte é truncado ao segundo, como o {@code iat}:
	 *         quem faz login no mesmo segundo da revogação (nova senha) não é recusado.
	 */
	public boolean isRevoked(UUID userId, Instant issuedAt) {
		Instant cutoff = cutoffs.get(userId);
		if (cutoff == null) {
			return false;
		}
		return issuedAt == null || issuedAt.isBefore(cutoff.truncatedTo(ChronoUnit.SECONDS));
	}
}
