package crm_data.record.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.record.domain.AuditAction;
import crm_data.record.domain.AuditLog;
import crm_data.record.service.AuditQueryService;
import crm_data.record.service.AuditQueryService.Entry;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP da trilha de auditoria: só D, somente leitura, filtros validados e sem valores de colunas. */
@WebMvcTest(AuditLogController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class AuditLogControllerWebTest {

	@Autowired
	MockMvc mvc;
	@MockitoBean
	AuditQueryService audit;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private Entry entry(long id, String name) {
		AuditLog log = (AuditLog) org.springframework.objenesis.ObjenesisHelper.newInstance(AuditLog.class);
		ReflectionTestUtils.setField(log, "id", id);
		ReflectionTestUtils.setField(log, "clinicId", clinicId);
		ReflectionTestUtils.setField(log, "userId", userId);
		ReflectionTestUtils.setField(log, "tableName", "clinical_notes");
		ReflectionTestUtils.setField(log, "recordId", UUID.fromString("11111111-1111-1111-1111-111111111111"));
		ReflectionTestUtils.setField(log, "action", AuditAction.UPDATE);
		ReflectionTestUtils.setField(log, "occurredAt", Instant.parse("2026-10-22T12:00:00Z"));
		return new Entry(log, name);
	}

	@Test
	void receptionistGets403AndUnauthenticatedGets401WithoutTouchingTheService() throws Exception {
		mvc.perform(get("/api/v1/audit-log").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
		mvc.perform(get("/api/v1/audit-log")).andExpect(status().isUnauthorized());

		verifyNoInteractions(audit);
	}

	@Test
	void dentistListsEntriesWithTheContractFieldsAndNoStore() throws Exception {
		when(audit.list(any(), any(), anyInt(), anyInt()))
				.thenReturn(new PageImpl<>(List.of(entry(42, "Dra. Ana"), entry(41, null)), PageRequest.of(1, 5), 12));

		mvc.perform(get("/api/v1/audit-log").param("tableName", "clinical_notes")
				.param("recordId", "11111111-1111-1111-1111-111111111111").param("page", "1").param("size", "5")
				.with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(5))
				.andExpect(jsonPath("$.totalElements").value(12))
				.andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.content[0].id").value(42))
				.andExpect(jsonPath("$.content[0].userId").value(userId.toString()))
				.andExpect(jsonPath("$.content[0].userName").value("Dra. Ana"))
				.andExpect(jsonPath("$.content[0].tableName").value("clinical_notes"))
				.andExpect(jsonPath("$.content[0].recordId").value("11111111-1111-1111-1111-111111111111"))
				.andExpect(jsonPath("$.content[0].action").value("UPDATE"))
				.andExpect(jsonPath("$.content[0].occurredAt").value("2026-10-22T12:00:00Z"))
				.andExpect(jsonPath("$.content[1].userName").doesNotExist())
				.andExpect(jsonPath("$.content[0].length()").value(7))
				.andExpect(content().string(not(containsString("clinicId"))));
		verify(audit).list("clinical_notes", UUID.fromString("11111111-1111-1111-1111-111111111111"), 1, 5);
	}

	@Test
	void filtersAreOptionalAndPagingHasDefaults() throws Exception {
		when(audit.list(any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));

		mvc.perform(get("/api/v1/audit-log").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk());

		verify(audit).list(isNull(), isNull(), eq(0), eq(20));
	}

	@Test
	void invalidFiltersAndLimitsAre400() throws Exception {
		when(audit.list(any(), any(), anyInt(), anyInt()))
				.thenThrow(new BusinessException(ProblemTypes.VALIDATION, "O filtro recordId exige tableName."));

		mvc.perform(get("/api/v1/audit-log").param("recordId", UUID.randomUUID().toString()).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"));
		mvc.perform(get("/api/v1/audit-log").param("recordId", "nao-e-uuid").param("tableName", "patients").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/audit-log").param("size", "51").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/audit-log").param("size", "0").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/audit-log").param("page", "-1").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void thereIsNoWriteRouteOnTheTrail() throws Exception {
		for (var request : List.of(post("/api/v1/audit-log"), put("/api/v1/audit-log"), delete("/api/v1/audit-log"),
				delete("/api/v1/audit-log/1"))) {
			int status = mvc.perform(request.with(as(Role.DENTIST_ADMIN)).contentType("application/json").content("{}"))
					.andReturn().getResponse().getStatus();
			assertThat(status).isIn(404, 405);
		}
	}

	@Test
	void responseToStringNeverPrintsTheUserName() {
		assertThat(AuditEntryResponse.from(entry(1, "Dra. Ana")).toString()).doesNotContain("Ana");
	}
}
