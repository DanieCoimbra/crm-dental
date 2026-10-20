package crm_data;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import crm_data.support.DatabaseContainer;

/**
 * Sobe o contexto completo com o perfil {@code prod} de verdade (incluindo o fail-fast de variáveis).
 * As variáveis de ambiente de produção são simuladas por propriedades; o datasource real aponta para o
 * Testcontainers ({@code DatabaseContainer}), que prevalece sobre {@code DB_URL}.
 */
@SpringBootTest(properties = {
		"DB_URL=jdbc:postgresql://db.example:6543/postgres?sslmode=require&prepareThreshold=0",
		"DB_APP_USER=crm_app.proj", "DB_APP_PASSWORD=nao-usada-no-teste",
		"CRM_JWT_SECRET=segredo-jwt-de-teste-com-mais-de-32-bytes", "CRM_CORS_ORIGINS=https://app.example" })
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProdProfileIT {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	MockMvc mvc;

	@Test
	void devOnlyEndpointsDoNotExist() throws Exception {
		for (String path : new String[] { "/v3/api-docs", "/swagger-ui/index.html", "/actuator/env",
				"/actuator/beans", "/actuator/info", "/actuator/metrics" }) {
			mvc.perform(get(path).with(user("u"))).andExpect(status().isNotFound());
		}
	}

	@Test
	void devOnlyEndpointsAreHiddenFromAnonymousCallers() throws Exception {
		for (String path : new String[] { "/v3/api-docs", "/actuator/env" }) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void healthIsStillPublic() throws Exception {
		mvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void sendsHstsOverHttps() throws Exception {
		mvc.perform(get("/actuator/health/liveness").secure(true))
				.andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
	}

	@Test
	void corsAllowsOnlyConfiguredOrigin() throws Exception {
		mvc.perform(get("/actuator/health/liveness").header("Origin", "https://app.example"))
				.andExpect(header().string("Access-Control-Allow-Origin", "https://app.example"));
		mvc.perform(get("/actuator/health/liveness").header("Origin", "https://evil.example"))
				.andExpect(status().isForbidden());
	}
}
