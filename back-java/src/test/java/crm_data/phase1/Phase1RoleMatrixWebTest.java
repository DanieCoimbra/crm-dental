package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import crm_data.patient.domain.Patient;
import crm_data.patient.domain.Sex;
import crm_data.patient.service.PatientAnonymizationService;
import crm_data.patient.service.PatientAnonymizationService.StorageCleanup;
import crm_data.patient.service.PatientService;
import crm_data.patient.service.PatientService.PatientDetails;
import crm_data.patient.web.PatientController;
import crm_data.phase1.Phase1Routes.Ctx;
import crm_data.phase1.Phase1Routes.Route;
import crm_data.record.domain.AuditAction;
import crm_data.record.domain.AuditLog;
import crm_data.record.domain.ClinicalNote;
import crm_data.record.domain.FileKind;
import crm_data.record.domain.MedicalHistory;
import crm_data.record.domain.PatientFile;
import crm_data.record.service.AuditQueryService;
import crm_data.record.service.ClinicalNoteService;
import crm_data.record.service.MedicalHistoryService;
import crm_data.record.service.MedicalHistoryService.Saved;
import crm_data.record.service.PatientFileService;
import crm_data.record.service.PatientFileService.SignedUrl;
import crm_data.record.service.UploadRateLimiter;
import crm_data.record.web.AuditLogController;
import crm_data.record.web.ClinicalNoteController;
import crm_data.record.web.MedicalHistoryController;
import crm_data.record.web.PatientFileController;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz papel × rota das 16 rotas da Fase 1 ({@code contract.md} da F06, seção 3): sem token → 401; recepção → o
 * status da matriz (2xx nas rotas liberadas, 403 nas demais, sem tocar nenhum service); dentista → 2xx. Toda resposta
 * das rotas clínicas leva {@code Cache-Control: no-store} e os erros de acesso saem em {@code problem+json}.
 */
