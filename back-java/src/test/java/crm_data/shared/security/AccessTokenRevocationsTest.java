package crm_data.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AccessTokenRevocationsTest {

	private static final Instant NOW = Instant.parse("2026-10-24T12:00:30.700Z");
	private static final UUID USER = UUID.randomUUID();

	private static AccessTokenRevocations at(Instant instant) {
		return new AccessTokenRevocations(Clock.fixed(instant, ZoneOffset.UTC));
	}

	@Test
	void userNeverRevokedKeepsHisTokens() {
		assertThat(at(NOW).isRevoked(USER, NOW.minusSeconds(60))).isFalse();
	}

	@Test
	void tokenIssuedBeforeTheRevocationIsRejected() {
		AccessTokenRevocations revocations = at(NOW);
		revocations.revoke(USER);

		assertThat(revocations.isRevoked(USER, Instant.parse("2026-10-24T12:00:10Z"))).isTrue();
	}

	@Test
	void tokenIssuedInTheSameSecondAsTheRevocationIsAccepted() {
		AccessTokenRevocations revocations = at(NOW);
		revocations.revoke(USER);

		// iat é gravado em segundos: quem entra com a senha nova no mesmo segundo não pode ser recusado
		assertThat(revocations.isRevoked(USER, Instant.parse("2026-10-24T12:00:30Z"))).isFalse();
		assertThat(revocations.isRevoked(USER, Instant.parse("2026-10-24T12:01:00Z"))).isFalse();
	}

	@Test
	void tokenWithoutIssuedAtIsRejectedOnceTheUserWasRevoked() {
		AccessTokenRevocations revocations = at(NOW);
		revocations.revoke(USER);

		assertThat(revocations.isRevoked(USER, null)).isTrue();
	}

	@Test
	void otherUsersAreNotAffected() {
		AccessTokenRevocations revocations = at(NOW);
		revocations.revoke(USER);

		assertThat(revocations.isRevoked(UUID.randomUUID(), NOW.minusSeconds(60))).isFalse();
	}

	@Test
	void cutoffsOlderThanTheTokenLifetimeAreDroppedOnTheNextRevocation() {
		Instant[] now = { NOW };
		Clock clock = new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now[0];
			}
		};
		AccessTokenRevocations revocations = new AccessTokenRevocations(clock);
		UUID old = UUID.randomUUID();
		revocations.revoke(old);
		Instant oldToken = NOW.minusSeconds(60);
		assertThat(revocations.isRevoked(old, oldToken)).isTrue();

		now[0] = NOW.plusSeconds(16 * 60); // nenhum token anterior ao corte pode estar vivo
		revocations.revoke(UUID.randomUUID());

		assertThat(revocations.isRevoked(old, oldToken)).isFalse();
	}
}
