package crm_data.phase1;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.Arrays;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.shared.security.Role;

/**
 * As 16 rotas da Fase 1, na ordem em que um dentista consegue percorrê-las contra um banco real (o histórico é criado
 * antes de ser lido, a nota antes de ser listada, o paciente é inativado só perto do fim). Usada pela matriz de papéis
 * do teste web (serviços simulados) e pelo IT (contexto completo e PostgreSQL).
 */
final class Phase1Routes {

	private static final byte[] PNG_HEAD = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	private static final String PATIENT = "\"name\":\"Maria Souza\",\"birthDate\":\"1988-04-12\",\"phone\":\"11987654321\"";

	private Phase1Routes() {
	}

	/** Ids e versões que as rotas referenciam. */
	record Ctx(UUID patientId, UUID noteId, UUID fileId, UUID anonymizableId, long patientVersion, long noteVersion) {

		static Ctx random() {
			return new Ctx(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, 0);
		}
	}

	@FunctionalInterface
	interface Build {
		RequestBuilder apply(Ctx ctx, RequestPostProcessor token);
	}

	/**
	 * @param label rota como no contrato
	 * @param receptionistAllowed {@code true} só para as rotas que a recepção pode usar (as demais são 403)
	 */
	enum Route {

		CREATE_PATIENT("POST /patients", true, (c, t) -> json(post("/api/v1/patients"), "{" + PATIENT + "}").with(t)),
		LIST_PATIENTS("GET /patients", true, (c, t) -> get("/api/v1/patients").with(t)),
		GET_PATIENT("GET /patients/{id}", true, (c, t) -> get(patient(c)).with(t)),
		UPDATE_PATIENT("PUT /patients/{id}", true,
				(c, t) -> json(put(patient(c)), "{" + PATIENT + ",\"version\":" + c.patientVersion() + "}").with(t)),
		SAVE_HISTORY("PUT /patients/{id}/medical-history", false,
				(c, t) -> json(put(patient(c) + "/medical-history"), "{\"content\":\"Alergia grave a penicilina\"}").with(t)),
		GET_HISTORY("GET /patients/{id}/medical-history", false, (c, t) -> get(patient(c) + "/medical-history").with(t)),
		CREATE_NOTE("POST /patients/{id}/notes", false,
				(c, t) -> json(post(patient(c) + "/notes"), "{\"content\":\"Restauração do dente 36\"}").with(t)),
		LIST_NOTES("GET /patients/{id}/notes", false, (c, t) -> get(patient(c) + "/notes").with(t)),
		UPDATE_NOTE("PUT /patients/{id}/notes/{noteId}", false,
				(c, t) -> json(put(patient(c) + "/notes/" + c.noteId()),
						"{\"content\":\"Nota corrigida\",\"notedAt\":\"2020-01-01T00:00:00Z\",\"version\":" + c.noteVersion() + "}").with(t)),
		UPLOAD_FILE("POST /patients/{id}/files", true,
				(c, t) -> multipart(patient(c) + "/files")
						.file(new MockMultipartFile("file", "raio-x.png", "image/png", Arrays.copyOf(PNG_HEAD, 64)))
						.param("kind", "RADIOGRAPH").with(t)),
		LIST_FILES("GET /patients/{id}/files", false, (c, t) -> get(patient(c) + "/files").with(t)),
		DOWNLOAD_URL("GET /patients/{id}/files/{fileId}/download-url", false,
				(c, t) -> get(patient(c) + "/files/" + c.fileId() + "/download-url").with(t)),
		DELETE_FILE("DELETE /patients/{id}/files/{fileId}", false, (c, t) -> delete(patient(c) + "/files/" + c.fileId()).with(t)),
		AUDIT_LOG("GET /audit-log", false, (c, t) -> get("/api/v1/audit-log").with(t)),
		SET_ACTIVE("PATCH /patients/{id}/active", false,
				(c, t) -> json(patch(patient(c) + "/active"), "{\"active\":false}").with(t)),
		ANONYMIZE("POST /patients/{id}/anonymize", false, (c, t) -> post(patient(c.anonymizableId()) + "/anonymize").with(t));

		final String label;
		final boolean receptionistAllowed;
		private final Build build;

		Route(String label, boolean receptionistAllowed, Build build) {
			this.label = label;
			this.receptionistAllowed = receptionistAllowed;
			this.build = build;
		}

		RequestBuilder request(Ctx ctx, RequestPostProcessor token) {
			return build.apply(ctx, token);
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** Requisição sem token: o post-processor é neutro. */
	static final RequestPostProcessor NO_TOKEN = request -> request;

	static RequestPostProcessor token(UUID clinicId, UUID userId, Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static String patient(Ctx ctx) {
		return patient(ctx.patientId());
	}

	private static String patient(UUID id) {
		return "/api/v1/patients/" + id;
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}
}
