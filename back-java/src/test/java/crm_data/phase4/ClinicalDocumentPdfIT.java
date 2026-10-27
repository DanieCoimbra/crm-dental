package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import crm_data.document.service.DocumentPdfModel;
import crm_data.document.service.DocumentPdfRenderer;
import crm_data.document.service.DocumentRules;
import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import tools.jackson.databind.JsonNode;

/** F04: PDF do documento clínico contra o schema real, com o registro {@code DOWNLOAD} na mesma transação. */
@SuppressWarnings("deprecation") // OpenPDF 2.x: ver DocumentPdfRenderer
class ClinicalDocumentPdfIT extends Phase4ItSupport {

	@MockitoSpyBean
	DocumentPdfRenderer renderer;

	@AfterEach
	void resetSpy() {
		org.mockito.Mockito.reset(renderer);
	}

	private String issue(Setup setup, String body) throws Exception {
		return read(mvc.perform(json(post("/api/v1/patients/" + setup.patient() + "/clinical-documents"), body)
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn()).get("id").asString();
	}

	private String certificate(Setup setup) throws Exception {
		return issue(setup, "{\"documentType\":\"CERTIFICATE\",\"restDays\":2,\"content\":\"Repouso relativo\"}");
	}

	private MvcResult pdf(Setup setup, String id) throws Exception {
		return mvc.perform(get("/api/v1/clinical-documents/" + id + "/pdf").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andReturn();
	}

	private int downloads(String id) {
		return DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.audit_log WHERE record_id = ?::uuid AND action = 'DOWNLOAD'",
				Integer.class, id);
	}

	private static String text(byte[] pdf) throws IOException {
		PdfReader reader = new PdfReader(pdf);
		try {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			StringBuilder all = new StringBuilder();
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				all.append(extractor.getTextFromPage(page)).append('\n');
			}
			return all.toString().replaceAll("[\\u00a0\\s]+", " ");
		}
		finally {
			reader.close();
		}
	}

	@Test
	void dentistGetsAnInlinePdfWithTheExactHeadersAndOneDownloadRecord() throws Exception {
		Setup setup = openDentistSetup();
		String id = certificate(setup);

		MvcResult result = pdf(setup, id);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
		assertThat(result.getResponse().getHeader("Content-Disposition")).isEqualTo("inline; filename=\"atestado-1.pdf\"");
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		byte[] bytes = result.getResponse().getContentAsByteArray();
		assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
		String text = text(bytes);
		assertThat(text).contains("ATESTADO Nº 1", "Maria Souza", "Repouso relativo", "Dra. Teste", "CRO 12345/SP")
				.doesNotContain("ANULADO");
		assertThat(downloads(id)).isEqualTo(1);
	}

	@Test
	void everyDeliveredPdfIsAuditedAndShowsInTheAuditLogWithTheUser() throws Exception {
		Setup setup = openDentistSetup();
		String id = certificate(setup);

		pdf(setup, id);
		pdf(setup, id);

		assertThat(downloads(id)).isEqualTo(2);
		JsonNode audit = read(mvc.perform(get("/api/v1/audit-log?tableName=clinical_documents&recordId=" + id)
				.with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());
		assertThat(audit.get("content").valueStream().map(n -> n.get("action").asString()).filter("DOWNLOAD"::equals).count()).isEqualTo(2);
		assertThat(audit.get("content").get(0).get("userName").asString()).isEqualTo("Dra. Teste");
	}

	@Test
	void prescriptionAndReferralHaveTheirOwnNames() throws Exception {
		Setup setup = openDentistSetup();
		String rx = issue(setup, "{\"documentType\":\"PRESCRIPTION\",\"content\":\"Dipirona 500 mg\"}");
		String ref = issue(setup, "{\"documentType\":\"REFERRAL\",\"targetSpecialty\":\"Endodontia\",\"content\":\"Dente 36\"}");

		assertThat(pdf(setup, rx).getResponse().getHeader("Content-Disposition")).contains("receita-1.pdf");
		MvcResult referral = pdf(setup, ref);
		assertThat(referral.getResponse().getHeader("Content-Disposition")).contains("encaminhamento-1.pdf");
		assertThat(text(referral.getResponse().getContentAsByteArray())).contains("conduta em Endodontia");
	}

	@Test
	void voidedDocumentPdfIsMarkedAndStillAudited() throws Exception {
		Setup setup = openDentistSetup();
		String id = certificate(setup);
		mvc.perform(json(post("/api/v1/clinical-documents/" + id + "/void"), "{\"version\":0}").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());

		MvcResult result = pdf(setup, id);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(text(result.getResponse().getContentAsByteArray())).contains("ANULADO", "Anulado em");
		assertThat(downloads(id)).isEqualTo(1);
	}

	@Test
	void receptionAnonymousAndAnotherClinicCannotGetThePdf() throws Exception {
		Setup setup = openDentistSetup();
		Setup other = openDentistSetup();
		String id = certificate(setup);
		String url = "/api/v1/clinical-documents/" + id + "/pdf";

		mvc.perform(get(url).with(as(setup.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(get(url)).andExpect(status().isUnauthorized());
		mvc.perform(get(url).with(as(other.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/clinical-documents/" + java.util.UUID.randomUUID() + "/pdf").with(as(setup.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		assertThat(downloads(id)).isZero();
	}

	@Test
	void anonymizedDocumentIs409AndRecordsNothing() throws Exception {
		Setup setup = openDentistSetup();
		String id = certificate(setup);
		String patientUrl = "/api/v1/patients/" + setup.patient();
		mvc.perform(json(patch(patientUrl + "/active"), "{\"active\":false}").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		mvc.perform(post(patientUrl + "/anonymize").with(as(setup.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());

		MvcResult result = pdf(setup, id);

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(downloads(id)).isZero();
	}

	@Test
	void renderingFailureRollsBackTheDownloadRecord() throws Exception {
		Setup setup = openDentistSetup();
		String id = certificate(setup);
		doThrow(new IllegalStateException("falha de renderização")).when(renderer).render(any(DocumentPdfModel.class));

		MvcResult result = pdf(setup, id);

		assertThat(result.getResponse().getStatus()).isEqualTo(500);
		assertThat(result.getResponse().getContentAsString()).doesNotContain("renderização", "Repouso");
		assertThat(downloads(id)).isZero();
	}

	@Test
	void pdfAboveFourMegabytesIs422AndRollsBackTheDownloadRecord() throws Exception {
		Setup setup = openDentistSetup();
		String id = certificate(setup);
		doReturn(new byte[DocumentRules.MAX_PDF_BYTES + 1]).when(renderer).render(any(DocumentPdfModel.class));

		MvcResult result = pdf(setup, id);

		assertThat(result.getResponse().getStatus()).isEqualTo(422);
		assertThat(read(result).get("reason").asString()).isEqualTo("PDF_TOO_LARGE");
		assertThat(downloads(id)).isZero();
	}

	@Test
	void thirtyFirstPdfInTheMinuteIs429WithRetryAfter() throws Exception {
		Setup setup = openDentistSetup();
		String id = certificate(setup);
		for (int i = 0; i < 30; i++) {
			assertThat(pdf(setup, id).getResponse().getStatus()).isEqualTo(200);
		}

		MvcResult limited = pdf(setup, id);

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();
		assertThat(downloads(id)).isEqualTo(30);
	}
}
