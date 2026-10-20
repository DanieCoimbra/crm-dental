package crm_data.auth.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import crm_data.shared.security.Role;

/**
 * Únicas leituras ANTES de existir tenant: as funções {@code SECURITY DEFINER} do banco
 * ({@code core.find_user_for_login} e {@code core.find_refresh_token}), com parâmetros vinculados. Usa JDBC puro,
 * fora de transação JPA (toda transação JPA exige tenant).
 */
@Repository
public class AuthLookupRepository {

	/** Linha mínima devolvida pelo login. */
	public record UserLookup(UUID id, UUID clinicId, String passwordHash, Role role, boolean active) {

		/** Nunca imprime o hash da senha. */
		@Override
		public String toString() {
			return "UserLookup[id=" + id + ", clinicId=" + clinicId + ", role=" + role + ", active=" + active + "]";
		}
	}

	/** Linha mínima devolvida pelo refresh. */
	public record RefreshLookup(UUID id, UUID clinicId, UUID userId, UUID familyId, Instant expiresAt, Instant usedAt,
			Instant revokedAt) {
	}

	private final JdbcClient jdbc;

	public AuthLookupRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @param email já normalizado (minúsculas, sem espaços nas pontas) */
	public Optional<UserLookup> findUserForLogin(String email) {
		return jdbc.sql("SELECT id, clinic_id, password_hash, role, active FROM core.find_user_for_login(:email)")
				.param("email", email)
				.query((rs, rowNum) -> new UserLookup(rs.getObject("id", UUID.class), rs.getObject("clinic_id", UUID.class),
						rs.getString("password_hash"), Role.valueOf(rs.getString("role")), rs.getBoolean("active")))
				.optional();
	}

	/** @param tokenHash SHA-256 hexadecimal minúsculo (64 caracteres) */
	public Optional<RefreshLookup> findRefreshToken(String tokenHash) {
		return jdbc.sql("""
				SELECT id, clinic_id, user_id, family_id, expires_at, used_at, revoked_at
				  FROM core.find_refresh_token(:hash)""")
				.param("hash", tokenHash)
				.query((rs, rowNum) -> new RefreshLookup(rs.getObject("id", UUID.class), rs.getObject("clinic_id", UUID.class),
						rs.getObject("user_id", UUID.class), rs.getObject("family_id", UUID.class),
						instant(rs.getObject("expires_at", OffsetDateTime.class)),
						instant(rs.getObject("used_at", OffsetDateTime.class)),
						instant(rs.getObject("revoked_at", OffsetDateTime.class))))
				.optional();
	}

	private static Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