@WebMvcTest({ PatientController.class, MedicalHistoryController.class, ClinicalNoteController.class,
		PatientFileController.class, AuditLogController.class })
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class, UploadRateLimiter.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class Phase1RoleMatrixWebTest {

	private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

	@Autowired
	MockMvc mvc;
	@MockitoBean
	PatientService patients;
	@MockitoBean
	PatientAnonymizationService anonymization;
	@MockitoBean
	MedicalHistoryService histories;
	@MockitoBean
	ClinicalNoteService notes;
	@MockitoBean
	PatientFileService files;
	@MockitoBean
	AuditQueryService audit;

	private final UUID clinicId = UUID.randomUUID();
	private final Ctx ctx = Ctx.random();

	// ---- objetos de retorno dos services simulados ----

	private Patient patient() {
		Patient patient = new Patient(ctx.patientId(), UUID.randomUUID());
		ReflectionTestUtils.setField(patient, "clinicId", clinicId);
		patient.setName("Maria Souza");
		patient.setBirthDate(LocalDate.of(1988, 4, 12));
		patient.setSex(Sex.FEMALE);
		patient.setPhone("11987654321");
		ReflectionTestUtils.setField(patient, "createdAt", NOW);
		ReflectionTestUtils.setField(patient, "updatedAt", NOW);
		ReflectionTestUtils.setField(patient, "version", 0L);
		return patient;
	}

	private MedicalHistory history() {
		MedicalHistory history = new MedicalHistory(UUID.randomUUID(), ctx.patientId(), "Alergia", UUID.randomUUID());
		ReflectionTestUtils.setField(history, "createdAt", NOW);
		ReflectionTestUtils.setField(history, "updatedAt", NOW);
		ReflectionTestUtils.setField(history, "version", 0L);
		return history;
	}

	private ClinicalNote note() {
		ClinicalNote note = new ClinicalNote(ctx.noteId(), ctx.patientId(), NOW, "Nota", UUID.randomUUID());
		ReflectionTestUtils.setField(note, "createdAt", NOW);
		ReflectionTestUtils.setField(note, "updatedAt", NOW);
		ReflectionTestUtils.setField(note, "version", 0L);
		return note;
	}

	private PatientFile file() {
		PatientFile file = new PatientFile(ctx.fileId(), ctx.patientId(), FileKind.OTHER,
				clinicId + "/" + ctx.patientId() + "/" + ctx.fileId(), "a.png", "image/png", 10, UUID.randomUUID());
		ReflectionTestUtils.setField(file, "createdAt", NOW);
		return file;
	}

	private AuditQueryService.Entry entry() {
		AuditLog log = (AuditLog) org.springframework.objenesis.ObjenesisHelper.newInstance(AuditLog.class);
		ReflectionTestUtils.setField(log, "id", 1L);
		ReflectionTestUtils.setField(log, "userId", UUID.randomUUID());
		ReflectionTestUtils.setField(log, "tableName", "patients");
		ReflectionTestUtils.setField(log, "recordId", ctx.patientId());
		ReflectionTestUtils.setField(log, "action", AuditAction.INSERT);
		ReflectionTestUtils.setField(log, "occurredAt", NOW);
		return new AuditQueryService.Entry(log, "Dra. Ana");
	}

	private void stubServices() {
		when(patients.create(any())).thenReturn(patient());
		when(patients.search(any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(patient())));
		when(patients.get(any())).thenReturn(patient());
		when(patients.update(any(), any(), anyLong())).thenReturn(patient());
		when(patients.setActive(any(), anyBoolean())).thenReturn(patient());
		when(anonymization.anonymize(any()))
				.thenReturn(new PatientAnonymizationService.Result(NOW, StorageCleanup.COMPLETED, 0));
		when(histories.get(any())).thenReturn(history());
		when(histories.save(any(), any(), any())).thenReturn(new Saved(history(), true));
		when(notes.create(any(), any(), any(), any())).thenReturn(note());
		when(notes.list(any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(note())));
		when(notes.update(any(), any(), any(), any(), any(), anyLong())).thenReturn(note());
		when(files.upload(any(), any())).thenReturn(ctx.fileId());
		when(files.list(any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(file())));
		when(files.signedUrl(any(), any())).thenReturn(new SignedUrl(URI.create("https://x.supabase.co/o?token=t"), 60));
		when(audit.list(any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(entry())));
	}

	private void assertNoServiceWasCalled() {
		verifyNoInteractions(patients, anonymization, histories, notes, files, audit);
	}

	// ---- a matriz ----

	@Test
	void thePhaseHasExactlySixteenRoutes() {
		assertThat(Route.values()).hasSize(16);
		assertThat(List.of(Route.values()).stream().filter(r -> r.receptionistAllowed)).extracting(r -> r.label)
				.containsExactlyInAnyOrder("POST /patients", "GET /patients", "GET /patients/{id}", "PUT /patients/{id}",
						"POST /patients/{id}/files");
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(Route.class)
	void withoutTokenEveryRouteIs401AndNoServiceIsCalled(Route route) throws Exception {
		mvc.perform(route.request(ctx, Phase1Routes.NO_TOKEN))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unauthorized"));

		assertNoServiceWasCalled();
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(Route.class)
	void theReceptionistIs2xxOnItsFiveRoutesAnd403OnTheRestWithoutReachingAnyService(Route route) throws Exception {
		stubServices();

		var result = mvc.perform(route.request(ctx, Phase1Routes.token(clinicId, UUID.randomUUID(), Role.RECEPTIONIST)));

		if (route.receptionistAllowed) {
			result.andExpect(status().is2xxSuccessful());
		}
		else {
			result.andExpect(status().isForbidden())
					.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
					.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
			assertNoServiceWasCalled();
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(Route.class)
	void theDentistIs2xxOnEveryRouteAndTheResponseIsNoStore(Route route) throws Exception {
		stubServices();

		mvc.perform(route.request(ctx, Phase1Routes.token(clinicId, UUID.randomUUID(), Role.DENTIST_ADMIN)))
				.andExpect(status().is2xxSuccessful())
				.andExpect(header().string("Cache-Control", "no-store"));
	}

	// ---- o servidor decide quem é o dono e o papel ----

	@Test
	void clientSuppliedClinicCreatorRoleAndIdsNeverReachTheServices() throws Exception {
		stubServices();
		String injected = "\"id\":\"%s\",\"clinicId\":\"%s\",\"createdBy\":\"%s\",\"role\":\"DENTIST_ADMIN\",\"patientId\":\"%s\"".formatted(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		var dentist = Phase1Routes.token(clinicId, UUID.randomUUID(), Role.DENTIST_ADMIN);

		MvcResult created = mvc.perform(post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Maria\",\"birthDate\":\"1988-04-12\",\"phone\":\"11987654321\"," + injected + "}").with(dentist))
				.andExpect(status().isCreated()).andReturn();
		mvc.perform(post("/api/v1/patients/" + ctx.patientId() + "/notes").contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"x\"," + injected + "}").with(dentist)).andExpect(status().isCreated());
		mvc.perform(put("/api/v1/patients/" + ctx.patientId() + "/medical-history").contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"x\"," + injected + "}").with(dentist)).andExpect(status().isCreated());

		ArgumentCaptor<PatientDetails> details = ArgumentCaptor.forClass(PatientDetails.class);
		verify(patients).create(details.capture());
		assertThat(details.getValue().name()).isEqualTo("Maria");
		verify(notes).create(ctx.patientId(), "x", null, null);
		verify(histories).save(ctx.patientId(), "x", null);
		assertThat(created.getResponse().getContentAsString()).doesNotContain("clinicId", "createdBy");
	}
}
