package crm_data.phase3;

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
import crm_data.support.tenant.PatientFixtures;
import tools.jackson.databind.JsonNode;

/**
 * Higiene de log e de resposta da Fase 3: percorre procedimento, orçamento (com observação), envio com PDF, URL
 * assinada, reemissão, aprovação, planos, pagamento, estorno, relatórios, ficha, erros de todo tipo e anonimização com
 * valores, nome, observação, chave de objeto e URL sentinelas, e afirma que nenhuma linha de log, nenhum
 * {@code throwable} e nenhuma resposta de erro os contém (nem o {@code DETAIL} do driver, SQL ou stack trace).
 * <strong>Mutação:</strong> injetar uma sentinela num log faz o detector achá-la; sem esse controle um detector quebrado
 * passaria em silêncio.
 */
class Phase3LogHygieneIT extends Phase3ItSupport {

	private static final String PATIENT_NAME = "Paciente Higienizado Fase Tres";
	private static final String NOTES = "SENTINELA-OBSERVACAO-ORCAMENTO";
	private static final String PRICE = "1234.56";
	private static final String LINE_TOTAL = "2469.12";
	private static final String DISCOUNT = "77.77";
	private static final String PAYMENT = "321.09";

	private static final List<String> LEAKS_IN_ERRORS = List.of("Exception", "\tat ", "at crm_data", "org.postgresql", "org.hibernate",
			"org.springframework", "SQLState", "ERROR:", "Detail:", "DETAIL", "Key (", "jdbc:", "insert into", "select ", "update core",
			"violates", "Caused by", "ux_inst_budget_number", "ux_pay_reverses", "ck_budgets_total");

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
			if (event.getThrowableProxy() != null) {
				leaks.add("throwable em " + event.getLoggerName() + ": " + event.getThrowableProxy().getClassName());
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

	private MockHttpServletRequestBuilder dentist(Kit kit, MockHttpServletRequestBuilder request) {
		return request.with(as(kit.clinic(), Role.DENTIST_ADMIN));
	}

	@Test
	void noSecretReachesTheLogsOrTheErrorResponsesInAnyPhase3Flow() throws Exception {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		List<String> secrets = new ArrayList<>(List.of(PATIENT_NAME, NOTES, PRICE, LINE_TOTAL, DISCOUNT, PAYMENT));
		try {
			Setup setup = openClinic();
			String patient = createPatient(setup.clinic(), PATIENT_NAME);
			Kit kit = kitOf(setup.clinic(), patient, createProcedure(setup.clinic(), "Procedimento Higiene", PRICE, 45),
					createProcedure(setup.clinic(), "Outro Procedimento", "100.00", 30));

			// rascunho com observação e desconto; erros de validação e de regra (400, 404, 409, 422)
			String body = "{\"patientId\":\"%s\",\"notes\":\"%s\",\"discountAmount\":\"%s\",\"items\":[%s]}".formatted(patient, NOTES, DISCOUNT, item(kit.restoration(), 2, null));
			JsonNode draft = read(send(dentist(kit, json(post("/api/v1/budgets"), body))));
			String id = draft.get("id").asString();
			send(dentist(kit, json(post("/api/v1/budgets"), body.replace("1234.56", "1e3").replace(kit.restoration(), UUID.randomUUID().toString()))));
			send(dentist(kit, json(post("/api/v1/budgets"), "{\"patientId\":\"%s\",\"notes\":\"%s\",\"items\":[]}".formatted(patient, NOTES))));
			send(dentist(kit, json(put("/api/v1/budgets/" + id), "{\"version\":99,\"items\":[%s],\"notes\":\"%s\"}".formatted(item(kit.restoration(), 1, null), NOTES))));
			send(dentist(kit, json(post("/api/v1/budgets/" + id + "/approve"), "{\"version\":0}")));
			send(dentist(kit, get("/api/v1/budgets/" + UUID.randomUUID())));

			// envio com PDF, URL assinada, reemissão, aprovação
			JsonNode sent = read(send(dentist(kit, json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(draft.get("version").asLong())))));
			send(dentist(kit, json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(draft.get("version").asLong()))));
			JsonNode created = read(send(dentist(kit, post("/api/v1/budgets/" + id + "/documents"))));
			String documentId = sent.get("documents").get(0).get("id").asString();
			JsonNode url = read(send(dentist(kit, get("/api/v1/budgets/" + id + "/documents/" + documentId + "/download-url"))));
			secrets.add(url.get("url").asString());
			DatabaseContainer.admin().queryForList("SELECT storage_key FROM core.budget_documents WHERE budget_id = ?::uuid", String.class, id).forEach(secrets::add);
			assertThat(created.get("revision").asInt()).isEqualTo(2);
			send(dentist(kit, json(post("/api/v1/budgets/" + id + "/approve"), "{\"version\":%d}".formatted(sent.get("version").asLong()))));

			// plano, pagamento, estorno e erros do financeiro
			send(dentist(kit, json(post("/api/v1/budgets/" + id + "/installments"), "{\"mode\":\"MANUAL\",\"installments\":[{\"amount\":\"5.00\",\"dueDate\":\"2027-03-01\"}]}")));
			JsonNode plan = read(send(dentist(kit, json(post("/api/v1/budgets/" + id + "/installments"), "{\"mode\":\"AUTO\",\"count\":2,\"firstDueDate\":\"2027-03-01\"}"))));
			send(dentist(kit, json(post("/api/v1/budgets/" + id + "/installments"), "{\"mode\":\"AUTO\",\"count\":2,\"firstDueDate\":\"2027-03-01\"}")));
			String installment = plan.get("installments").get(0).get("id").asString();
			JsonNode paid = read(send(dentist(kit, json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"%s\",\"method\":\"PIX\"}".formatted(PAYMENT)))));
			send(dentist(kit, json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"999999.99\",\"method\":\"PIX\"}")));
			send(dentist(kit, json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"1.00\",\"method\":\"PIX\",\"paidOn\":\"2999-01-01\"}")));
			send(dentist(kit, json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"-3\",\"method\":\"BITCOIN\"}")));
			send(dentist(kit, json(patch("/api/v1/installments/" + installment + "/due-date"), "{\"dueDate\":\"2027-06-01\",\"version\":0}")));
			String paymentId = paid.get("payment").get("id").asString();
			send(dentist(kit, post("/api/v1/payments/" + paymentId + "/reversal")));
			send(dentist(kit, post("/api/v1/payments/" + paymentId + "/reversal")));
			send(dentist(kit, post("/api/v1/payments/" + UUID.randomUUID() + "/reversal")));

			// relatórios e ficha
			send(dentist(kit, get("/api/v1/finance/receivables?from=2027-01-01&to=2027-12-31")));
			send(dentist(kit, get("/api/v1/finance/overdue")));
			send(dentist(kit, get("/api/v1/finance/cash-flow?from=2026-01-01&to=2027-12-31")));
			send(dentist(kit, get("/api/v1/finance/revenue?from=2026-01-01&to=2026-12-31")));
			send(dentist(kit, get("/api/v1/finance/revenue?from=2026-01-01&to=2028-12-31")));
			send(dentist(kit, get("/api/v1/patients/" + patient + "/financial")));
			send(dentist(kit, get("/api/v1/audit-log?tableName=payments")));
			send(dentist(kit, get("/api/v1/audit-log?tableName=budget_items")));

			// anonimização: bloqueada pela parcela em aberto (CR006) e depois concluída
			adminSql(kit.clinic(), "UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = '%s'".formatted(patient));
			PatientFixtures.backdateClinicalRecords(patient, 21);
			send(dentist(kit, post("/api/v1/patients/" + patient + "/anonymize")));
			String second = plan.get("installments").get(1).get("id").asString();
			for (String target : List.of(installment, second)) {
				long open = DatabaseContainer.admin().queryForObject("SELECT (amount - coalesce((SELECT sum(CASE WHEN kind = 'PAYMENT' THEN amount ELSE -amount END) FROM core.payments WHERE installment_id = i.id), 0)) * 100 FROM core.installments i WHERE id = ?::uuid",
						Long.class, target);
				send(dentist(kit, json(post("/api/v1/installments/" + target + "/payments"), "{\"amount\":\"%s\",\"method\":\"CASH\"}".formatted("%d.%02d".formatted(open / 100, open % 100)))));
			}
			send(dentist(kit, post("/api/v1/patients/" + patient + "/anonymize")));
			send(dentist(kit, post("/api/v1/patients/" + patient + "/anonymize")));
		}
		finally {
			root.detachAppender(appender);
		}

		assertThat(appender.list).as("o fluxo gerou logs (o detector não olha uma lista vazia)").isNotEmpty();
		assertThat(leaksIn(appender.list, secrets)).as("nenhuma sentinela, chave ou URL em log; nenhum throwable").isEmpty();
		assertThat(errorBodies).as("o fluxo exercitou respostas de erro").hasSizeGreaterThan(15);
		for (String errorBody : errorBodies) {
			for (String secret : secrets) {
				assertThat(errorBody).as("resposta de erro sem sentinela").doesNotContain(secret);
			}
			for (String leak : LEAKS_IN_ERRORS) {
				assertThat(errorBody).as("resposta de erro sem detalhe interno").doesNotContain(leak);
			}
		}
	}

	@Test
	void theDetectorSeesALeakInjectedIntoALog() {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		try {
			LoggerFactory.getLogger("crm_data.finance.audit").info("Pagamento registrado: valor={} paciente={}", PAYMENT, PATIENT_NAME);
			LoggerFactory.getLogger("crm_data.commercial.audit").warn("falha", new IllegalStateException("detalhe interno"));
		}
		finally {
			root.detachAppender(appender);
		}

		List<String> leaks = leaksIn(appender.list, List.of(PATIENT_NAME, NOTES, PRICE, LINE_TOTAL, DISCOUNT, PAYMENT));

		assertThat(leaks).as("a mutação (valor e nome num log, throwable em outro) é detectada").hasSize(3);
	}
}
