package crm_data.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import crm_data.shared.config.JwtConfig;
import crm_data.shared.security.Role;
import lombok.RequiredArgsConstructor;

/**
 * Emite o access token (JWT HS256, 15 min) com as claims do contrato: {@code iss}, {@code sub}, {@code clinic_id},
 * {@code role}, {@code jti}, {@code iat} e {@code exp = iat + 900 s}. A validação (algoritmo fixo, emissor e
 * expiração) fica no {@code JwtDecoder} de {@link JwtConfig}.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

	public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

	/** Access token emitido e seu tempo de vida em segundos. */
	public record AccessToken(String value, long expiresInSeconds) {
	}

	private final JwtEncoder encoder;
	private final Clock clock;

	public AccessToken issue(UUID userId, UUID clinicId, Role role) {
		Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(JwtConfig.ISSUER)
				.subject(userId.toString())
				.claim("clinic_id", clinicId.toString())
				.claim("role", role.name())
				.id(UUID.randomUUID().toString())
				.issuedAt(issuedAt)
				.expiresAt(issuedAt.plus(ACCESS_TOKEN_TTL))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(token, ACCESS_TOKEN_TTL.toSeconds());
	}
}
