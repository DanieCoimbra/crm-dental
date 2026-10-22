package crm_data.record.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.record.domain.ClinicalNote;
import crm_data.record.domain.MedicalHistory;
import crm_data.record.service.ClinicalNoteService;
import crm_data.record.service.MedicalHistoryService;
import crm_data.record.service.MedicalHistoryService.Saved;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP do prontuário: matriz papel × rota, validação, status 201/200 e ausência de campos internos. */
@WebMvcTest({ MedicalHistoryController.class, ClinicalNoteController.class })
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class RecordControllersWebTest {

	private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

	@Autowired
	MockMvc mvc;
	@MockitoBean
	MedicalHistoryService histories;
	@MockitoBean
	ClinicalNoteService notes;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();
	private final UUID noteId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private MedicalHistory history() {
		MedicalHistory history = new MedicalHistory(UUID.randomUUID(), patientId, "Alergia a penicilina", userId);
		ReflectionTestUtils.setField(history, "clinicId", clinicId);
		ReflectionTestUtils.setField(history, "createdAt", NOW);
		ReflectionTestUtils.setField(history, "updatedAt", NOW);
		ReflectionTestUtils.setField(history, "version", 0L);
		return history;
	}

	private ClinicalNote note() {
		ClinicalNote note = new ClinicalNote(noteId, patientId, NOW, "Restauração do 36", userId);
		ReflectionTestUtils.setField(note, "clinicId", clinicId);
		ReflectionTestUtils.setField(note, "createdAt", NOW);
		ReflectionTestUtils.setField(note, "updatedAt", NOW);
		ReflectionTestUtils.setField(note, "version", 0L);
		return note;
	}

	private String historyPath() {
		return "/api/v1/patients/" + patientId + "/medical-history";
	}

	private String notesPath() {
		return "/api/v1/patients/" + patientId + "/notes";
	}

	// ---- matriz de papéis ----

	@Test
	void receptionistGets403OnEveryRouteAndNoServiceIsTouched() throws Exception {
		RequestPostProcessor r = as(Role.RECEPTIONIST);
		String body = "{\"content\":\"x\",\"notedAt\":\"2026-10-22T12:00:00Z\",\"version\":0}";

		mvc.perform(get(historyPath()).with(r)).andExpect(status().isForbidden());
		mvc.perform(json(put(historyPath()), body).with(r)).andExpect(status().isForbidden());
		mvc.perform(json(post(notesPath()), body).with(r)).andExpect(status().isForbidden());
		mvc.perform(get(notesPath()).with(r)).andExpect(status().isForbidden());
		mvc.perform(json(put(notesPath() + "/" + noteId), body).with(r)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
		verifyNoInteractions(histories, notes);
	}

	@Test
	void unauthenticatedRequestsGet401() throws Exception {
		mvc.perform(get(historyPath())).andExpect(status().isUnauthorized());
		mvc.perform(get(notesPath())).andExpect(status().isUnauthorized());
		mvc.perform(json(post(notesPath()), "{\"content\":\"x\"}")).andExpect(status().isUnauthorized());
		verifyNoInteractions(histories, notes);
	}

	// ---- histórico ----

	@Test
	void historyPutIs201WhenCreatedAnd200WhenUpdated() throws Exception {
		when(histories.save(eq(patientId), any(), any())).thenReturn(new Saved(history(), true), new Saved(history(), false));

		mvc.perform(json(put(historyPath()), "{\"content\":\"Alergia a penicilina\"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.content").value("Alergia a penicilina"))
				.andExpect(jsonPath("$.patientId").value(patientId.toString()))
				.andExpect(jsonPath("$.version").value(0))
				.andExpect(content().string(not(containsString("clinicId"))))
				.andExpect(content().string(not(containsString("createdBy"))));
		mvc.perform(json(put(historyPath()), "{\"content\":\"Atualizado\",\"version\":0}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		verify(histories).save(patientId, "Atualizado", 0L);
	}

	@Test
	void historyTextIsNeverTrimmedBeforeReachingTheService() throws Exception {
		when(histories.save(any(), any(), any())).thenReturn(new Saved(history(), true));

		mvc.perform(json(put(historyPath()), "{\"content\":\"  com espaços  \"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());

		verify(histories).save(patientId, "  com espaços  ", null);
	}

	@Test
	void historyContentLimitsAndBlankAreValidationErrors() throws Exception {
		RequestPostProcessor d = as(Role.DENTIST_ADMIN);
		when(histories.save(any(), any(), any())).thenReturn(new Saved(history(), true));

		for (String bad : List.of("{}", "{\"content\":\"\"}", "{\"content\":\"   \\n \"}",
				"{\"content\":\"" + "a".repeat(10001) + "\"}")) {
			mvc.perform(json(put(historyPath()), bad).with(d)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"));
		}
		mvc.perform(json(put(historyPath()), "{\"content\":\"" + "a".repeat(10000) + "\"}").with(d)).andExpect(status().isCreated());
	}

	@Test
	void historyVersionIsNotValidatedByTheBorderSoCreationCanIgnoreAnyValue() throws Exception {
		when(histories.save(any(), any(), any())).thenReturn(new Saved(history(), true));

		mvc.perform(json(put(historyPath()), "{\"content\":\"x\",\"version\":-1}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());

		verify(histories).save(patientId, "x", -1L);
	}

	@Test
	void historyErrorsFromTheServiceKeepTheirStatus() throws Exception {
		when(histories.get(patientId)).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Histórico não encontrado."));
		when(histories.save(any(), any(), any())).thenThrow(new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado."));

		mvc.perform(get(historyPath()).with(as(Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(json(put(historyPath()), "{\"content\":\"x\"}").with(as(Role.DENTIST_ADMIN))).andExpect(status().isConflict());
	}

	@Test
	void historyGetReturnsDecryptedContentWithoutInternalFields() throws Exception {
		when(histories.get(patientId)).thenReturn(history());

		mvc.perform(get(historyPath()).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").value("Alergia a penicilina"))
				.andExpect(content().string(not(containsString("clinicId"))));
	}

	// ---- notas ----

	@Test
	void noteCreateReturns201ForwardingNotedAtAndRejectsInjectedFields() throws Exception {
		when(notes.create(any(), any(), any())).thenReturn(note());
		String body = "{\"content\":\"Restauração do 36\",\"notedAt\":\"2026-10-22T11:00:00Z\",\"id\":\"%s\",\"patientId\":\"%s\",\"createdBy\":\"%s\"}"
				.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		mvc.perform(json(post(notesPath()), body).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(noteId.toString()))
				.andExpect(jsonPath("$.createdBy").value(userId.toString()))
				.andExpect(jsonPath("$.content").value("Restauração do 36"))
				.andExpect(content().string(not(containsString("clinicId"))));

		verify(notes).create(patientId, "Restauração do 36", Instant.parse("2026-10-22T11:00:00Z"));
	}

	@Test
	void noteCreateWithoutNotedAtPassesNull() throws Exception {
		when(notes.create(any(), any(), any())).thenReturn(note());

		mvc.perform(json(post(notesPath()), "{\"content\":\"x\"}").with(as(Role.DENTIST_ADMIN))).andExpect(status().isCreated());

		verify(notes).create(eq(patientId), eq("x"), isNull());
	}

	@Test
	void noteContentLimitAndBlankAreValidationErrors() throws Exception {
		RequestPostProcessor d = as(Role.DENTIST_ADMIN);
		when(notes.create(any(), any(), any())).thenReturn(note());

		for (String bad : List.of("{}", "{\"content\":\" \"}", "{\"content\":\"" + "a".repeat(5001) + "\"}")) {
			mvc.perform(json(post(notesPath()), bad).with(d)).andExpect(status().isBadRequest());
		}
		mvc.perform(json(post(notesPath()), "{\"content\":\"" + "a".repeat(5000) + "\"}").with(d)).andExpect(status().isCreated());
	}

	@Test
	void futureNotedAtFromTheServiceIs400() throws Exception {
		when(notes.create(any(), any(), any()))
				.thenThrow(new BusinessException(ProblemTypes.VALIDATION, "A data do atendimento não pode estar no futuro."));

		mvc.perform(json(post(notesPath()), "{\"content\":\"x\",\"notedAt\":\"2999-01-01T00:00:00Z\"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void noteListIsPagedWithinTheLimits() throws Exception {
		when(notes.list(any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(note()), PageRequest.of(1, 5), 11));

		mvc.perform(get(notesPath()).param("page", "1").param("size", "5").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(5))
				.andExpect(jsonPath("$.totalElements").value(11))
				.andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.content[0].content").value("Restauração do 36"));
		mvc.perform(get(notesPath()).with(as(Role.DENTIST_ADMIN)));
		verify(notes).list(patientId, 1, 5);
		verify(notes).list(patientId, 0, 20);

		mvc.perform(get(notesPath()).param("size", "51").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get(notesPath()).param("size", "0").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void noteUpdateRequiresContentNotedAtAndVersion() throws Exception {
		when(notes.update(any(), any(), any(), any(), anyLong())).thenReturn(note());
		String path = notesPath() + "/" + noteId;

		mvc.perform(json(put(path), "{\"content\":\"novo\",\"notedAt\":\"2026-10-22T10:00:00Z\",\"version\":4}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		verify(notes).update(patientId, noteId, "novo", Instant.parse("2026-10-22T10:00:00Z"), 4L);

		for (String bad : List.of("{\"content\":\"x\",\"notedAt\":\"2026-10-22T10:00:00Z\"}",
				"{\"content\":\"x\",\"version\":0}", "{\"notedAt\":\"2026-10-22T10:00:00Z\",\"version\":0}")) {
			mvc.perform(json(put(path), bad).with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		}
	}

	@Test
	void malformedIdsAreValidationErrorsAndThereIsNoDelete() throws Exception {
		mvc.perform(get("/api/v1/patients/xyz/notes").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(delete(notesPath() + "/" + noteId).with(as(Role.DENTIST_ADMIN))).andExpect(status().isMethodNotAllowed());
		mvc.perform(delete(historyPath()).with(as(Role.DENTIST_ADMIN))).andExpect(status().isMethodNotAllowed());
	}

	@Test
	void responsesNeverPrintClinicalTextInToString() {
		assertThat(MedicalHistoryResponse.from(history()).toString()).doesNotContain("penicilina");
		assertThat(NoteResponse.from(note()).toString()).doesNotContain("Restauração");
		assertThat(new RecordRequests.SaveHistoryRequest("segredo clínico", null).toString()).doesNotContain("segredo");
		assertThat(new RecordRequests.CreateNoteRequest("segredo clínico", null).toString()).doesNotContain("segredo");
		assertThat(new RecordRequests.UpdateNoteRequest("segredo clínico", NOW, 0L).toString()).doesNotContain("segredo");
	}
}
