package crm_data.patient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.patient.domain.Patient;
import crm_data.patient.domain.Sex;
import crm_data.patient.service.PatientAnonymizationService;
import crm_data.patient.service.PatientAnonymizationService.Result;
import crm_data.patient.service.PatientAnonymizationService.StorageCleanup;
import crm_data.patient.service.PatientService;
import crm_data.patient.service.PatientService.PatientDetails;
import crm_data.patient.service.PatientService.Status;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP de pacientes: DTOs, normalização, validação, matriz papel × rota e ausência de campos internos. */
@WebMvcTest(PatientController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class PatientControllerWebTest {

	private static final String VALID = """
			{"name":"  Maria Souza ","cpf":"529.982.247-25","birthDate":"1988-04-12","sex":"FEMALE",
			 "profession":"Professora","phone":"(11) 98765-4321","email":" Maria@Exemplo.COM ","address":"Rua A, 10",
			 "insuranceName":"Convênio X","insuranceNumber":"123456","acquaintanceName":"João Souza",
			 "acquaintancePhone":"11 91234-5678"}""";

	@Autowired
	MockMvc mvc;
	@MockitoBean
	PatientService patients;
	@MockitoBean
	PatientAnonymizationService anonymization;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private Patient patient() {
		Patient patient = new Patient(UUID.randomUUID(), userId);
		ReflectionTestUtils.setField(patient, "clinicId", clinicId);
		patient.setName("Maria Souza");
		patient.setCpf("52998224725");
		patient.setBirthDate(LocalDate.of(1988, 4, 12));
		patient.setSex(Sex.FEMALE);
		patient.setPhone("11987654321");
		ReflectionTestUtils.setField(patient, "createdAt", Instant.parse("2026-10-22T12:00:00Z"));
		ReflectionTestUtils.setField(patient, "updatedAt", Instant.parse("2026-10-22T12:00:00Z"));
		ReflectionTestUtils.setField(patient, "version", 0L);
		return patient;
	}

	// ---- POST ----

	@Test
	void bothRolesCreateAndTheRequestIsNormalizedBeforeReachingTheService() throws Exception {
		when(patients.create(any())).thenReturn(patient());

		for (Role role : Role.values()) {
			mvc.perform(json(post("/api/v1/patients"), VALID).with(as(role)))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.name").value("Maria Souza"))
					.andExpect(jsonPath("$.cpf").value("52998224725"));
		}

		ArgumentCaptor<PatientDetails> captor = ArgumentCaptor.forClass(PatientDetails.class);
		verify(patients, org.mockito.Mockito.times(2)).create(captor.capture());
		PatientDetails details = captor.getValue();
		assertThat(details.name()).isEqualTo("Maria Souza");
		assertThat(details.cpf()).isEqualTo("52998224725");
		assertThat(details.phone()).isEqualTo("11987654321");
		assertThat(details.acquaintancePhone()).isEqualTo("11912345678");
		assertThat(details.email()).isEqualTo("maria@exemplo.com");
		assertThat(details.sex()).isEqualTo(Sex.FEMALE);
	}

	@Test
	void minimalBodyDefaultsSexAndTurnsBlankOptionalsIntoNull() throws Exception {
		when(patients.create(any())).thenReturn(patient());
		String body = """
				{"name":"Maria","birthDate":"1988-04-12","phone":"11987654321","cpf":"  ","email":"","profession":" "}""";

		mvc.perform(json(post("/api/v1/patients"), body).with(as(Role.RECEPTIONIST))).andExpect(status().isCreated());

		ArgumentCaptor<PatientDetails> captor = ArgumentCaptor.forClass(PatientDetails.class);
		verify(patients).create(captor.capture());
		assertThat(captor.getValue().sex()).isEqualTo(Sex.NOT_INFORMED);
		assertThat(captor.getValue().cpf()).isNull();
		assertThat(captor.getValue().email()).isNull();
		assertThat(captor.getValue().profession()).isNull();
	}

	@Test
	void unauthenticatedRequestsAreRejectedBeforeAnyWork() throws Exception {
		mvc.perform(json(post("/api/v1/patients"), VALID)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/patients")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/patients/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
		mvc.perform(json(put("/api/v1/patients/" + UUID.randomUUID()), VALID)).andExpect(status().isUnauthorized());
		mvc.perform(json(patch("/api/v1/patients/" + UUID.randomUUID() + "/active"), "{\"active\":false}"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(patients);
	}

	@Test
	void listsEveryInvalidFieldAtOnce() throws Exception {
		String body = """
				{"name":"A","cpf":"52998224724","birthDate":"2999-01-01","phone":"123","email":"nao-e-email",
				 "insuranceNumber":"99","acquaintanceName":"João"}""";

		mvc.perform(json(post("/api/v1/patients"), body).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"))
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='cpf')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='birthDate')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='phone')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='email')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='insuranceNumber')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='acquaintancePhone')]").exists());
		verifyNoInteractions(patients);
	}

	@Test
	void requiredFieldsAreEnforced() throws Exception {
		mvc.perform(json(post("/api/v1/patients"), "{}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='birthDate')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='phone')]").exists());
	}

	@Test
	void birthDateBoundaries() throws Exception {
		when(patients.create(any())).thenReturn(patient());
		String template = "{\"name\":\"Maria\",\"birthDate\":\"%s\",\"phone\":\"11987654321\"}";

		mvc.perform(json(post("/api/v1/patients"), template.formatted("1900-01-01")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());
		mvc.perform(json(post("/api/v1/patients"), template.formatted("1899-12-31")).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("birthDate"));
		mvc.perform(json(post("/api/v1/patients"), template.formatted(LocalDate.now().plusDays(2))).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("birthDate"));
	}

	@Test
	void unknownSexIsRejected() throws Exception {
		String body = "{\"name\":\"Maria\",\"birthDate\":\"1988-04-12\",\"phone\":\"11987654321\",\"sex\":\"X\"}";

		mvc.perform(json(post("/api/v1/patients"), body).with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void clientCannotInjectIdClinicCreatorActiveOrAnonymization() throws Exception {
		when(patients.create(any())).thenReturn(patient());
		String body = """
				{"name":"Maria","birthDate":"1988-04-12","phone":"11987654321","id":"%s","clinicId":"%s",
				 "createdBy":"%s","active":false,"anonymizedAt":"2020-01-01T00:00:00Z","role":"DENTIST_ADMIN"}"""
				.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		mvc.perform(json(post("/api/v1/patients"), body).with(as(Role.RECEPTIONIST))).andExpect(status().isCreated());

		ArgumentCaptor<PatientDetails> captor = ArgumentCaptor.forClass(PatientDetails.class);
		verify(patients).create(captor.capture());
		assertThat(captor.getValue().toString()).isEqualTo("PatientDetails[]");
	}

	@Test
	void responseNeverExposesClinicCreatorOrClinicalRecord() throws Exception {
		when(patients.create(any())).thenReturn(patient());

		mvc.perform(json(post("/api/v1/patients"), VALID).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(0))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(content().string(not(containsString("clinicId"))))
				.andExpect(content().string(not(containsString("createdBy"))))
				.andExpect(content().string(not(containsString("medical"))))
				.andExpect(header().string("Cache-Control", not(containsString("max-age=3600"))));
	}

	// ---- GET ----

	@Test
	void searchPassesFiltersAndPagingAndReturnsOnlySummaryFields() throws Exception {
		when(patients.search(any(), any(), anyInt(), anyInt()))
				.thenReturn(new PageImpl<>(List.of(patient()), PageRequest.of(1, 5), 11));

		for (Role role : Role.values()) {
			mvc.perform(get("/api/v1/patients").param("q", "mar").param("status", "all").param("page", "1").param("size", "5")
					.with(as(role)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.page").value(1))
					.andExpect(jsonPath("$.size").value(5))
					.andExpect(jsonPath("$.totalElements").value(11))
					.andExpect(jsonPath("$.totalPages").value(3))
					.andExpect(jsonPath("$.content[0].name").value("Maria Souza"))
					.andExpect(jsonPath("$.content[0].cpf").doesNotExist())
					.andExpect(jsonPath("$.content[0].email").doesNotExist());
		}
		verify(patients, org.mockito.Mockito.times(2)).search("mar", Status.ALL, 1, 5);
	}

	@Test
	void searchDefaultsToActiveFirstPageOfTwenty() throws Exception {
		when(patients.search(any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));

		mvc.perform(get("/api/v1/patients").with(as(Role.RECEPTIONIST))).andExpect(status().isOk());

		verify(patients).search(isNull(), eq(Status.ACTIVE), eq(0), eq(20));
	}

	@Test
	void invalidSearchParametersAreValidationErrors() throws Exception {
		mvc.perform(get("/api/v1/patients").param("size", "51").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/patients").param("size", "0").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/patients").param("page", "-1").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/patients").param("status", "todos").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"));
		verifyNoInteractions(patients);
	}

	@Test
	void shortSearchTermFromTheServiceIsA400Problem() throws Exception {
		when(patients.search(any(), any(), anyInt(), anyInt()))
				.thenThrow(new BusinessException(ProblemTypes.VALIDATION, "A busca exige ao menos 2 caracteres."));

		mvc.perform(get("/api/v1/patients").param("q", "a").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"));
	}

	@Test
	void getByIdReturnsTheFichaForBothRolesAndRejectsMalformedIds() throws Exception {
		Patient patient = patient();
		when(patients.get(patient.getId())).thenReturn(patient);

		for (Role role : Role.values()) {
			mvc.perform(get("/api/v1/patients/" + patient.getId()).with(as(role)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(patient.getId().toString()));
		}
		mvc.perform(get("/api/v1/patients/nao-e-uuid").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void unknownPatientIs404() throws Exception {
		UUID id = UUID.randomUUID();
		when(patients.get(id)).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado."));

		mvc.perform(get("/api/v1/patients/" + id).with(as(Role.RECEPTIONIST)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/not-found"));
	}

	// ---- PUT ----

	@Test
	void updateRequiresVersionAndForwardsItWithTheNormalizedFields() throws Exception {
		Patient patient = patient();
		when(patients.update(any(), any(), anyLong())).thenReturn(patient);
		String withVersion = VALID.substring(0, VALID.lastIndexOf('}')) + ",\"version\":4}";

		for (Role role : Role.values()) {
			mvc.perform(json(put("/api/v1/patients/" + patient.getId()), withVersion).with(as(role))).andExpect(status().isOk());
		}
		verify(patients, org.mockito.Mockito.times(2)).update(eq(patient.getId()), any(PatientDetails.class), eq(4L));

		mvc.perform(json(put("/api/v1/patients/" + patient.getId()), VALID).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='version')]").exists());
	}

	@Test
	void staleVersionConflictFromTheServiceIs409() throws Exception {
		when(patients.update(any(), any(), anyLong()))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "O recurso foi alterado por outra operação."));
		String body = VALID.substring(0, VALID.lastIndexOf('}')) + ",\"version\":0}";

		mvc.perform(json(put("/api/v1/patients/" + UUID.randomUUID()), body).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/conflict"));
	}

	// ---- PATCH /active ----

	@Test
	void onlyTheDentistChangesActiveAndTheReceptionistNeverReachesTheService() throws Exception {
		UUID id = UUID.randomUUID();
		Patient patient = patient();
		when(patients.setActive(eq(id), anyBoolean())).thenReturn(patient);

		mvc.perform(json(patch("/api/v1/patients/" + id + "/active"), "{\"active\":false}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
		verifyNoInteractions(patients);

		mvc.perform(json(patch("/api/v1/patients/" + id + "/active"), "{\"active\":false}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		verify(patients).setActive(id, false);
	}

	@Test
	void patchRequiresTheActiveFlag() throws Exception {
		mvc.perform(json(patch("/api/v1/patients/" + UUID.randomUUID() + "/active"), "{}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='active')]").exists());
	}

	@Test
	void thereIsNoDeleteRoute() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/patients/" + UUID.randomUUID())
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isMethodNotAllowed());
	}

	// ---- POST /anonymize ----

	@Test
	void onlyTheDentistAnonymizesAndTheReceptionistNeverReachesTheService() throws Exception {
		UUID id = UUID.randomUUID();
		when(anonymization.anonymize(id)).thenReturn(new Result(Instant.parse("2046-10-22T13:00:00Z"), StorageCleanup.PENDING));

		mvc.perform(post("/api/v1/patients/" + id + "/anonymize").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
		mvc.perform(post("/api/v1/patients/" + id + "/anonymize")).andExpect(status().isUnauthorized());
		verifyNoInteractions(anonymization);

		mvc.perform(post("/api/v1/patients/" + id + "/anonymize").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.anonymizedAt").value("2046-10-22T13:00:00Z"))
				.andExpect(jsonPath("$.storageCleanup").value("PENDING"))
				.andExpect(jsonPath("$.length()").value(2));
		verify(anonymization).anonymize(id);
	}

	@Test
	void anonymizationErrorsKeepTheirStatusAndMalformedIdsAre400() throws Exception {
		UUID id = UUID.randomUUID();
		when(anonymization.anonymize(id))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "Retenção legal do prontuário vigente. Liberado em 2046-11-30."))
				.thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado."));

		mvc.perform(post("/api/v1/patients/" + id + "/anonymize").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Retenção legal do prontuário vigente. Liberado em 2046-11-30."));
		mvc.perform(post("/api/v1/patients/" + id + "/anonymize").with(as(Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/patients/xyz/anonymize").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}
}
