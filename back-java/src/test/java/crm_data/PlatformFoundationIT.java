package crm_data;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import crm_data.support.DatabaseContainer;

/**
 * Contexto completo contra o PostgreSQL real da função banco (Testcontainers, scripts de {@code ../database},
 * papel {@code crm_app}). Prova que a aplicação sobe com {@code ddl-auto=validate} e que os endpoints
 * operacionais respondem como o contrato da F01.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformFoundationIT {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	MockMvc mvc;

	@Test
	void livenessIsPublicAndUp() throws Exception {
		mvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(header().exists("X-Request-Id"));
	}

	@Test
	void aggregateHealthPathUsedByRenderIsPublic() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void readinessIsPublicAndUp() throws Exception {
		mvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	/** O readiness é público e sem rate limit: se incluísse o banco, cada chamada seguraria uma conexão do pool (auditoria 2026-10-27). */
	@Test
	void publicReadinessDoesNotQueryTheDatabase(@Autowired Environment environment) {
		assertThat(environment.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState");
	}

	@Test
	void healthDoesNotExposeComponentDetails() throws Exception {
		mvc.perform(get("/actuator/health/readiness"))
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	void otherActuatorEndpointsAreNotExposed() throws Exception {
		mvc.perform(get("/actuator/env").with(user("u"))).andExpect(status().isNotFound());
		mvc.perform(get("/actuator/beans").with(user("u"))).andExpect(status().isNotFound());
		mvc.perform(get("/actuator/info").with(user("u"))).andExpect(status().isNotFound());
	}

	@Test
	void openApiIsNotAvailableOutsideDev() throws Exception {
		mvc.perform(get("/v3/api-docs").with(user("u"))).andExpect(status().isNotFound());
		mvc.perform(get("/swagger-ui/index.html").with(user("u"))).andExpect(status().isNotFound());
	}

	@Test
	void protectedRoutesRequireAuthentication() throws Exception {
		mvc.perform(get("/api/v1/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unauthorized"));
	}

	@Test
	void unknownRouteReturnsNotFoundProblemWhenAuthenticated() throws Exception {
		mvc.perform(get("/api/v1/nao-existe").with(user("u")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/not-found"));
	}
}
