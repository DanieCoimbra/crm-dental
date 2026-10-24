package crm_data.phase2;

import static crm_data.phase2.AgendaDates.at;
import static crm_data.phase2.AgendaDates.mondays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import crm_data.shared.security.Role;
import tools.jackson.databind.JsonNode;

/**
 * Higiene de log e de resposta da agenda: percorre criação, conflito, regras, validação, remarcação, transições,
 * inativação com cancelamento automático, nota vinculada, histórico, expediente e feriados com observação, nome e
 * telefone sentinela, e afirma que nenhuma linha de log, nenhum {@code throwable} e nenhuma resposta de erro contém essas
 * sentinelas, o {@code DETAIL} do driver, SQL ou stack trace. Os controles provam que o detector enxerga um vazamento
 * (mutação: injetar a sentinela num log faz a verificação falhar).
 */
class SchedulingLogHygieneIT extends Phase2ItSupport {

	private static final String NOTES = "SENTINELA-OBSERVACAO-AGENDA";
	private static final String NAME = "Paciente Higienizada Agenda";
	private static final String PHONE = "11955550123";
	private static final String NOTE_TEXT = "SENTINELA-NOTA-CLINICA";
	private static final String CPF = "52998224725";
	private static final List<String> SECRETS = List.of(NOTES, NAME, PHONE, NOTE_TEXT, CPF);

	private static final List<String> LEAKS_IN_ERRORS = List.of("Exception", "\tat ", "at crm_data", "org.postgresql", "org.hibernate",
			"org.springframework", "SQLState", "ERROR:", "Detail:", "DETAIL", "Key (", "jdbc:", "insert into", "select ", "update core",
			"violates", "Caused by", "ex_appt_no_overlap", "ux_bh_clinic_weekday");

	private final List<String> errorBodies = new ArrayList<>();

