package crm_data.clinic.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import crm_data.auth.domain.Clinic;
import crm_data.auth.domain.User;
import crm_data.auth.service.ProfileService;
import crm_data.auth.web.MeController;
import crm_data.clinic.service.ClinicService;
import crm_data.clinic.service.ClinicService.ClinicData;
import crm_data.clinic.service.UserAdminService;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP de perfil, clínica e equipe: DTOs, validação, matriz papel × rota e ausência de dados sensíveis. */
@WebMvcTest({ MeController.class, ClinicController.class, UserController.class })
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class ClinicUsersWebTest {

	private static final String STRONG = "SenhaForte2026";

	@Autowired
	MockMvc mvc;
	@MockitoBean
	ProfileService profile;
	@MockitoBean
	ClinicService clinics;
	@MockitoBean
	UserAdminService userAdmin;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private User dentist;
	private User receptionist;
	private Clinic clinic;

	private static User user(UUID id, UUID clinicId, String name, String email, Role role) {
		User user = new User(id, name, email, "$2a$12$hashSecretoQueNuncaPodeSair", role);
		ReflectionTestUtils.setField(user, "clinicId", clinicId);
		return user;
	}

	@BeforeEach
	void setUp() {
		dentist = user(userId, clinicId, "Dra. Ana", "ana@clinica.com", Role.DENTIST_ADMIN);
		receptionist = user(UUID.randomUUID(), clinicId, "Bia", "bia@clinica.com", Role.RECEPTIONIST);
		clinic = new Clinic(clinicId, "Clínica Sorriso");
		ReflectionTestUtils.setField(clinic, "version", 3L);
	}

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	// ---- /me ----

	@Test
	void meReturnsTheUserWithoutAnyPasswordField() throws Exception {
		when(profile.me()).thenReturn(dentist);

		mvc.perform(get("/api/v1/me").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(userId.toString()))
				.andExpect(jsonPath("$.name").value("Dra. Ana"))
				.andExpect(jsonPath("$.email").value("ana@clinica.com"))
				.andExpect(jsonPath("$.role").value("DENTIST_ADMIN"))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.clinicId").value(clinicId.toString()))
				.andExpect(content().string(not(containsString("password"))))
				.andExpect(content().string(not(containsString("hashSecreto"))));
	}

	@Test
	void bothRolesCanReadAndEditTheirProfile() throws Exception {
		when(profile.me()).thenReturn(receptionist);
		when(profile.updateProfile(anyString(), anyString(), any())).thenReturn(receptionist);

		for (Role role : Role.values()) {
			mvc.perform(get("/api/v1/me").with(as(role))).andExpect(status().isOk());
			mvc.perform(json(put("/api/v1/me"), "{\"name\":\"Bia\",\"email\":\"bia@clinica.com\"}").with(as(role)))
					.andExpect(status().isOk());
		}
	}

	@Test
	void updateProfileNormalizesAndIgnoresRoleClinicAndIdFromTheClient() throws Exception {
		when(profile.updateProfile(anyString(), anyString(), any())).thenReturn(dentist);
		String body = """
				{"name":"  Dra. Ana Souza ","email":"  ANA.Souza@Clinica.COM ","role":"RECEPTIONIST",
				 "clinicId":"%s","id":"%s","active":false}""".formatted(UUID.randomUUID(), UUID.randomUUID());

		mvc.perform(json(put("/api/v1/me"), body).with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk());

		verify(profile).updateProfile("Dra. Ana Souza", "ana.souza@clinica.com", null);
	}

	@Test
	void updateProfileValidatesNameAndEmail() throws Exception {
		mvc.perform(json(put("/api/v1/me"), "{\"name\":\"A\",\"email\":\"nao-e-email\"}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='email')]").isNotEmpty());
		verifyNoInteractions(profile);
	}

	@Test
	void duplicateEmailOnProfileIs409() throws Exception {
		when(profile.updateProfile(anyString(), anyString(), any())).thenThrow(new BusinessException(ProblemTypes.CONFLICT, "E-mail já cadastrado."));

		mvc.perform(json(put("/api/v1/me"), "{\"name\":\"Ana\",\"email\":\"outra@clinica.com\"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/conflict"));
	}

	@Test
	void changePasswordReturns204AndValidatesThePolicyOfTheNewPassword() throws Exception {
		mvc.perform(json(put("/api/v1/me/password"), "{\"currentPassword\":\"atual\",\"newPassword\":\"" + STRONG + "\"}")
				.with(as(Role.RECEPTIONIST)))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
		verify(profile).changePassword("atual", STRONG);

		for (String weak : new String[] { "curta1", "somenteletrasaqui", "12345678901", "a1".repeat(33), "😀".repeat(30) + "a1" }) {
			mvc.perform(json(put("/api/v1/me/password"), "{\"currentPassword\":\"atual\",\"newPassword\":\"" + weak + "\"}")
					.with(as(Role.RECEPTIONIST)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[?(@.field=='newPassword')]").isNotEmpty());
		}
	}

	@Test
	void changePasswordMapsWrongCurrentPasswordTo401AndSamePasswordTo422() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ProblemTypes.UNAUTHORIZED, "Senha atual incorreta."))
				.doThrow(new BusinessException(ProblemTypes.BUSINESS_RULE, "A nova senha deve ser diferente da atual."))
				.when(profile).changePassword(anyString(), anyString());
		String body = "{\"currentPassword\":\"atual\",\"newPassword\":\"" + STRONG + "\"}";

		mvc.perform(json(put("/api/v1/me/password"), body).with(as(Role.DENTIST_ADMIN))).andExpect(status().isUnauthorized());
		mvc.perform(json(put("/api/v1/me/password"), body).with(as(Role.DENTIST_ADMIN))).andExpect(status().isUnprocessableContent());
	}

	// ---- /clinic ----

	@Test
	void bothRolesReadTheClinicWithItsVersion() throws Exception {
		when(clinics.get()).thenReturn(clinic);

		for (Role role : Role.values()) {
			mvc.perform(get("/api/v1/clinic").with(as(role)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(clinicId.toString()))
					.andExpect(jsonPath("$.name").value("Clínica Sorriso"))
					.andExpect(jsonPath("$.document").doesNotExist())
					.andExpect(jsonPath("$.timezone").value("America/Sao_Paulo"))
					.andExpect(jsonPath("$.version").value(3));
		}
	}

	@Test
	void receptionistCannotEditTheClinic_403() throws Exception {
		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Nova\",\"version\":3}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
		verifyNoInteractions(clinics);
	}

	@Test
	void dentistEditsTheClinicNormalizingOptionalFieldsAndIgnoringIds() throws Exception {
		when(clinics.update(any(), anyLong())).thenReturn(clinic);
		String body = """
				{"name":" Clínica Sorriso ","document":"  ","phone":" 11 99999-0000 ","email":"  ","address":"Rua A, 10",
				 "version":3,"id":"%s","clinicId":"%s"}""".formatted(UUID.randomUUID(), UUID.randomUUID());

		mvc.perform(json(put("/api/v1/clinic"), body).with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk());

		ArgumentCaptor<ClinicData> data = ArgumentCaptor.forClass(ClinicData.class);
		verify(clinics).update(data.capture(), eq(3L));
		assertThat(data.getValue()).isEqualTo(new ClinicData("Clínica Sorriso", null, "11 99999-0000", null, "Rua A, 10", null));
	}

	@Test
	void editingTheClinicRequiresNameAndVersionAndValidSizes() throws Exception {
		mvc.perform(json(put("/api/v1/clinic"), "{}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='version')]").isNotEmpty());
		String tooLong = "{\"name\":\"Clínica\",\"document\":\"" + "1".repeat(21) + "\",\"phone\":\"" + "9".repeat(21)
				+ "\",\"email\":\"invalido\",\"address\":\"" + "x".repeat(256) + "\",\"version\":-1}";
		mvc.perform(json(put("/api/v1/clinic"), tooLong).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.length()").value(5));
		verifyNoInteractions(clinics);
	}

	@Test
	void dentistChangesTheTimezoneAndTheServiceReceivesIt() throws Exception {
		when(clinics.update(any(), anyLong())).thenReturn(clinic);

		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Clínica\",\"timezone\":\" America/Manaus \",\"version\":3}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk());

		ArgumentCaptor<ClinicData> data = ArgumentCaptor.forClass(ClinicData.class);
		verify(clinics).update(data.capture(), eq(3L));
		assertThat(data.getValue().timezone()).isEqualTo("America/Manaus");
	}

	@ParameterizedTest
	@ValueSource(strings = { "Mars/Phobos", "+03:00", "america/sao_paulo", "GMT+3x", "Xx" })
	void unknownTimezoneIs400OnTheTimezoneField(String timezone) throws Exception {
		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Clínica\",\"timezone\":\"" + timezone + "\",\"version\":3}")
				.with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='timezone')]").isNotEmpty());
		verifyNoInteractions(clinics);
	}

	@Test
	void receptionistCannotChangeTheTimezone_403() throws Exception {
		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Clínica\",\"timezone\":\"UTC\",\"version\":3}")
				.with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		verifyNoInteractions(clinics);
	}

	@Test
	void staleVersionFromTheServiceIs409() throws Exception {
		when(clinics.update(any(), anyLong())).thenThrow(new BusinessException(ProblemTypes.CONFLICT, "Os dados foram alterados."));

		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Clínica\",\"version\":1}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
	}

	// ---- /users: matriz papel × rota ----

	@Test
	void receptionistGets403OnEveryUsersRoute() throws Exception {
		UUID id = UUID.randomUUID();
		List<MockHttpServletRequestBuilder> routes = List.of(
				get("/api/v1/users"),
				json(post("/api/v1/users"), "{\"name\":\"Bia\",\"email\":\"b@c.com\",\"password\":\"" + STRONG + "\"}"),
				json(patch("/api/v1/users/" + id + "/active"), "{\"active\":false}"),
				json(put("/api/v1/users/" + id + "/password"), "{\"newPassword\":\"" + STRONG + "\"}"));

		for (MockHttpServletRequestBuilder route : routes) {
			mvc.perform(route.with(as(Role.RECEPTIONIST)))
					.andExpect(status().isForbidden())
					.andExpect(content().contentTypeCompatibleWith("application/problem+json"));
		}
		verifyNoInteractions(userAdmin);
	}

	@Test
	void unauthenticatedGets401OnAllRoutes() throws Exception {
		for (String path : new String[] { "/api/v1/me", "/api/v1/clinic", "/api/v1/users" }) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized());
		}
		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"X\",\"version\":0}")).andExpect(status().isUnauthorized());
	}

	// ---- /users: dentista ----

	@Test
	void listUsesDefaultsAndReturnsTheContractedPage() throws Exception {
		when(userAdmin.list(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(dentist, receptionist), PageRequest.of(0, 20), 2));

		mvc.perform(get("/api/v1/users").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].name").value("Dra. Ana"))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(1))
				.andExpect(content().string(not(containsString("password"))));
		verify(userAdmin).list(0, 20);
	}

	@Test
	void listRejectsSizeOutsideOneToFiftyAndNegativePage() throws Exception {
		for (String query : new String[] { "?size=0", "?size=51", "?size=-3", "?page=-1", "?page=100001", "?size=abc" }) {
			mvc.perform(get("/api/v1/users" + query).with(as(Role.DENTIST_ADMIN)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"));
		}
		verifyNoInteractions(userAdmin);
	}

	@Test
	void listAcceptsTheBoundaries() throws Exception {
		when(userAdmin.list(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

		mvc.perform(get("/api/v1/users?size=1&page=0").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk());
		mvc.perform(get("/api/v1/users?size=50&page=2").with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk());

		verify(userAdmin).list(0, 1);
		verify(userAdmin).list(2, 50);
	}

	@Test
	void createReturns201WithTheNewReceptionistAndIgnoresRoleAndClinicFromTheBody() throws Exception {
		when(userAdmin.createReceptionist(anyString(), anyString(), anyString())).thenReturn(receptionist);
		String body = """
				{"name":" Bia ","email":" BIA@Clinica.com ","password":"%s","role":"DENTIST_ADMIN",
				 "clinicId":"%s","id":"%s","active":false}""".formatted(STRONG, UUID.randomUUID(), UUID.randomUUID());

		mvc.perform(json(post("/api/v1/users"), body).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("RECEPTIONIST"))
				.andExpect(jsonPath("$.name").value("Bia"))
				.andExpect(content().string(not(containsString(STRONG))));

		verify(userAdmin).createReceptionist("Bia", "bia@clinica.com", STRONG);
	}

	@Test
	void createValidatesFieldsAndMapsDuplicateEmailTo409() throws Exception {
		mvc.perform(json(post("/api/v1/users"), "{\"name\":\"B\",\"email\":\"x\",\"password\":\"curta1\"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.length()").value(3));
		verifyNoInteractions(userAdmin);

		when(userAdmin.createReceptionist(anyString(), anyString(), anyString()))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "E-mail já cadastrado."));
		mvc.perform(json(post("/api/v1/users"), "{\"name\":\"Bia\",\"email\":\"bia@clinica.com\",\"password\":\"" + STRONG + "\"}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isConflict());
	}

	@Test
	void setActiveReturnsTheUserAndValidatesTheBody() throws Exception {
		UUID id = receptionist.getId();
		when(userAdmin.setActive(eq(id), anyBoolean())).thenReturn(receptionist);

		mvc.perform(json(patch("/api/v1/users/" + id + "/active"), "{\"active\":false}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()));
		verify(userAdmin).setActive(id, false);

		mvc.perform(json(patch("/api/v1/users/" + id + "/active"), "{}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='active')]").isNotEmpty());
		mvc.perform(json(patch("/api/v1/users/nao-e-uuid/active"), "{\"active\":true}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void businessRulesAndNotFoundFromTheServiceAreMappedToTheContractedStatus() throws Exception {
		UUID id = UUID.randomUUID();
		when(userAdmin.setActive(eq(id), anyBoolean()))
				.thenThrow(new BusinessException(ProblemTypes.BUSINESS_RULE, "Não é possível desativar."))
				.thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Usuário não encontrado."));

		mvc.perform(json(patch("/api/v1/users/" + id + "/active"), "{\"active\":false}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/business-rule"));
		mvc.perform(json(patch("/api/v1/users/" + id + "/active"), "{\"active\":false}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
	}

	@Test
	void resetPasswordReturns204AndEnforcesThePasswordPolicy() throws Exception {
		UUID id = receptionist.getId();

		mvc.perform(json(put("/api/v1/users/" + id + "/password"), "{\"newPassword\":\"" + STRONG + "\"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isNoContent());
		verify(userAdmin).resetPassword(id, STRONG);

		mvc.perform(json(put("/api/v1/users/" + id + "/password"), "{\"newPassword\":\"fraca\"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void usersCannotBeDeleted_onlyDeactivated() throws Exception {
		// fora do escopo (PRD §4): não existe rota de exclusão, nem para o dentista
		mvc.perform(delete("/api/v1/users/" + UUID.randomUUID()).with(as(Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/users").with(as(Role.DENTIST_ADMIN))).andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(userAdmin);
	}

	@Test
	void sensitiveRequestsNeverPrintPasswordsInToString() {
		assertThat(new UserRequests.CreateUserRequest("Bia", "bia@clinica.com", STRONG).toString()).doesNotContain(STRONG);
		assertThat(new UserRequests.ResetPasswordRequest(STRONG).toString()).doesNotContain(STRONG);
		assertThat(new crm_data.auth.web.ProfileRequests.ChangePasswordRequest("atualSecreta", STRONG).toString())
				.doesNotContain(STRONG).doesNotContain("atualSecreta");
	}
}
