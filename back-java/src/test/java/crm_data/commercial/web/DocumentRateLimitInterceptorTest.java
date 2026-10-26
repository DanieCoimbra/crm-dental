package crm_data.commercial.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import crm_data.commercial.service.DocumentRateLimiter;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class DocumentRateLimitInterceptorTest {

	private final DocumentRateLimiter limiter = mock(DocumentRateLimiter.class);
	private final DocumentRateLimitInterceptor interceptor = new DocumentRateLimitInterceptor(limiter);
	private final UUID user = UUID.randomUUID();
	private final String id = UUID.randomUUID().toString();

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	private boolean call(String method, String uri) {
		return interceptor.preHandle(new MockHttpServletRequest(method, uri), new MockHttpServletResponse(), new Object());
	}

	@ParameterizedTest
	@ValueSource(strings = { "send", "documents" })
	void countsOnlyThePostOfSendAndDocuments(String action) {
		TenantContext.set(UUID.randomUUID(), user, Role.DENTIST_ADMIN);

		assertThat(call("POST", "/api/v1/budgets/" + id + "/" + action)).isTrue();

		verify(limiter).check(user);
	}

	@Test
	void doesNotCountReadsOrOtherBudgetRoutes() {
		TenantContext.set(UUID.randomUUID(), user, Role.DENTIST_ADMIN);

		call("GET", "/api/v1/budgets/" + id + "/documents");
		call("GET", "/api/v1/budgets/" + id + "/documents/" + UUID.randomUUID() + "/download-url");
		call("POST", "/api/v1/budgets");
		call("POST", "/api/v1/budgets/" + id + "/approve");
		call("PUT", "/api/v1/budgets/" + id);
		call("POST", "/api/v1/budgets/" + id + "/documents/extra");

		verify(limiter, never()).check(any());
	}

	@Test
	void withoutATenantNothingIsCounted() {
		call("POST", "/api/v1/budgets/" + id + "/send");

		verify(limiter, never()).check(any());
	}
}
