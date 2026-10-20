package crm_data.auth.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.domain.RefreshToken;
import crm_data.auth.domain.User;
import crm_data.auth.repository.AuthLookupRepository;
import crm_data.auth.repository.AuthLookupRepository.RefreshLookup;
import crm_data.auth.repository.RefreshTokenRepository;
import crm_data.auth.repository.UserRepository;
import crm_data.auth.service.AuthEvents.RefreshReuseDetected;
import crm_data.auth.service.AuthEvents.UserSessionsRevoked;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantTransactions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;

/**
 * Refresh tokens opacos (256 bits aleatórios, base64url, 7 dias, uso único). No banco fica só o SHA-256. A cada uso
 * o token é trocado por outro da mesma família; reapresentar um token já usado revoga a família inteira.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

	static final Duration TTL = Duration.ofDays(7);
	private static final int TOKEN_BYTES = 32;
	private static final SecureRandom RANDOM = new SecureRandom();

	/** Resultado da rotação dentro da transação do tenant (a decisão de erro é tomada após o commit). */
	private record Rotation(AuthSession session, boolean reuse) {
	}

	@PersistenceContext
	private EntityManager entityManager;

	private final RefreshTokenRepository tokens;
	private final UserRepository users;
	private final AuthLookupRepository lookup;
	private final TenantTransactions tenantTransactions;
	private final AuthSessionFactory sessions;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	/**
	 * Cria e persiste um refresh token e devolve o valor em claro (único momento em que existe). Exige transação do
	 * tenant em andamento.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public String issue(UUID userId, UUID familyId) {
		String plain = newToken();
		entityManager.persist(new RefreshToken(UUID.randomUUID(), userId, familyId, Hashing.sha256Hex(plain),
				clock.instant().plus(TTL)));
		return plain;
	}

	/** Troca o refresh token por um novo par. @throws BusinessException 401 em qualquer falha (sem detalhar o motivo) */
	public AuthSession rotate(String plain) {
		RefreshLookup token = lookup.findRefreshToken(Hashing.sha256Hex(plain)).orElseThrow(RefreshTokenService::unauthorized);
		Instant now = clock.instant();

		if (token.revokedAt() != null) {
			throw unauthorized();
		}
		if (token.usedAt() != null) { // reuso: alguém reapresentou um token já trocado
			tenantTransactions.run(token.clinicId(), () -> tokens.revokeFamily(token.familyId(), now));
			events.publishEvent(new RefreshReuseDetected(token.userId(), token.familyId()));
			throw unauthorized();
		}
		if (!token.expiresAt().isAfter(now)) {
			throw unauthorized();
		}

		Rotation rotation = tenantTransactions.execute(token.clinicId(), () -> rotateInTenant(token, now));
		if (rotation.session() != null) {
			return rotation.session();
		}
		if (rotation.reuse()) {
			events.publishEvent(new RefreshReuseDetected(token.userId(), token.familyId()));
		}
		throw unauthorized();
	}

	/** Revoga o token informado e sua família. Idempotente: token desconhecido não é erro. */
	public void revoke(String plain) {
		Optional<RefreshLookup> token = lookup.findRefreshToken(Hashing.sha256Hex(plain));
		token.ifPresent(t -> tenantTransactions.run(t.clinicId(), () -> tokens.revokeFamily(t.familyId(), clock.instant())));
	}

	/**
	 * Revoga todas as sessões do usuário (troca de senha, desativação ou reset, acionados pela F05) dentro da
	 * transação do tenant da chamada.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int revokeAllOfUser(UUID userId) {
		int revoked = tokens.revokeAllOfUser(userId, clock.instant());
		events.publishEvent(new UserSessionsRevoked(userId));
		return revoked;
	}

	private Rotation rotateInTenant(RefreshLookup token, Instant now) {
		if (tokens.markUsed(token.id(), now) == 0) { // outro pedido o usou primeiro: tratar como reuso
			tokens.revokeFamily(token.familyId(), now);
			return new Rotation(null, true);
		}
		User user = users.findById(token.userId()).filter(User::isActive).orElse(null);
		if (user == null) { // inativado (ou removido) desde a emissão
			tokens.revokeFamily(token.familyId(), now);
			return new Rotation(null, false);
		}
		String next = issue(user.getId(), token.familyId());
		return new Rotation(sessions.create(user, next), false);
	}

	private static String newToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static BusinessException unauthorized() {
		return new BusinessException(ProblemTypes.UNAUTHORIZED, "Token inválido ou expirado.");
	}
}
