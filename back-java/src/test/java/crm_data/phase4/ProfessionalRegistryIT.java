package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/** F01: CRO no perfil do dentista, contra o schema real (V031) com RLS e o trigger {@code guard_user_cro}. */
class ProfessionalRegistryIT extends Phase4ItSupport {

	private static final String ROUTE = "/api/v1/me/professional-registry";

	private static String body(String cro, String state, long version) {
		return "{\"cro\":%s,\"croState\":%s,\"version\":%d}".formatted(cro == null ? "null" : "\"" + cro + "\"",
				state == null ? "null" : "\"" + state + "\"", version);
	}

	private String storedCro(Clinic clinic) {
		return DatabaseContainer.admin().queryForObject("SELECT cro_number || '/' || cro_state FROM core.users WHERE id = ?",
				String.class, userOf(clinic, Role.DENTIST_ADMIN));
	}

	@Test
	void dentistRegistersChangesAndClearsTheCro() throws Exception {
		Clinic clinic = newDentistClinic();

		JsonNode saved = registerCro(clinic, "12345", "SP");
		assertThat(saved.get("cro").asString()).isEqualTo("12345");
		assertThat(saved.get("croState").asString()).isEqualTo("SP");
		assertThat(storedCro(clinic)).isEqualTo("12345/SP");

		registerCro(clinic, "98765", "RJ");
		assertThat(storedCro(clinic)).isEqualTo("98765/RJ");

		mvc.perform(json(put(ROUTE), body(null, null, versionOf(clinic, Role.DENTIST_ADMIN))).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.cro").value((Object) null)).andExpect(jsonPath("$.croState").value((Object) null));
		assertThat(DatabaseContainer.admin().queryForObject("SELECT cro_number FROM core.users WHERE id = ?", String.class,
				userOf(clinic, Role.DENTIST_ADMIN))).isNull();
	}

	@Test
	void meReturnsTheCroAndReceptionSeesNull() throws Exception {
		Clinic clinic = newDentistClinic();
		registerCro(clinic, "12345", "SP");

		mvc.perform(get("/api/v1/me").with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.cro").value("12345")).andExpect(jsonPath("$.croState").value("SP"));
		mvc.perform(get("/api/v1/me").with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isOk())
				.andExpect(jsonPath("$.cro").value((Object) null));
	}

	@Test
	void receptionAndAnonymousCannotWriteTheRegistry() throws Exception {
		Clinic clinic = newDentistClinic();

		mvc.perform(json(put(ROUTE), body("12345", "SP", 0)).with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(put(ROUTE), body("12345", "SP", 0))).andExpect(status().isUnauthorized());
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.users WHERE clinic_id = ? AND cro_number IS NOT NULL",
				Integer.class, clinic.id())).isZero();
	}

	@Test
	void invalidRegistryIs400WithFieldErrors() throws Exception {
		Clinic clinic = newDentistClinic();
		long version = versionOf(clinic, Role.DENTIST_ADMIN);

		for (String[] bad : new String[][] { { "12345", null }, { null, "SP" }, { "12A45", "SP" }, { "1234567890123456", "SP" },
				{ "12345", "sp" }, { "12345", "XX" } }) {
			mvc.perform(json(put(ROUTE), body(bad[0], bad[1], version)).with(as(clinic, Role.DENTIST_ADMIN)))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(1)));
		}
		mvc.perform(json(put(ROUTE), "{\"cro\":\"12345\",\"croState\":\"SP\"}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void staleVersionIs409() throws Exception {
		Clinic clinic = newDentistClinic();
		long version = versionOf(clinic, Role.DENTIST_ADMIN);
		registerCro(clinic, "12345", "SP");

		mvc.perform(json(put(ROUTE), body("999", "RJ", version)).with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		assertThat(storedCro(clinic)).isEqualTo("12345/SP");
	}

	@Test
	void updatingTheProfileNeverTouchesTheCro() throws Exception {
		Clinic clinic = newDentistClinic();
		registerCro(clinic, "12345", "SP");

		mvc.perform(json(put("/api/v1/me"), "{\"name\":\"Dra. Nova\",\"email\":\"" + userOf(clinic, Role.DENTIST_ADMIN)
				+ "@teste.local\"}").with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.cro").value("12345"));
		assertThat(storedCro(clinic)).isEqualTo("12345/SP");
	}
}
