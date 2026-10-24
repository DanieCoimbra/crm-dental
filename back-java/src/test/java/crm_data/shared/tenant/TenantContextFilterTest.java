package crm_data.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.shared.security.AccessTokenRevocations;
import crm_data.support.PlatformWebTest;

@PlatformWebTest
class TenantContextFilterTest {

	private static final UUID CLINIC = UUID.randomUUID();
	private static final UUID USER = UUID.randomUUID();

	@Autowired
	MockMvc mvc;

	@Autowired
	AccessTokenRevocations revocations;

	private static RequestPostProcessor token(Consumer<Jwt.Builder> claims) {
		return jwt().jwt(claims);
	}

	private static RequestPostProcessor validToken() {
		return token(j -> j.subject(USER.toString()).claim("clinic_id", CLINIC.toString()).claim("role", "DENTIST_ADMIN"));
	}

	@Test
	void populatesTheContextFromTheTokenClaims() throws Exception {
		mvc.perform(get("/probe/tenant").with(validToken()))
				.andExpect(status().isOk())
				.andExpect(content().string(CLINIC + "|" + USER + "|DENTIST_ADMIN"));
	}

	@Test
	void clinicIdFromQueryIsIgnoredAndTheTokenWins() throws Exception {
		mvc.perform(get("/probe/tenant").param("clinicId", UUID.randomUUID().toString())
				.header("X-Clinic-Id", UUID.randomUUID().toString()).with(validToken()))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.startsWith(CLINIC.toString())));
	}

	@Test
	void contextIsClearedWhenTheRequestEnds() throws Exception {
		mvc.perform(get("/probe/tenant").with(validToken())).andExpect(status().isOk());
		assertThat(TenantContext.isPresent()).isFalse();
	}

	@Test
	void contextIsClearedEvenWhenTheRequestFails() throws Exception {
		mvc.perform(get("/probe/boom").with(validToken())).andExpect(status().isInternalServerError());
		assertThat(TenantContext.isPresent()).isFalse();
	}

	@Test
	void requestWithoutJwtHasNoTenantAndBusinessCodeAnswers401() throws Exception {
		mvc.perform(get("/probe/tenant").with(user("u")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unauthorized"));
	}

	@Test
	void tokenWithoutClinicClaimIsRejectedBeforeReachingTheController() throws Exception {
		mvc.perform(get("/probe/tenant")
				.with(token(j -> j.subject(USER.toString()).claim("role", "DENTIST_ADMIN"))))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unauthorized"));
	}

	@Test
	void malformedClaimsAreRejectedWith401() throws Exception {
		RequestPostProcessor[] bad = {
				token(j -> j.subject(USER.toString()).claim("clinic_id", "nao-e-uuid").claim("role", "DENTIST_ADMIN")),
				token(j -> j.subject(USER.toString()).claim("clinic_id", "1-1-1-1-1").claim("role", "DENTIST_ADMIN")),
				token(j -> j.subject("sub-invalido").claim("clinic_id", CLINIC.toString()).claim("role", "DENTIST_ADMIN")),
				token(j -> j.subject(USER.toString()).claim("clinic_id", CLINIC.toString()).claim("role", "SUPERUSER")),
				token(j -> j.subject(USER.toString()).claim("clinic_id", CLINIC.toString())) };
		for (RequestPostProcessor token : bad) {
			mvc.perform(get("/probe/tenant").with(token)).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void tokenIssuedBeforeTheUserSessionsWereRevokedIsRejectedWith401() throws Exception {
		UUID revokedUser = UUID.randomUUID();
		java.time.Instant issuedAt = java.time.Instant.now().minusSeconds(120);
		RequestPostProcessor oldToken = token(j -> j.subject(revokedUser.toString()).claim("clinic_id", CLINIC.toString())
				.claim("role", "RECEPTIONIST").issuedAt(issuedAt));
		mvc.perform(get("/probe/tenant").with(oldToken)).andExpect(status().isOk());

		revocations.revoke(revokedUser);

		mvc.perform(get("/probe/tenant").with(oldToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unauthorized"));
		assertThat(TenantContext.isPresent()).isFalse();
	}

	@Test
	void publicRoutesAreUnaffectedWithoutAnyToken() throws Exception {
		mvc.perform(get("/api/v1/auth/login")).andExpect(status().isNotFound());
	}
}
