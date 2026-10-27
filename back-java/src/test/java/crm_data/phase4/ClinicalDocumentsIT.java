package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/** F02 e F03: emitir, listar, abrir e anular documentos clínicos contra o schema real (V001-V035) com RLS. */
class ClinicalDocumentsIT extends Phase4ItSupport {

	private static final String SECRET = "Amoxicilina 500 mg, 8/8 h por 7 dias";

	private static String certificate(int days, String content) {
		return "{\"documentType\":\"CERTIFICATE\",\"restDays\":%d%s}".formatted(days,
				content == null ? "" : ",\"content\":\"" + content + "\"");
	}

	private static String prescription(String content) {
		return "{\"documentType\":\"PRESCRIPTION\",\"content\":\"%s\"}".formatted(content);
	}

	private static String referral(String specialty, String content) {
		return "{\"documentType\":\"REFERRAL\",\"targetSpecialty\":\"%s\",\"content\":\"%s\"}".formatted(specialty, content);
	}

	private MvcResult issue(Setup setup, String body) throws Exception {
		return mvc.perform(json(post("/api/v1/patients/" + setup.patient() + "/clinical-documents"), body)
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andReturn();
	}

	private JsonNode issueOk(Setup setup, String body) throws Exception {
		MvcResult result = issue(setup, body);
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return read(result);
	}

	private JsonNode open(Clinic clinic, String id) throws Exception {
		return read(mvc.perform(get("/api/v1/clinical-documents/" + id).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andReturn());
	}

	@Test
	void dentistIssuesTheThreeTypesWithSequentialNumbersPerType() throws Exception {
		Setup setup = openDentistSetup();

		JsonNode first = issueOk(setup, certificate(2, null));
		JsonNode second = issueOk(setup, certificate(1, "Repouso"));
		JsonNode rx = issueOk(setup, prescription(SECRET));
		JsonNode ref = issueOk(setup, referral("Endodontia", "Dente 36 com necrose"));

		assertThat(first.get("number").asInt()).isEqualTo(1);
		assertThat(second.get("number").asInt()).isEqualTo(2);
		assertThat(rx.get("number").asInt()).isEqualTo(1);
		assertThat(ref.get("number").asInt()).isEqualTo(1);
		assertThat(rx.get("content").asString()).isEqualTo(SECRET);
		assertThat(rx.get("state").asString()).isEqualTo("ACTIVE");
		assertThat(rx.get("createdByName").asString()).isEqualTo("Dra. Teste");
		assertThat(rx.has("sha256")).isFalse();
		assertThat(rx.has("clinicId")).isFalse();
		assertThat(rx.has("createdBy")).isFalse();
		assertThat(first.get("issuedOn").asString()).isEqualTo(LocalDate.now().toString());
	}

	@Test
	void locationPointsToTheDocumentAndTheResponseIsNotCached() throws Exception {
		Setup setup = openDentistSetup();

		MvcResult result = issue(setup, prescription(SECRET));

		String id = read(result).get("id").asString();
		assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/clinical-documents/" + id);
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
	}

	@Test
	void theTextIsEncryptedAtRestAndTheSealIsStored() throws Exception {
		Setup setup = openDentistSetup();

		String id = issueOk(setup, prescription(SECRET)).get("id").asString();

		String stored = DatabaseContainer.admin().queryForObject("SELECT content FROM core.clinical_documents WHERE id = ?::uuid",
				String.class, id);
		String seal = DatabaseContainer.admin().queryForObject("SELECT sha256 FROM core.clinical_documents WHERE id = ?::uuid",
				String.class, id);
		assertThat(stored).doesNotContain("Amoxicilina").matches("^[A-Za-z0-9+/]+={0,2}$").hasSizeGreaterThanOrEqualTo(40);
		assertThat(seal).matches("^[0-9a-f]{64}$");
		assertThat(open(setup.clinic(), id).get("content").asString()).isEqualTo(SECRET);
	}

	@Test
	void listHasNoContentIsNewestFirstAndFiltersByType() throws Exception {
		Setup setup = openDentistSetup();
		issueOk(setup, certificate(2, null));
		issueOk(setup, prescription(SECRET));
		String last = issueOk(setup, referral("Endodontia", "Dente 36")).get("id").asString();

		JsonNode all = read(mvc.perform(get("/api/v1/patients/" + setup.patient() + "/clinical-documents")
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
				.andReturn());
		assertThat(all.get("content").get(0).get("id").asString()).isEqualTo(last);
		assertThat(all.get("content").get(0).has("content")).isFalse();
		assertThat(all.get("content").get(0).has("voidReason")).isFalse();
		assertThat(all.get("content").get(0).has("version")).isFalse();

		mvc.perform(get("/api/v1/patients/" + setup.patient() + "/clinical-documents?type=PRESCRIPTION")
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].documentType").value("PRESCRIPTION"));
		mvc.perform(get("/api/v1/patients/" + setup.patient() + "/clinical-documents?size=51")
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void invalidCombinationsAreRejectedWith400AndFieldErrors() throws Exception {
		Setup setup = openDentistSetup();
		String[] bodies = {
				"{\"documentType\":\"CERTIFICATE\"}",
				"{\"documentType\":\"CERTIFICATE\",\"restDays\":91}",
				"{\"documentType\":\"CERTIFICATE\",\"restDays\":2,\"targetSpecialty\":\"Endo\"}",
				"{\"documentType\":\"PRESCRIPTION\"}",
				"{\"documentType\":\"PRESCRIPTION\",\"content\":\"   \"}",
				"{\"documentType\":\"PRESCRIPTION\",\"content\":\"x\",\"restDays\":2}",
				"{\"documentType\":\"REFERRAL\",\"content\":\"x\"}",
				"{\"documentType\":\"PRESCRIPTION\",\"content\":\"" + "a".repeat(4001) + "\"}",
				"{\"documentType\":\"PRESCRIPTION\",\"content\":\"x\",\"issuedOn\":\"1999-12-31\"}",
				"{}" };
		for (String body : bodies) {
			MvcResult result = issue(setup, body);
			assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
			assertThat(read(result).get("errors")).as(body).isNotEmpty();
		}
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.clinical_documents WHERE clinic_id = ?", Integer.class,
				setup.clinic().id())).isZero();
	}

	@Test
	void futureIssueDateIs422AndPastDateIsAccepted() throws Exception {
		Setup setup = openDentistSetup();

		MvcResult future = issue(setup, "{\"documentType\":\"PRESCRIPTION\",\"content\":\"x\",\"issuedOn\":\"" + LocalDate.now().plusDays(2) + "\"}");
		assertThat(future.getResponse().getStatus()).isEqualTo(422);
		assertThat(read(future).get("reason").asString()).isEqualTo("ISSUED_ON_IN_FUTURE");

		JsonNode past = issueOk(setup, "{\"documentType\":\"PRESCRIPTION\",\"content\":\"x\",\"issuedOn\":\"2026-01-15\"}");
		assertThat(past.get("issuedOn").asString()).isEqualTo("2026-01-15");
	}

	@Test
	void dentistWithoutCroGets409AndNothingIsStored() throws Exception {
		Clinic clinic = newDentistClinic();
		Setup setup = setupOf(clinic, createPatient(clinic, "Maria Souza"));

		MvcResult result = issue(setup, prescription(SECRET));

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(read(result).get("detail").asString()).contains("CRO");
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.clinical_documents WHERE clinic_id = ?", Integer.class,
				clinic.id())).isZero();
	}

	@Test
	void inactivePatientAndUnknownAppointmentAreRejected() throws Exception {
		Setup setup = openDentistSetup();

		MvcResult unknown = issue(setup, "{\"documentType\":\"PRESCRIPTION\",\"content\":\"x\",\"appointmentId\":\"" + UUID.randomUUID() + "\"}");
		assertThat(unknown.getResponse().getStatus()).isEqualTo(404);

		mvc.perform(json(patch("/api/v1/patients/" + setup.patient() + "/active"), "{\"active\":false}").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		assertThat(issue(setup, prescription(SECRET)).getResponse().getStatus()).isEqualTo(409);
	}

	@Test
	void receptionAndAnonymousAreBlockedOnEveryRoute() throws Exception {
		Setup setup = openDentistSetup();
		String id = issueOk(setup, prescription(SECRET)).get("id").asString();
		String list = "/api/v1/patients/" + setup.patient() + "/clinical-documents";
		String item = "/api/v1/clinical-documents/" + id;

		mvc.perform(json(post(list), prescription("x")).with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(get(list).with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(get(item).with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(post(item + "/void"), "{\"version\":0}").with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(get(list)).andExpect(status().isUnauthorized());
		mvc.perform(get(item)).andExpect(status().isUnauthorized());
		mvc.perform(json(post(item + "/void"), "{\"version\":0}")).andExpect(status().isUnauthorized());
	}

	@Test
	void anotherClinicSeesNothing() throws Exception {
		Setup mine = openDentistSetup();
		Setup other = openDentistSetup();
		String id = issueOk(mine, prescription(SECRET)).get("id").asString();

		mvc.perform(get("/api/v1/clinical-documents/" + id).with(as(other.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/clinical-documents/" + id + "/void"), "{\"version\":0}").with(as(other.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + mine.patient() + "/clinical-documents").with(as(other.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		assertThat(issue(setupOf(other.clinic(), mine.patient()), prescription("x")).getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void tamperedContentOrSealIs500IntegrityWithoutLeakingText() throws Exception {
		Setup setup = openDentistSetup();
		String byContent = issueOk(setup, prescription(SECRET)).get("id").asString();
		String bySeal = issueOk(setup, prescription(SECRET)).get("id").asString();
		DatabaseContainer.admin().execute("ALTER TABLE core.clinical_documents DISABLE TRIGGER USER");
		try {
			DatabaseContainer.admin().update("UPDATE core.clinical_documents SET sha256 = repeat('0', 64) WHERE id = ?::uuid", bySeal);
			DatabaseContainer.admin().update(
					"UPDATE core.clinical_documents SET content = (SELECT content FROM core.clinical_documents WHERE id = ?::uuid) WHERE id = ?::uuid",
					bySeal, byContent);
		}
		finally {
			DatabaseContainer.admin().execute("ALTER TABLE core.clinical_documents ENABLE TRIGGER USER");
		}

		for (String id : new String[] { byContent, bySeal }) {
			MvcResult result = mvc.perform(get("/api/v1/clinical-documents/" + id).with(as(setup.clinic(), Role.DENTIST_ADMIN)))
					.andReturn();
			assertThat(result.getResponse().getStatus()).isEqualTo(500);
			assertThat(result.getResponse().getContentAsString()).contains("/problems/integrity").doesNotContain("Amoxicilina");
		}
	}

	@Test
	void voidMarksTheDocumentOnceAndIsAudited() throws Exception {
		Setup setup = openDentistSetup();
		JsonNode created = issueOk(setup, prescription(SECRET));
		String id = created.get("id").asString();
		String url = "/api/v1/clinical-documents/" + id + "/void";

		JsonNode voided = read(mvc.perform(json(post(url), "{\"version\":%d,\"reason\":\"  Paciente errado  \"}".formatted(created.get("version").asLong()))
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());

		assertThat(voided.get("state").asString()).isEqualTo("VOIDED");
		assertThat(voided.get("voidReason").asString()).isEqualTo("Paciente errado");
		assertThat(voided.get("voidedByName").asString()).isEqualTo("Dra. Teste");
		assertThat(voided.get("content").asString()).isEqualTo(SECRET);
		assertThat(voided.get("version").asLong()).isGreaterThan(created.get("version").asLong());

		mvc.perform(json(post(url), "{\"version\":%d}".formatted(voided.get("version").asLong())).with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());

		JsonNode audit = read(mvc.perform(get("/api/v1/audit-log?tableName=clinical_documents&recordId=" + id)
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());
		assertThat(audit.get("content").valueStream().map(n -> n.get("action").asString())).containsExactlyInAnyOrder("INSERT", "VOID");
	}

	@Test
	void voidWithStaleVersionOrTooLongReasonIsRejectedAndNothingChanges() throws Exception {
		Setup setup = openDentistSetup();
		JsonNode created = issueOk(setup, prescription(SECRET));
		String url = "/api/v1/clinical-documents/" + created.get("id").asString() + "/void";

		mvc.perform(json(post(url), "{\"version\":99}").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(json(post(url), "{\"version\":0,\"reason\":\"" + "a".repeat(201) + "\"}").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(json(post(url), "{}").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());

		assertThat(open(setup.clinic(), created.get("id").asString()).get("state").asString()).isEqualTo("ACTIVE");
	}

	@Test
	void forgedFieldsInTheBodyAreIgnored() throws Exception {
		Setup setup = openDentistSetup();
		UUID forgedClinic = UUID.randomUUID();

		JsonNode created = issueOk(setup, "{\"documentType\":\"PRESCRIPTION\",\"content\":\"x\",\"number\":99,\"clinicId\":\"" + forgedClinic
				+ "\",\"voidedAt\":\"2026-01-01T00:00:00Z\",\"createdBy\":\"" + UUID.randomUUID() + "\",\"sha256\":\"" + "a".repeat(64) + "\"}");

		assertThat(created.get("number").asInt()).isEqualTo(1);
		assertThat(created.get("state").asString()).isEqualTo("ACTIVE");
		assertThat(DatabaseContainer.admin().queryForObject("SELECT clinic_id = ? FROM core.clinical_documents WHERE id = ?::uuid", Boolean.class,
				setup.clinic().id(), created.get("id").asString())).isTrue();
	}

	@Test
	void anonymizingThePatientClearsTheDocumentsButKeepsTheRows() throws Exception {
		Setup setup = openDentistSetup();
		String id = issueOk(setup, referral("Endodontia", "Dente 36 com necrose")).get("id").asString();
		String patientUrl = "/api/v1/patients/" + setup.patient();
		mvc.perform(json(patch(patientUrl + "/active"), "{\"active\":false}").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		mvc.perform(post(patientUrl + "/anonymize").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());

		JsonNode doc = open(setup.clinic(), id);
		assertThat(doc.get("state").asString()).isEqualTo("ANONYMIZED");
		assertThat(doc.get("anonymized").asBoolean()).isTrue();
		assertThat(doc.get("content").isNull()).isTrue();
		assertThat(doc.get("targetSpecialty").isNull()).isTrue();
		assertThat(doc.get("number").asInt()).isEqualTo(1);

		mvc.perform(get(patientUrl + "/clinical-documents").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].anonymized").value(true));
		mvc.perform(json(post("/api/v1/clinical-documents/" + id + "/void"), "{\"version\":0}").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
	}
}
