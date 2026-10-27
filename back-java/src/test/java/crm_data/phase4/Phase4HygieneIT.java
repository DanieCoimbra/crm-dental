package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * Higiene de log e de resposta da Fase 4: percorre CRO, emissão dos três tipos, leitura, lista, anulação com motivo, PDF,
 * erros de todo tipo (400, 404, 409, 422, 500 de integridade) e anonimização com texto clínico, motivo, especialidade, nome
 * do paciente e CRO sentinelas, e afirma que nenhuma linha de log e nenhuma resposta de erro os contém (nem o {@code DETAIL}
 * do driver, SQL ou stack trace). <strong>Mutação:</strong> injetar uma sentinela num log faz o detector achá-la; sem esse
 * controle um detector quebrado passaria em silêncio.
 */
class Phase4HygieneIT extends Phase4ItSupport {

	private static final String PATIENT_NAME = "Paciente Higienizado Fase Quatro";
	private static final String CONTENT = "SENTINELA-TEXTO-CLINICO-RECEITA";
	private static final String COMPLEMENT = "SENTINELA-COMPLEMENTO-ATESTADO";
	private static final String SPECIALTY = "SENTINELA-ESPECIALIDADE";
	private static final String REASON = "SENTINELA-MOTIVO-ANULACAO";
	private static final String CRO = "7654321";

	private static final List<String> LEAKS_IN_ERRORS = List.of("Exception", "\tat ", "at crm_data", "org.postgresql", "org.hibernate",
			"org.springframework", "SQLState", "ERROR:", "Detail:", "DETAIL", "Key (", "jdbc:", "insert into", "select ", "update core",
			"violates", "Caused by", "ck_cdoc_", "ux_cdoc_number", "record_document_download");

	private final List<String> errorBodies = new ArrayList<>();

