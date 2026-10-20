package crm_data.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import crm_data.support.PlatformWebTest;

@PlatformWebTest
class GlobalExceptionHandlerTest {

	private static final String PROBLEM = "application/problem+json";

	@Autowired
	MockMvc mvc;

	@ParameterizedTest
	@CsvSource({ "NOT_FOUND,404,not-found", "CONFLICT,409,conflict", "BUSINESS_RULE,422,business-rule",
			"FORBIDDEN,403,forbidden", "UNAUTHORIZED,401,unauthorized", "UNAVAILABLE,503,unavailable" })
	void businessExceptionUsesTypeStatusAndSlug(String type, int status, String slug) throws Exception {
		mvc.perform(get("/probe/business/" + type).with(user("u")))
				.andExpect(status().is(status))
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/" + slug))
				.andExpect(jsonPath("$.status").value(status))
				.andExpect(jsonPath("$.detail").value("mensagem de negócio"))
				.andExpect(jsonPath("$.instance").value("/probe/business/" + type));
	}

	@Test
	void rateLimitedReturns429WithRetryAfter() throws Exception {
		mvc.perform(get("/probe/rate-limited").with(user("u")))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "30"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/rate-limited"));
	}

	@Test
	void beanValidationListsEveryInvalidField() throws Exception {
		mvc.perform(post("/probe/validate").with(user("u")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"\",\"password\":\"curta\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"))
				.andExpect(jsonPath("$.errors.length()").value(2))
				.andExpect(jsonPath("$.errors[?(@.field=='name')].message").value("obrigatório"))
				.andExpect(jsonPath("$.errors[?(@.field=='password')].message").value("mínimo de 10 caracteres"));
	}

	@Test
	void malformedJsonReturns400ValidationWithoutParserDetails() throws Exception {
		mvc.perform(post("/probe/validate").with(user("u")).contentType(MediaType.APPLICATION_JSON).content("{nome:"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"))
				.andExpect(jsonPath("$.errors").doesNotExist())
				.andExpect(content().string(not(containsString("Unexpected"))))
				.andExpect(content().string(not(containsString("jackson"))));
	}

	@Test
	void typeMismatchReturns400ValidationWithField() throws Exception {
		mvc.perform(get("/probe/number").param("n", "abc").with(user("u")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"))
				.andExpect(jsonPath("$.errors[0].field").value("n"));
	}

	@Test
	void unknownRouteReturns404Problem() throws Exception {
		mvc.perform(get("/nao/existe").with(user("u")))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/not-found"))
				.andExpect(jsonPath("$.instance").value("/nao/existe"));
	}

	@Test
	void unsupportedMethodReturns405ProblemKeepingAllowHeader() throws Exception {
		mvc.perform(post("/probe/ok").with(user("u")))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().exists("Allow"))
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.status").value(405));
	}

	@Test
	void unexpectedExceptionReturns500WithoutInternalDetails() throws Exception {
		MvcResult result = mvc.perform(get("/probe/boom").with(user("u")))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/internal"))
				.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("segredo interno", "host-interno", "IllegalStateException", "at crm_data");
	}

	@Test
	void cryptoFailureReturns500WithoutDetails() throws Exception {
		mvc.perform(get("/probe/crypto").with(user("u")))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/internal"))
				.andExpect(content().string(not(containsString("adulterado"))));
	}

	@Test
	void methodSecurityDenialReturns403NotInternalError() throws Exception {
		mvc.perform(get("/probe/admin").with(user("u").roles("USER")))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
	}

	@Test
	void optimisticLockReturns409Conflict() throws Exception {
		MvcResult result = mvc.perform(get("/probe/locking").with(user("u")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/conflict"))
				.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("Foo");
	}

	@Test
	void databaseFailureReturns503WithoutInternalDetails() throws Exception {
		MvcResult result = mvc.perform(get("/probe/db-down").with(user("u")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unavailable"))
				.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("host-interno", "5432");
	}

	@Test
	void errorsAreProblemJsonEvenWhenClientAcceptsOnlyJson() throws Exception {
		mvc.perform(get("/probe/business/CONFLICT").accept(MediaType.APPLICATION_JSON).with(user("u")))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM));
	}

	@Test
	void unauthenticatedRequestReturns401Problem() throws Exception {
		mvc.perform(get("/probe/ok"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unauthorized"))
				.andExpect(jsonPath("$.instance").value("/probe/ok"));
	}
}
