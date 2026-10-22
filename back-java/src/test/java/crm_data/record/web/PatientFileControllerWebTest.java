package crm_data.record.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import crm_data.record.domain.FileKind;
import crm_data.record.domain.PatientFile;
import crm_data.record.service.PatientFileService;
import crm_data.record.service.PatientFileService.SignedUrl;
import crm_data.record.service.PatientFileService.Upload;
import crm_data.record.service.UploadRateLimiter;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.storage.StorageException;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP dos exames: matriz papel × rota, multipart, erros, limite de uploads e ausência de {@code storageKey}. */
@WebMvcTest(PatientFileController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class, UploadRateLimiter.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class PatientFileControllerWebTest {

	@Autowired
	MockMvc mvc;
	@MockitoBean
	PatientFileService files;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID(); // um usuário por teste: o limite de uploads é por usuário
	private final UUID patientId = UUID.randomUUID();
	private final UUID fileId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private String base() {
		return "/api/v1/patients/" + patientId + "/files";
	}

	private static MockMultipartFile png() {
		return new MockMultipartFile("file", "raio-x.png", "image/png", new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 });
	}

	private PatientFile stored() {
		PatientFile file = new PatientFile(fileId, patientId, FileKind.RADIOGRAPH, clinicId + "/" + patientId + "/" + fileId,
				"raio-x.png", "image/png", 1234, userId);
		ReflectionTestUtils.setField(file, "clinicId", clinicId);
		ReflectionTestUtils.setField(file, "createdAt", Instant.parse("2026-10-22T12:00:00Z"));
		ReflectionTestUtils.setField(file, "version", 0L);
		return file;
	}

	// ---- upload ----

	@Test
	void dentistAndReceptionistUploadAndGetOnlyTheId() throws Exception {
		when(files.upload(any(), any())).thenReturn(fileId);

		for (Role role : Role.values()) {
			mvc.perform(multipart(base()).file(png()).param("kind", "RADIOGRAPH").with(as(role)))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.id").value(fileId.toString()))
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(content().string(not(containsString("storageKey"))));
		}
		ArgumentCaptor<Upload> captor = ArgumentCaptor.forClass(Upload.class);
		verify(files, times(2)).upload(org.mockito.ArgumentMatchers.eq(patientId), captor.capture());
		Upload upload = captor.getValue();
		assertThat(upload.kind()).isEqualTo("RADIOGRAPH");
		assertThat(upload.declaredContentType()).isEqualTo("image/png");
		assertThat(upload.originalFilename()).isEqualTo("raio-x.png");
		assertThat(upload.size()).isEqualTo(4);
		assertThat(upload.content().open().readAllBytes()).hasSize(4);
		assertThat(upload.toString()).doesNotContain("raio-x");
	}

	@Test
	void uploadWithoutTokenIs401() throws Exception {
		mvc.perform(multipart(base()).file(png()).param("kind", "OTHER")).andExpect(status().isUnauthorized());
		verifyNoInteractions(files);
	}

	@Test
	void missingFileOrKindAndWrongMediaTypeAreClientErrors() throws Exception {
		mvc.perform(multipart(base()).param("kind", "OTHER").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(multipart(base()).file(png()).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='kind')]").exists());
		mvc.perform(post(base()).contentType(MediaType.APPLICATION_JSON).content("{}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isUnsupportedMediaType());
		verifyNoInteractions(files);
	}

	@Test
	void serviceErrorsKeepTheContractStatus() throws Exception {
		when(files.upload(any(), any()))
				.thenThrow(new BusinessException(ProblemTypes.VALIDATION, "Tipo de arquivo não permitido."))
				.thenThrow(new MaxUploadSizeExceededException(10_485_760L))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado."))
				.thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado."))
				.thenThrow(new StorageException("fora do ar https://segredo.supabase.co/chave"));
		int[] expected = { 400, 413, 409, 404, 503 };

		for (int status : expected) {
			var result = mvc.perform(multipart(base()).file(png()).param("kind", "OTHER").with(as(Role.RECEPTIONIST)))
					.andExpect(status().is(status));
			if (status == 503) {
				result.andExpect(jsonPath("$.type").value("https://crm.local/problems/unavailable"))
						.andExpect(content().string(not(containsString("supabase"))));
			}
		}
	}

	@Test
	void theEleventhUploadInAMinuteIs429WithRetryAfterAndNeverReachesTheService() throws Exception {
		when(files.upload(any(), any())).thenReturn(fileId);

		for (int i = 0; i < 10; i++) {
			mvc.perform(multipart(base()).file(png()).param("kind", "OTHER").with(as(Role.RECEPTIONIST))).andExpect(status().isCreated());
		}
		mvc.perform(multipart(base()).file(png()).param("kind", "OTHER").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/rate-limited"));

		verify(files, times(10)).upload(any(), any());
	}

	// ---- recepção ----

	@Test
	void receptionistGets403OnListDownloadAndDeleteWithoutTouchingTheService() throws Exception {
		RequestPostProcessor r = as(Role.RECEPTIONIST);

		mvc.perform(get(base()).with(r)).andExpect(status().isForbidden());
		mvc.perform(get(base() + "/" + fileId + "/download-url").with(r)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
		mvc.perform(delete(base() + "/" + fileId).with(r)).andExpect(status().isForbidden());

		verifyNoInteractions(files);
	}

	@Test
	void unauthenticatedListDownloadAndDeleteAre401() throws Exception {
		mvc.perform(get(base())).andExpect(status().isUnauthorized());
		mvc.perform(get(base() + "/" + fileId + "/download-url")).andExpect(status().isUnauthorized());
		mvc.perform(delete(base() + "/" + fileId)).andExpect(status().isUnauthorized());
	}

	// ---- dentista ----

	@Test
	void listReturnsPagedFilesWithoutTheStorageKey() throws Exception {
		when(files.list(any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(stored()), PageRequest.of(1, 5), 11));

		mvc.perform(get(base()).param("page", "1").param("size", "5").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(5))
				.andExpect(jsonPath("$.totalElements").value(11))
				.andExpect(jsonPath("$.content[0].id").value(fileId.toString()))
				.andExpect(jsonPath("$.content[0].kind").value("RADIOGRAPH"))
				.andExpect(jsonPath("$.content[0].originalFilename").value("raio-x.png"))
				.andExpect(jsonPath("$.content[0].contentType").value("image/png"))
				.andExpect(jsonPath("$.content[0].sizeBytes").value(1234))
				.andExpect(content().string(not(containsString("storageKey"))))
				.andExpect(content().string(not(containsString("clinicId"))))
				.andExpect(content().string(not(containsString("createdBy"))));
		verify(files).list(patientId, 1, 5);
	}

	@Test
	void listLimitsAreValidated() throws Exception {
		mvc.perform(get(base()).param("size", "51").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get(base()).param("size", "0").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get(base()).param("page", "-1").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		verifyNoInteractions(files);
	}

	@Test
	void downloadUrlIsReturnedWithNoStore() throws Exception {
		when(files.signedUrl(patientId, fileId)).thenReturn(new SignedUrl(URI.create("https://x.supabase.co/storage/v1/object/sign/b/k?token=t"), 60));

		mvc.perform(get(base() + "/" + fileId + "/download-url").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.url").value("https://x.supabase.co/storage/v1/object/sign/b/k?token=t"))
				.andExpect(jsonPath("$.expiresIn").value(60));
	}

	@Test
	void downloadUrlOfAnUnknownFileIs404() throws Exception {
		when(files.signedUrl(patientId, fileId)).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Exame não encontrado."));

		mvc.perform(get(base() + "/" + fileId + "/download-url").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
	}

	@Test
	void deleteReturns204ForTheDentistAndMalformedIdsAre400() throws Exception {
		mvc.perform(delete(base() + "/" + fileId).with(as(Role.DENTIST_ADMIN))).andExpect(status().isNoContent());
		verify(files).remove(patientId, fileId);

		mvc.perform(delete(base() + "/nao-e-uuid").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/patients/xyz/files").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void everyFileRouteResponseIsNoStore() throws Exception {
		when(files.upload(any(), any())).thenReturn(fileId);
		when(files.list(any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));

		mvc.perform(multipart(base()).file(png()).param("kind", "OTHER").with(as(Role.DENTIST_ADMIN)))
				.andExpect(header().string("Cache-Control", "no-store"));
		mvc.perform(get(base()).with(as(Role.DENTIST_ADMIN))).andExpect(header().string("Cache-Control", "no-store"));
	}
}
