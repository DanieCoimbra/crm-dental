package crm_data.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import crm_data.support.PlatformWebTest;

@PlatformWebTest
@ExtendWith(OutputCaptureExtension.class)
class PlatformFiltersTest {

	private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final String ALLOWED = "https://app.test.local";

	@Autowired
	MockMvc mvc;

	// ---- X-Request-Id ----

	@Test
	void generatesRequestIdWhenAbsent() throws Exception {
		mvc.perform(get("/probe/ok").with(user("u")))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
	}

	@Test
	void propagatesValidRequestId() throws Exception {
		mvc.perform(get("/probe/ok").header("X-Request-Id", "abc-123-DEF").with(user("u")))
				.andExpect(header().string("X-Request-Id", "abc-123-DEF"));
	}

	@Test
	void replacesInvalidOrTooLongRequestId() throws Exception {
		mvc.perform(get("/probe/ok").header("X-Request-Id", "id com espaço;<script>").with(user("u")))
				.andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
		mvc.perform(get("/probe/ok").header("X-Request-Id", "a".repeat(65)).with(user("u")))
				.andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
	}

	@Test
	void requestIdAppearsInLogLinesAndIsClearedAfterTheRequest(CapturedOutput output) throws Exception {
		mvc.perform(get("/probe/ok").header("X-Request-Id", "trace-abc-1").with(user("u"))).andExpect(status().isOk());

		assertThat(output.getAll()).containsPattern("\\[trace-abc-1\\].*probe-ok-executado");
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void requestIdIsPresentEvenOnSecurityAndRoutingErrors() throws Exception {
		mvc.perform(get("/probe/ok")).andExpect(status().isUnauthorized())
				.andExpect(header().exists("X-Request-Id"));
		mvc.perform(get("/nao/existe").with(user("u"))).andExpect(status().isNotFound())
				.andExpect(header().exists("X-Request-Id"));
	}

	// ---- CORS ----

	@Test
	void allowedOriginReceivesCorsHeaders() throws Exception {
		mvc.perform(get("/probe/ok").header("Origin", ALLOWED).with(user("u")))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
				.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
				.andExpect(header().string("Access-Control-Expose-Headers", "X-Request-Id, Retry-After"));
	}

	@Test
	void preflightFromAllowedOriginIsAnswered() throws Exception {
		mvc.perform(options("/probe/ok").header("Origin", ALLOWED)
				.header("Access-Control-Request-Method", "PUT")
				.header("Access-Control-Request-Headers", "authorization,content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
				.andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS"));
	}

	@Test
	void disallowedOriginGetsNoCorsHeadersAndAProblem() throws Exception {
		MvcResult result = mvc.perform(get("/probe/ok").header("Origin", "https://evil.example").with(user("u")))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"))
				.andExpect(header().exists("X-Request-Id"))
				.andReturn();
		assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
	}

	@Test
	void disallowedPreflightGetsNoCorsHeaders() throws Exception {
		MvcResult result = mvc.perform(options("/probe/ok").header("Origin", "https://evil.example")
				.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isForbidden())
				.andReturn();
		assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
		assertThat(result.getResponse().getHeader("Access-Control-Allow-Methods")).isNull();
	}

	@Test
	void unauthenticatedErrorFromAllowedOriginStillCarriesCorsHeaders() throws Exception {
		mvc.perform(get("/probe/ok").header("Origin", ALLOWED))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
	}

	// ---- Cabeçalhos de segurança ----

	@Test
	void securityHeadersAreApplied() throws Exception {
		mvc.perform(get("/probe/ok").with(user("u")))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
	}

	@Test
	void authRoutesAreNeverCached() throws Exception {
		// rota pública de auth ainda inexistente nesta feature: o 404 também precisa trazer no-store
		mvc.perform(get("/api/v1/auth/login"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
	}

	@Test
	void hstsIsNotSentOutsideProd() throws Exception {
		mvc.perform(get("/probe/ok").secure(true).with(user("u")))
				.andExpect(header().doesNotExist("Strict-Transport-Security"));
	}
}