	private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = mvc.perform(request).andReturn();
		if (result.getResponse().getStatus() >= 400) {
			errorBodies.add(result.getResponse().getContentAsString());
		}
		return result;
	}

	private static List<String> leaksIn(List<ILoggingEvent> events) {
		List<String> leaks = new ArrayList<>();
		for (ILoggingEvent event : events) {
			String line = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : stack(event.getThrowableProxy()));
			for (String secret : SECRETS) {
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

	private JsonNode created(Setup setup, String start, String end, String extra) throws Exception {
		return read(send(json(post("/api/v1/appointments"), "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"%s}"
				.formatted(setup.patient(), start, end, extra)).with(as(setup.clinic(), Role.RECEPTIONIST))));
	}

	@Test
	void noSecretReachesTheLogsOrTheErrorResponsesInAnyAgendaFlow() throws Exception {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		try {
			Setup setup = openClinic();
			// o paciente do cenário tem nome e telefone sentinela
			String patient = read(send(json(post("/api/v1/patients"), "{\"name\":\"%s\",\"cpf\":\"%s\",\"birthDate\":\"1988-04-12\",\"phone\":\"%s\"}".formatted(NAME, CPF, PHONE))
					.with(as(setup.clinic(), Role.RECEPTIONIST)))).get("id").asString();
			Setup sentinel = new Setup(setup.clinic(), patient);
			List<LocalDate> mondays = mondays(3);
			LocalDate monday = mondays.get(0);
			LocalDate sunday = monday.plusDays(6);
			String notes = ",\"notes\":\"" + NOTES + "\"";

			// criar, conflito (409), regra (422), validação (400), paciente inexistente (404)
			JsonNode first = created(sentinel, at(monday, 9, 0), at(monday, 10, 0), notes);
			created(sentinel, at(monday, 9, 30), at(monday, 10, 30), notes);
			created(sentinel, at(sunday, 9, 0), at(sunday, 10, 0), notes);
			created(sentinel, at(monday, 9, 0), at(monday, 9, 7), notes);
			send(json(post("/api/v1/appointments"), "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"%s}".formatted(java.util.UUID.randomUUID(),
					at(monday, 14, 0), at(monday, 15, 0), notes)).with(as(setup.clinic(), Role.RECEPTIONIST)));
			// o dentista encaixa (gera o log de encaixe) e remarca
			send(json(post("/api/v1/appointments"), "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"allowOutsideHours\":true%s}"
					.formatted(patient, at(sunday, 9, 0), at(sunday, 10, 0), notes)).with(as(setup.clinic(), Role.DENTIST_ADMIN)));
			send(json(put("/api/v1/appointments/" + first.get("id").asString()), "{\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"version\":0%s}"
					.formatted(at(monday, 14, 0), at(monday, 15, 0), notes)).with(as(setup.clinic(), Role.RECEPTIONIST)));
			send(json(put("/api/v1/appointments/" + first.get("id").asString()), "{\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"version\":99%s}"
					.formatted(at(monday, 14, 0), at(monday, 15, 0), notes)).with(as(setup.clinic(), Role.RECEPTIONIST)));
			// transições, inclusive as proibidas
			JsonNode second = created(sentinel, at(mondays.get(1), 9, 0), at(mondays.get(1), 10, 0), notes);
			for (String route : new String[] { "confirm", "confirm", "start", "cancel", "reactivate" }) {
				send(json(post("/api/v1/appointments/" + second.get("id").asString() + "/" + route), "{\"version\":0}")
						.with(as(setup.clinic(), route.equals("cancel") || route.equals("confirm") ? Role.RECEPTIONIST : Role.DENTIST_ADMIN)));
			}
			// leituras
			send(get("/api/v1/appointments").param("from", monday.toString()).param("to", monday.plusDays(6).toString()).with(as(setup.clinic(), Role.RECEPTIONIST)));
			send(get("/api/v1/patients/" + patient + "/appointments").with(as(setup.clinic(), Role.RECEPTIONIST)));
			send(get("/api/v1/appointments/" + java.util.UUID.randomUUID()).with(as(setup.clinic(), Role.RECEPTIONIST)));
			// expediente (com o aviso de impacto, que carrega nome e telefone na RESPOSTA, nunca no log) e feriados
			send(json(put("/api/v1/business-hours").param("dryRun", "true"), "{\"days\":[{\"weekday\":1,\"isOpen\":false,\"version\":0}]}")
					.with(as(setup.clinic(), Role.DENTIST_ADMIN)));
			send(json(put("/api/v1/business-hours"), "{\"days\":[{\"weekday\":1,\"isOpen\":false,\"version\":99}]}").with(as(setup.clinic(), Role.DENTIST_ADMIN)));
			send(json(post("/api/v1/clinic-holidays"), "{\"date\":\"%s\",\"name\":\"Recesso\"}".formatted(monday)).with(as(setup.clinic(), Role.DENTIST_ADMIN)));
			send(json(post("/api/v1/clinic-holidays"), "{\"date\":\"%s\",\"name\":\"Recesso\"}".formatted(monday)).with(as(setup.clinic(), Role.DENTIST_ADMIN)));
			// nota vinculada e disputa, e a inativação com cancelamento automático
			send(json(post("/api/v1/patients/" + patient + "/notes"), "{\"content\":\"%s\",\"appointmentId\":\"%s\"}".formatted(NOTE_TEXT, second.get("id").asString()))
					.with(as(setup.clinic(), Role.DENTIST_ADMIN)));
			send(json(post("/api/v1/patients/" + patient + "/notes"), "{\"content\":\"%s\",\"appointmentId\":\"%s\"}".formatted(NOTE_TEXT, second.get("id").asString()))
					.with(as(setup.clinic(), Role.DENTIST_ADMIN)));
			send(json(patch("/api/v1/patients/" + patient + "/active"), "{\"active\":false}").with(as(setup.clinic(), Role.DENTIST_ADMIN)));
			send(json(patch("/api/v1/patients/" + patient + "/active"), "{\"active\":false}").with(as(setup.clinic(), Role.DENTIST_ADMIN)));
			send(post("/api/v1/patients/" + patient + "/anonymize").with(as(setup.clinic(), Role.DENTIST_ADMIN)));

			assertThat(appender.list).as("o detector enxerga logs de verdade").anyMatch(e -> e.getFormattedMessage().startsWith("Agendamento criado"));
			assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().startsWith("Encaixe fora do expediente"));
			assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().startsWith("Agendamentos cancelados pela inativação"));
			assertThat(leaksIn(appender.list)).as("segredos ou stack traces no log").isEmpty();
			assertThat(errorBodies).as("houve respostas de erro para conferir").isNotEmpty();
			assertThat(errorBodies).allSatisfy(body -> {
				assertThat(body).as("corpo de erro").doesNotContain(SECRETS);
				for (String leak : LEAKS_IN_ERRORS) {
					assertThat(body).as("corpo de erro não deve conter '%s'", leak).doesNotContain(leak);
				}
			});
		}
		finally {
			root.detachAppender(appender);
		}
	}

	// ---- controles: o detector não é cego (validação por mutação) ----

	@Test
	void controlAnInjectedSecretInALogLineIsDetected() {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		try {
			LoggerFactory.getLogger("crm_data.scheduling.audit").info("Agendamento criado com observação {}", NOTES);
			LoggerFactory.getLogger("crm_data.scheduling.audit").info("Telefone {}", PHONE);
			LoggerFactory.getLogger("crm_data.patient").info("Paciente {} cpf {}", NAME, CPF);

			assertThat(leaksIn(appender.list)).as("observação, telefone, nome e CPF").hasSize(4);
		}
		finally {
			root.detachAppender(appender);
		}
	}

	@Test
	void controlAnExceptionAttachedToALogLineIsDetectedAsALeak() {
		Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		try {
			LoggerFactory.getLogger("crm_data.scheduling").error("falha", new IllegalStateException("detalhe com " + NAME));

			assertThat(leaksIn(appender.list)).isNotEmpty();
		}
		finally {
			root.detachAppender(appender);
		}
	}
}
