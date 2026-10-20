package crm_data.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import crm_data.auth.repository.AuthLookupRepository.UserLookup;
import crm_data.auth.service.AuthSession;
import crm_data.auth.service.AuthSession.UserSummary;
import crm_data.auth.web.AuthRequests.LoginRequest;
import crm_data.auth.web.AuthRequests.LogoutRequest;
import crm_data.auth.web.AuthRequests.RefreshRequest;
import crm_data.auth.web.AuthRequests.RegisterRequest;
import crm_data.shared.security.Role;

/** Um log acidental (por exemplo {@code log.debug("{}", request)}) não pode vazar segredos nem dados pessoais. */
class SensitiveToStringTest {

	private static final String PASSWORD = "SenhaSecreta2026";
	private static final String EMAIL = "ana@clinica.com";
	private static final String TOKEN = "T".repeat(43);

	@Test
	void requestsNeverPrintPasswordsTokensOrEmail() {
		assertThat(new RegisterRequest("Clínica X", "Ana", EMAIL, PASSWORD).toString()).doesNotContain(PASSWORD).doesNotContain(EMAIL);
		assertThat(new LoginRequest(EMAIL, PASSWORD).toString()).doesNotContain(PASSWORD).doesNotContain(EMAIL);
		assertThat(new RefreshRequest(TOKEN).toString()).doesNotContain(TOKEN);
		assertThat(new LogoutRequest(TOKEN).toString()).doesNotContain(TOKEN);
	}

	@Test
	void sessionNeverPrintsTokensNameOrEmail() {
		UserSummary user = new UserSummary(UUID.randomUUID(), "Dra. Ana Souza", EMAIL, Role.DENTIST_ADMIN, UUID.randomUUID());
		AuthSession session = new AuthSession("header.payload.assinatura", TOKEN, "Bearer", 900, user);

		assertThat(session.toString()).doesNotContain("header.payload.assinatura").doesNotContain(TOKEN)
				.doesNotContain(EMAIL).doesNotContain("Dra. Ana Souza").contains("DENTIST_ADMIN");
	}

	@Test
	void lookupNeverPrintsThePasswordHash() {
		UserLookup lookup = new UserLookup(UUID.randomUUID(), UUID.randomUUID(), "$2a$12$hashSecretoDeTeste", Role.RECEPTIONIST, true);

		assertThat(lookup.toString()).doesNotContain("hashSecretoDeTeste").doesNotContain("$2a$").contains("RECEPTIONIST");
	}

	@Test
	void valuesStillCompareByContent() {
		// records mantêm equals/hashCode por componente; só o toString foi substituído
		assertThat(new LoginRequest(EMAIL, PASSWORD)).isEqualTo(new LoginRequest("ANA@clinica.com ", PASSWORD));
	}
}
