package crm_data.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import crm_data.auth.domain.User;
import crm_data.auth.repository.AuthLookupRepository;
import crm_data.auth.repository.AuthLookupRepository.RefreshLookup;
import crm_data.auth.repository.RefreshTokenRepository;
import crm_data.auth.repository.UserRepository;
import crm_data.auth.service.AuthEvents.RefreshReuseDetected;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantTransactions;

/** Ramos da rotação do refresh token, isolados (a integração com o banco real está em AuthFlowIT). */
class RefreshTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
	private static final String PLAIN = "A".repeat(43);

	private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private final AuthLookupRepository lookup = mock(AuthLookupRepository.class);
	private final TenantTransactions tenantTransactions = mock(TenantTransactions.class);
	private final AuthSessionFactory sessions = mock(AuthSessionFactory.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID familyId = UUID.randomUUID();
	private final UUID tokenId = UUID.randomUUID();
	private final User user = new User(userId, "Ana", "ana@clinica.com", "hash", Role.DENTIST_ADMIN);
	private final AuthSession session = new AuthSession("novo-access", "novo-refresh", "Bearer", 900, null);
	private RefreshTokenService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		service = spy(new RefreshTokenService(tokens, users, lookup, tenantTransactions, sessions, events,
				Clock.fixed(NOW, ZoneOffset.UTC)));
		org.mockito.Mockito.doReturn("novo-refresh").when(service).issue(any(), any());
		when(tenantTransactions.execute(any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
		org.mockito.Mockito.doAnswer(call -> {
			((Runnable) call.getArgument(1)).run();
			return null;
		}).when(tenantTransactions).run(any(), any());
		when(users.findOwnById(userId)).thenReturn(Optional.of(user));
		when(sessions.create(user, "novo-refresh")).thenReturn(session);
	}

	private RefreshLookup stored(Instant expiresAt, Instant usedAt, Instant revokedAt) {
		RefreshLookup token = new RefreshLookup(tokenId, clinicId, userId, familyId, expiresAt, usedAt, revokedAt);
		when(lookup.findRefreshToken(Hashing.sha256Hex(PLAIN))).thenReturn(Optional.of(token));
		return token;
	}

	private void assertUnauthorized() {
		assertThatThrownBy(() -> service.rotate(PLAIN)).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.UNAUTHORIZED));
	}

	@Test
	void validTokenIsConsumedAndReplacedInTheSameFamily() {
		stored(NOW.plusSeconds(3600), null, null);
		when(tokens.markUsed(tokenId, NOW)).thenReturn(1);

		assertThat(service.rotate(PLAIN)).isSameAs(session);

		verify(tenantTransactions).execute(eq(clinicId), any());
		verify(service).issue(userId, familyId); // nova emissão na MESMA família
		verify(tokens, never()).revokeFamily(any(), any());
		verifyNoInteractions(events);
	}

	@Test
	void lookupUsesTheSha256OfTheTokenNeverTheTokenItself() {
		stored(NOW.plusSeconds(3600), null, null);
		when(tokens.markUsed(tokenId, NOW)).thenReturn(1);

		service.rotate(PLAIN);

		ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
		verify(lookup).findRefreshToken(hash.capture());
		assertThat(hash.getValue()).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(PLAIN);
	}

	@Test
	void unknownTokenIsUnauthorizedWithoutTouchingTheTenant() {
		when(lookup.findRefreshToken(any())).thenReturn(Optional.empty());

		assertUnauthorized();

		verifyNoInteractions(tenantTransactions, events);
	}

	@Test
	void revokedTokenIsUnauthorized() {
		stored(NOW.plusSeconds(3600), null, NOW.minusSeconds(10));

		assertUnauthorized();

		verifyNoInteractions(tenantTransactions, events);
	}

	@Test
	void expiredTokenIsUnauthorized() {
		stored(NOW.minusSeconds(1), null, null);

		assertUnauthorized();

		verify(tokens, never()).markUsed(any(), any());
	}

	@Test
	void tokenExpiringExactlyNowIsAlreadyExpired() {
		stored(NOW, null, null);

		assertUnauthorized();
	}

	@Test
	void reusedTokenRevokesTheWholeFamilyAndRaisesTheEvent() {
		stored(NOW.plusSeconds(3600), NOW.minusSeconds(60), null);

		assertUnauthorized();

		verify(tokens).revokeFamily(familyId, NOW);
		verify(events).publishEvent(new RefreshReuseDetected(userId, familyId));
		verify(tokens, never()).markUsed(any(), any());
	}

	@Test
	void reuseIsDetectedEvenWhenTheUsedTokenIsAlreadyExpired() {
		stored(NOW.minusSeconds(1), NOW.minusSeconds(60), null);

		assertUnauthorized();

		verify(tokens).revokeFamily(familyId, NOW);
		verify(events).publishEvent(new RefreshReuseDetected(userId, familyId));
	}

	@Test
	void losingTheRaceForTheAtomicUpdateIsTreatedAsReuse() {
		stored(NOW.plusSeconds(3600), null, null);
		when(tokens.markUsed(tokenId, NOW)).thenReturn(0); // outra requisição consumiu primeiro

		assertUnauthorized();

		verify(tokens).revokeFamily(familyId, NOW);
		verify(events).publishEvent(new RefreshReuseDetected(userId, familyId));
		verify(service, never()).issue(any(), any());
	}

	@Test
	void inactiveUserCannotRefreshAndTheFamilyIsRevoked() {
		stored(NOW.plusSeconds(3600), null, null);
		when(tokens.markUsed(tokenId, NOW)).thenReturn(1);
		User inactive = mock(User.class);
		when(inactive.isActive()).thenReturn(false);
		when(users.findOwnById(userId)).thenReturn(Optional.of(inactive));

		assertUnauthorized();

		verify(tokens).revokeFamily(familyId, NOW);
		verify(service, never()).issue(any(), any());
		verifyNoInteractions(events); // desativação não é reuso
	}

	@Test
	void missingUserCannotRefreshEither() {
		stored(NOW.plusSeconds(3600), null, null);
		when(tokens.markUsed(tokenId, NOW)).thenReturn(1);
		when(users.findOwnById(userId)).thenReturn(Optional.empty());

		assertUnauthorized();

		verify(tokens).revokeFamily(familyId, NOW);
	}

	// ---- logout e revogação geral ----

	@Test
	void logoutRevokesTheFamilyOfAKnownToken() {
		stored(NOW.plusSeconds(3600), null, null);

		service.revoke(PLAIN);

		verify(tenantTransactions).run(eq(clinicId), any());
		verify(tokens).revokeFamily(familyId, NOW);
	}

	@Test
	void logoutOfAnUnknownTokenIsSilentlyIdempotent() {
		when(lookup.findRefreshToken(any())).thenReturn(Optional.empty());

		service.revoke(PLAIN);

		verifyNoInteractions(tenantTransactions, tokens);
	}

	@Test
	void revokeAllOfUserRevokesAndPublishesTheSessionsEvent() {
		when(tokens.revokeAllOfUser(userId, NOW)).thenReturn(3);

		assertThat(service.revokeAllOfUser(userId)).isEqualTo(3);

		verify(events).publishEvent(new AuthEvents.UserSessionsRevoked(userId));
	}
}