	private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = mvc.perform(request).andReturn();
		if (result.getResponse().getStatus() >= 400) {
			errorBodies.add(result.getResponse().getContentAsString());
		}
		return result;
	}

	private static List<String> leaksIn(List<ILoggingEvent> events, List<String> secrets) {
		List<String> leaks = new ArrayList<>();
		for (ILoggingEvent event : events) {
			String line = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : stack(event.getThrowableProxy()));
			for (String secret : secrets) {
				if (line.contains(secret)) {
					leaks.add(secret + " em: " + event.getLoggerName());
				}
			}
		}
		return leaks;
	}

	private static String stack(IThrowableProxy proxy) {
		StringBuilder text = new StringBuilder(proxy.getClassName()).append(proxy.getMessage());
		for (IThrowableProxy cause = proxy.getCause(); cause != null; cause = cause.getCause()) {
			text.append(cause.getClassName()).append(cause.getMessage());
		}
		return text.toString();
	}

	private MockHttpServletRequestBuilder dentist(Clinic clinic, MockHttpServletRequestBuilder request) {
		return request.with(as(clinic, Role.DENTIST_ADMIN));
	}

	@Test
	void noSecretReachesTheLogsOrTheErrorResponsesInAnyPhase4Flow() throws Exception {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		List<String> secrets = List.of(PATIENT_NAME, CONTENT, COMPLEMENT, SPECIALTY, REASON, CRO);
		try {
			Clinic clinic = newDentistClinic();
			String patient = createPatient(clinic, PATIENT_NAME);
			String list = "/api/v1/patients/" + patient + "/clinical-documents";

			// CRO: erro de validação e sucesso; emissão sem CRO (409)
			send(dentist(clinic, json(put("/api/v1/me/professional-registry"), "{\"cro\":\"" + CRO + "\",\"version\":0}")));
			send(dentist(clinic, json(post(list), "{\"documentType\":\"PRESCRIPTION\",\"content\":\"" + CONTENT + "\"}")));
			registerCro(clinic, CRO, "SP");

			// emissão dos três tipos e erros (400, 404, 422)
			JsonNode certificate = read(send(dentist(clinic,
					json(post(list), "{\"documentType\":\"CERTIFICATE\",\"restDays\":2,\"content\":\"" + COMPLEMENT + "\"}"))));
			JsonNode prescription = read(send(dentist(clinic, json(post(list), "{\"documentType\":\"PRESCRIPTION\",\"content\":\"" + CONTENT + "\"}"))));
			send(dentist(clinic, json(post(list), "{\"documentType\":\"REFERRAL\",\"targetSpecialty\":\"" + SPECIALTY + "\",\"content\":\"" + CONTENT + "\"}")));
			send(dentist(clinic, json(post(list), "{\"documentType\":\"PRESCRIPTION\",\"content\":\"" + CONTENT + "\",\"restDays\":3}")));
			send(dentist(clinic, json(post(list), "{\"documentType\":\"PRESCRIPTION\",\"content\":\"" + CONTENT + "\",\"issuedOn\":\"2999-01-01\"}")));
			send(dentist(clinic, json(post("/api/v1/patients/" + UUID.randomUUID() + "/clinical-documents"),
					"{\"documentType\":\"PRESCRIPTION\",\"content\":\"" + CONTENT + "\"}")));

			// leitura, lista, PDF e anulação com motivo (e os conflitos)
			String id = certificate.get("id").asString();
			send(dentist(clinic, get("/api/v1/clinical-documents/" + id)));
			send(dentist(clinic, get(list)));
			send(dentist(clinic, get("/api/v1/clinical-documents/" + id + "/pdf")));
			send(dentist(clinic, get("/api/v1/clinical-documents/" + UUID.randomUUID())));
			JsonNode voided = read(send(dentist(clinic, json(post("/api/v1/clinical-documents/" + id + "/void"),
					"{\"version\":%d,\"reason\":\"%s\"}".formatted(certificate.get("version").asLong(), REASON)))));
			send(dentist(clinic, json(post("/api/v1/clinical-documents/" + id + "/void"),
					"{\"version\":%d,\"reason\":\"%s\"}".formatted(voided.get("version").asLong(), REASON))));
			send(dentist(clinic, get("/api/v1/clinical-documents/" + id + "/pdf")));
			send(dentist(clinic, get("/api/v1/audit-log?tableName=clinical_documents&recordId=" + id)));

			// adulteração (500 integrity) lida pelo detalhe e pelo PDF
			String tampered = prescription.get("id").asString();
			DatabaseContainer.admin().execute("ALTER TABLE core.clinical_documents DISABLE TRIGGER USER");
			try {
				DatabaseContainer.admin().update("UPDATE core.clinical_documents SET sha256 = repeat('0', 64) WHERE id = ?::uuid", tampered);
			}
			finally {
				DatabaseContainer.admin().execute("ALTER TABLE core.clinical_documents ENABLE TRIGGER USER");
			}
			send(dentist(clinic, get("/api/v1/clinical-documents/" + tampered)));
			send(dentist(clinic, get("/api/v1/clinical-documents/" + tampered + "/pdf")));

			// recepção, anônimo e anonimização do paciente
			send(get("/api/v1/clinical-documents/" + id));
			send(get("/api/v1/clinical-documents/" + id).with(as(clinic, Role.RECEPTIONIST)));
			send(get("/api/v1/dashboard").with(as(clinic, Role.RECEPTIONIST)));
			send(dentist(clinic, json(patch("/api/v1/patients/" + patient + "/active"), "{\"active\":false}")));
			send(dentist(clinic, post("/api/v1/patients/" + patient + "/anonymize")));
			send(dentist(clinic, get("/api/v1/clinical-documents/" + id + "/pdf")));

			List<String> leaks = leaksIn(appender.list, secrets);
			assertThat(leaks).as("segredos nos logs").isEmpty();
			assertThat(appender.list.stream().filter(e -> e.getThrowableProxy() != null).map(e -> e.getLoggerName()).toList())
					.as("nenhum throwable nos logs dos fluxos esperados").isEmpty();
			for (String body : errorBodies) {
				for (String secret : secrets) {
					assertThat(body).as("segredo em resposta de erro").doesNotContain(secret);
				}
				for (String leak : LEAKS_IN_ERRORS) {
					assertThat(body).as("vazamento técnico em resposta de erro").doesNotContain(leak);
				}
			}
			assertThat(errorBodies).as("o percurso exercitou erros").hasSizeGreaterThan(8);
		}
		finally {
			root.detachAppender(appender);
		}
	}

	@Test
	void theDetectorFindsASentinelInjectedIntoALog() {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		try {
			LoggerFactory.getLogger("crm_data.document.mutacao").info("texto vazado: {}", CONTENT);

			assertThat(leaksIn(appender.list, List.of(CONTENT))).hasSize(1);
		}
		finally {
			root.detachAppender(appender);
		}
	}

	@Test
	void toStringOfTheRequestAndResponseTypesNeverPrintsClinicalText() {
		var request = new crm_data.document.web.DocumentRequests.CreateDocumentRequest(crm_data.document.domain.DocumentType.PRESCRIPTION, null,
				null, SPECIALTY, CONTENT, null);
		var voidRequest = new crm_data.document.web.DocumentRequests.VoidDocumentRequest(1L, REASON);

		assertThat(request.toString()).doesNotContain(CONTENT, SPECIALTY);
		assertThat(voidRequest.toString()).doesNotContain(REASON);
	}
}
