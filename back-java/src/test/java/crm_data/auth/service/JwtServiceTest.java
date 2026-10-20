package crm_data.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import crm_data.auth.service.JwtService.AccessToken;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.security.Role;

class JwtServiceTest {

	private static final String SECRET = "segredo-de-teste-com-pelo-menos-64-bytes-para-permitir-HS512-no-teste!!";

	private final JwtConfig config = new JwtConfig();
	private final SecretKey key = config.jwtSecretKey(SECRET);
	private final JwtEncoder encoder = config.jwtEncoder(key);
	private final JwtDecoder decoder = config.jwtDecoder(key);

	private JwtService serviceAt(Instant instant) {
		return new JwtService(encoder, Clock.fixed(instant, ZoneOffset.UTC));
	}

	private final UUID userId = UUID.randomUUID();
	private final UUID clinicId = UUID.randomUUID();

	// ---- Emissão ----

	@Test
	void issuesTheContractedClaimsWith15MinutesOfLife() {
		Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		AccessToken token = serviceAt(issuedAt).issue(userId, clinicId, Role.DENTIST_ADMIN);

		Jwt jwt = decoder.decode(token.value());
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("crm-clinica");
		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.getClaimAsString("clinic_id")).isEqualTo(clinicId.toString());
		assertThat(jwt.getClaimAsString("role")).isEqualTo("DENTIST_ADMIN");
		assertThat(UUID.fromString(jwt.getId())).isNotNull();
		assertThat(jwt.getIssuedAt()).isEqualTo(issuedAt);
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofSeconds(900));
		assertThat(token.expiresInSeconds()).isEqualTo(900);
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
	}

	@Test
	void everyTokenHasAUniqueJti() {
		JwtService service = serviceAt(Instant.now());
		String first = decoder.decode(service.issue(userId, clinicId, Role.RECEPTIONIST).value()).getId();
		String second = decoder.decode(service.issue(userId, clinicId, Role.RECEPTIONIST).value()).getId();

		assertThat(first).isNotEqualTo(second);
	}

	// ---- Validação (decoder) ----

	private String tokenIssuedAt(Instant issuedAt) {
		return serviceAt(issuedAt).issue(userId, clinicId, Role.DENTIST_ADMIN).value();
	}

	@Test
	void acceptsAFreshToken() {
		assertThat(decoder.decode(tokenIssuedAt(Instant.now())).getSubject()).isEqualTo(userId.toString());
	}

	@Test
	void rejectsAnExpiredTokenWithNoClockSkewTolerance() {
		String expired = tokenIssuedAt(Instant.now().minus(Duration.ofMinutes(15)).minusSeconds(5));

		assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void rejectsATamperedPayload() throws Exception {
		String[] parts = tokenIssuedAt(Instant.now()).split("\\.");
		String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
				("{\"iss\":\"crm-clinica\",\"sub\":\"" + userId + "\",\"clinic_id\":\"" + UUID.randomUUID()
						+ "\",\"role\":\"DENTIST_ADMIN\",\"exp\":" + (Instant.now().getEpochSecond() + 900) + "}")
						.getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> decoder.decode(parts[0] + "." + forgedPayload + "." + parts[2]))
				.isInstanceOf(BadJwtException.class);
	}

	@Test
	void rejectsATokenSignedWithAnotherSecret() {
		JwtConfig other = new JwtConfig();
		SecretKey otherKey = other.jwtSecretKey("outro-segredo-de-teste-com-mais-de-32-bytes-ok");
		String foreign = new JwtService(other.jwtEncoder(otherKey), Clock.systemUTC())
				.issue(userId, clinicId, Role.DENTIST_ADMIN).value();

		assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void rejectsAlgNone() {
		String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
		String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
				("{\"iss\":\"crm-clinica\",\"sub\":\"" + userId + "\",\"exp\":" + (Instant.now().getEpochSecond() + 900) + "}")
						.getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".")).isInstanceOf(BadJwtException.class);
		assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".abc")).isInstanceOf(BadJwtException.class);
	}

	@Test
	void rejectsAnotherHmacAlgorithmEvenWithTheRightSecret() throws Exception {
		SignedJWT hs512 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder().issuer("crm-clinica")
				.subject(userId.toString()).expirationTime(Date.from(Instant.now().plusSeconds(900))).build());
		hs512.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

		assertThatThrownBy(() -> decoder.decode(hs512.serialize())).isInstanceOf(BadJwtException.class);
	}

	@Test
	void rejectsAWrongIssuer() throws Exception {
		SignedJWT wrongIssuer = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().issuer("outro-emissor")
				.subject(userId.toString()).expirationTime(Date.from(Instant.now().plusSeconds(900))).build());
		wrongIssuer.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

		assertThatThrownBy(() -> decoder.decode(wrongIssuer.serialize())).isInstanceOf(BadJwtException.class);
	}

	@Test
	void rejectsGarbage() {
		assertThatThrownBy(() -> decoder.decode("isto.nao.e-jwt")).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
		assertThatThrownBy(() -> decoder.decode("")).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
	}

	// ---- Segredo (startup) ----

	@Test
	void blankSecretAbortsStartupNamingTheVariable() {
		assertThatThrownBy(() -> config.jwtSecretKey("")).isInstanceOf(IllegalStateException.class).hasMessageContaining("CRM_JWT_SECRET");
		assertThatThrownBy(() -> config.jwtSecretKey("   ")).isInstanceOf(IllegalStateException.class).hasMessageContaining("CRM_JWT_SECRET");
	}

	@Test
	void secretShorterThan32BytesAbortsStartupWithoutPrintingIt() {
		String shortSecret = "x".repeat(31);

		assertThatThrownBy(() -> config.jwtSecretKey(shortSecret)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRM_JWT_SECRET").hasMessageContaining("32").hasMessageNotContaining(shortSecret);
		assertThat(config.jwtSecretKey("x".repeat(32))).isNotNull();
	}

	@Test
	void secretLengthIsMeasuredInBytesNotCharacters() {
		String sixteenTwoByteChars = "ç".repeat(16); // 16 caracteres, 32 bytes UTF-8
		assertThat(config.jwtSecretKey(sixteenTwoByteChars)).isNotNull();
	}
}
