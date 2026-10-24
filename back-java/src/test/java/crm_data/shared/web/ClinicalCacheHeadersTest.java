package crm_data.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ClinicalCacheHeadersTest {

	private static String cacheControlFor(String path) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new ClinicalCacheHeaders().doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
		return response.getHeader("Cache-Control");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/patients", "/api/v1/patients/abc", "/api/v1/patients/abc/files/1/download-url",
			"/api/v1/audit-log", "/api/v1/audit-log/x", "/api/v1/patients/abc/appointments", "/api/v1/appointments",
			"/api/v1/appointments/abc/confirm", "/api/v1/business-hours", "/api/v1/holidays", "/api/v1/clinic-holidays",
			"/api/v1/clinic-holidays/abc" })
	void clinicalRoutesAreNoStore(String path) throws Exception {
		assertThat(cacheControlFor(path)).isEqualTo("no-store");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/auth/login", "/api/v1/patientsX", "/api/v1/clinic", "/api/v1/appointmentsX", "/probe/ok" })
	void otherRoutesAreUntouched(String path) throws Exception {
		assertThat(cacheControlFor(path)).isNull();
	}
}
