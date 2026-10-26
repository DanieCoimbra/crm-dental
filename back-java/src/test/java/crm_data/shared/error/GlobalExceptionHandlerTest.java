package crm_data.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import crm_data.support.PlatformWebTest;

@PlatformWebTest
class GlobalExceptionHandlerTest {

	private static final String PROBLEM = "application/problem+json";

	@Autowired
	MockMvc mvc;

	@ParameterizedTest
	@CsvSource({ "NOT_FOUND,404,not-found", "CONFLICT,409,conflict", "BUSINESS_RULE,422,business-rule",
			"FORBIDDEN,403,forbidden", "UNAUTHORIZED,401,unauthorized", "UNAVAILABLE,503,unavailable" })
	void businessExceptionUsesTypeStatusAndSlug(String type, int status, String slug) throws Exception {
		mvc.perform(get("/probe/business/" + type).with(user("u")))
				.andExpect(status().is(status))
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/" + slug))
				.andExpect(jsonPath("$.status").value(status))
				.andExpect(jsonPath("$.detail").value("mensagem de negócio"))
				.andExpect(jsonPath("$.instance").value("/probe/business/" + type));
	}

	@Test
	void rateLimitedReturns429WithRetryAfter() throws Exception {
		mvc.perform(get("/probe/rate-limited").with(user("u")))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "30"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/rate-limited"));
	}

	@Test
	void beanValidationListsEveryInvalidField() throws Exception {
		mvc.perform(post("/probe/validate").with(user("u")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"\",\"password\":\"curta\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"))
				.andExpect(jsonPath("$.errors.length()").value(2))
				.andExpect(jsonPath("$.errors[?(@.field=='name')].message").value("obrigatório"))
				.andExpect(jsonPath("$.errors[?(@.field=='password')].message").value("mínimo de 10 caracteres"));
	}

	@Test
	void malformedJsonReturns400ValidationWithoutParserDetails() throws Exception {
		mvc.perform(post("/probe/validate").with(user("u")).contentType(MediaType.APPLICATION_JSON).content("{nome:"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"))
				.andExpect(jsonPath("$.errors").doesNotExist())
				.andExpect(content().string(not(containsString("Unexpected"))))
				.andExpect(content().string(not(containsString("jackson"))));
	}

	@Test
	void typeMismatchReturns400ValidationWithField() throws Exception {
		mvc.perform(get("/probe/number").param("n", "abc").with(user("u")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/validation"))
				.andExpect(jsonPath("$.errors[0].field").value("n"));
	}

	@Test
	void unknownRouteReturns404Problem() throws Exception {
		mvc.perform(get("/nao/existe").with(user("u")))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/not-found"))
				.andExpect(jsonPath("$.instance").value("/nao/existe"));
	}

	@Test
	void unsupportedMethodReturns405ProblemKeepingAllowHeader() throws Exception {
		mvc.perform(post("/probe/ok").with(user("u")))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().exists("Allow"))
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.status").value(405));
	}

	@Test
	void unexpectedExceptionReturns500WithoutInternalDetails() throws Exception {
		MvcResult result = mvc.perform(get("/probe/boom").with(user("u")))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/internal"))
				.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("segredo interno", "host-interno", "IllegalStateException", "at crm_data");
	}

	@Test
	void cryptoFailureReturns500WithoutDetails() throws Exception {
		mvc.perform(get("/probe/crypto").with(user("u")))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/internal"))
				.andExpect(content().string(not(containsString("adulterado"))));
	}

	@Test
	void methodSecurityDenialReturns403NotInternalError() throws Exception {
		mvc.perform(get("/probe/admin").with(user("u").roles("USER")))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/forbidden"));
	}

	@Test
	void optimisticLockReturns409Conflict() throws Exception {
		MvcResult result = mvc.perform(get("/probe/locking").with(user("u")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/conflict"))
				.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("Foo");
	}

	@Test
	void databaseFailureReturns503WithoutInternalDetails() throws Exception {
		MvcResult result = mvc.perform(get("/probe/db-down").with(user("u")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unavailable"))
				.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("host-interno", "5432");
	}

	@Test
	void errorsAreProblemJsonEvenWhenClientAcceptsOnlyJson() throws Exception {
		mvc.perform(get("/probe/business/CONFLICT").accept(MediaType.APPLICATION_JSON).with(user("u")))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM));
	}

	@Test
	void unauthenticatedRequestReturns401Problem() throws Exception {
		mvc.perform(get("/probe/ok"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unauthorized"))
				.andExpect(jsonPath("$.instance").value("/probe/ok"));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "CR001|409|conflict|Inative o paciente antes de anonimizar.",
			"CR002|409|conflict|Retenção legal do prontuário vigente. Liberado em 2046-10-22.",
			"CR002-NODATE|409|conflict|Retenção legal do prontuário vigente.",
			"CR003|409|conflict|Paciente anonimizado.", "P0002|404|not-found|Paciente não encontrado.",
			"CR004|409|conflict|Cancele os agendamentos futuros antes de anonimizar.",
			"CR005|409|conflict|Paciente inativo.", "23P01|409|conflict|Horário já ocupado.",
			"40P01|409|conflict|O recurso foi alterado por outra operação. Recarregue e tente novamente.",
			"40001|409|conflict|O recurso foi alterado por outra operação. Recarregue e tente novamente.",
			"23505-BH|409|conflict|Dia já configurado.", "23505-HOLIDAY|409|conflict|Já existe feriado nesta data.",
			"CR006|409|conflict|Quite ou cancele as parcelas em aberto antes de anonimizar.",
			"CR007|409|conflict|Orçamento já enviado: não pode mais ser alterado.",
			"CR008|409|conflict|Operação inválida para o status do orçamento.",
			"CR009|409|conflict|Operação inválida para o estado da parcela.",
			"CR010|409|conflict|Pagamento inválido: valor acima do saldo ou parcela cancelada.",
			"CR011|409|conflict|As parcelas não somam o total do orçamento.",
			"23505-PTYPE|409|conflict|Já existe um procedimento ativo com este nome.",
			"23505-REVERSE|409|conflict|Este pagamento já foi estornado.",
			"23505-PLAN|409|conflict|O plano de parcelas foi alterado. Recarregue.",
			"23505-NUM|500|internal|", "23514-OTHER|500|internal|",
			"42501|403|forbidden|Você não tem permissão para esta operação.",
			"28000|500|internal|", "23514|500|internal|", "23503|500|internal|", "23505|500|internal|" })
	void databaseErrorsAreTranslatedBySqlState(String state, int status, String slug, String detail) throws Exception {
		MvcResult result = mvc.perform(get("/probe/db-error/" + state).with(user("u")))
				.andExpect(status().is(status))
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/" + slug))
				.andReturn();

		String body = result.getResponse().getContentAsString();
		if (detail != null) {
			assertThat(body).contains(detail);
		}
		assertThat(body).doesNotContain("52998224725", "SQLException", "constraint");
	}

	@ParameterizedTest
	@CsvSource({ "23514-ITEM", "23514-TOTAL" })
	void amountCeilingChecksBecomeABusinessRuleWithAStableReason(String state) throws Exception {
		MvcResult result = mvc.perform(get("/probe/db-error/" + state).with(user("u")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/business-rule"))
				.andExpect(jsonPath("$.reason").value("AMOUNT_OUT_OF_RANGE"))
				.andExpect(jsonPath("$.detail").value("Valores do orçamento fora do limite."))
				.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("ck_", "1000000", "-5.00", "constraint");
	}

	@Test
	void commitTimeCr011ComesAsTransactionSystemExceptionAndBecomes409NotInternalError() throws Exception {
		MvcResult result = mvc.perform(get("/probe/commit-error/CR011").with(user("u")))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/conflict"))
				.andExpect(jsonPath("$.detail").value("As parcelas não somam o total do orçamento."))
				.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("52998224725", "799.99", "800.00");
	}

	@Test
	void commitTimeErrorWithoutAKnownSqlStateIs500WithoutTheDriverMessage() throws Exception {
		MvcResult result = mvc.perform(get("/probe/commit-error/XX000").with(user("u")))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/internal"))
				.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("52998224725", "SQLException", "parcelas");
	}

	@Test
	void commitFailureWithoutASqlCauseKeepsTheFullStackInTheLog() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			mvc.perform(get("/probe/commit-error-no-sql").with(user("u"))).andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.type").value("https://crm.local/problems/internal"));
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).hasSize(1);
		assertThat(appender.list.get(0).getThrowableProxy()).as("a stack completa serve ao diagnóstico").isNotNull();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "{\"amount\":\"1234.50\"}|200", "{\"amount\":\"0\"}|200", "{\"amount\":1234.5}|400",
			"{\"amount\":1234}|400", "{\"amount\":1e3}|400", "{\"amount\":\"1e3\"}|400", "{\"amount\":\"-1\"}|400",
			"{\"amount\":\"1,5\"}|400", "{\"amount\":\"1.234\"}|400", "{\"amount\":\"1000000.00\"}|400", "{\"amount\":null}|400",
			"{}|400" })
	void aJsonNumberInAMoneyFieldIsRejectedJustLikeAMalformedText(String body, int status) throws Exception {
		MvcResult result = mvc.perform(post("/probe/money").with(user("u")).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().is(status)).andReturn();
		if (status == 200) {
			assertThat(result.getResponse().getContentAsString()).matches("\\d+\\.\\d{2}");
		}
	}

	@Test
	void unexpectedCheckViolationLogsOnlyTheConstraintNameNeverTheRowValues() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			mvc.perform(get("/probe/db-error/23514-OTHER").with(user("u"))).andExpect(status().isInternalServerError());
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).hasSize(1);
		ILoggingEvent event = appender.list.get(0);
		assertThat(event.getThrowableProxy()).isNull();
		assertThat(event.getFormattedMessage()).contains("sqlState=23514", "constraint=ck_budgets_notes")
				.doesNotContain("52998224725");
	}

	@Test
	void businessRuleWithReasonExposesTheStableCode() throws Exception {
		mvc.perform(get("/probe/business-reason").with(user("u")))
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/business-rule"))
				.andExpect(jsonPath("$.reason").value("OUTSIDE_HOURS"));
	}

	@Test
	void businessRuleWithoutReasonHasNoReasonProperty() throws Exception {
		mvc.perform(get("/probe/business/CONFLICT").with(user("u")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.reason").doesNotExist());
	}

	@Test
	void databaseErrorLogCarriesOnlyClassSqlStateAndConstraintNeverTheCpf() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			mvc.perform(get("/probe/db-error/23505").with(user("u"))).andExpect(status().isInternalServerError());
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).hasSize(1);
		ILoggingEvent event = appender.list.get(0);
		assertThat(event.getThrowableProxy()).isNull();
		assertThat(event.getFormattedMessage()).contains("sqlState=23505", "constraint=ux_patients_clinic_cpf")
				.doesNotContain("52998224725");
	}

	@Test
	void unexpectedWrapperAroundASqlErrorLogsOnlyClassAndSqlStateNeverTheCauseNorTheCpf() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			mvc.perform(get("/probe/wrapped-sql").with(user("u"))).andExpect(status().isInternalServerError());
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).hasSize(1);
		ILoggingEvent event = appender.list.get(0);
		assertThat(event.getThrowableProxy()).as("sem stack nem causas").isNull();
		assertThat(event.getFormattedMessage()).contains("java.lang.IllegalStateException", "sqlState=23505")
				.doesNotContain("52998224725");
	}

	@Test
	void unexpectedErrorWithoutSqlCauseStillLogsTheFullStackForDiagnosis() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			mvc.perform(get("/probe/boom").with(user("u"))).andExpect(status().isInternalServerError());
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).hasSize(1);
		assertThat(appender.list.get(0).getThrowableProxy()).isNotNull();
	}

	@Test
	void clinicalRoutesGetExactlyNoStoreThroughTheRealSecurityChain() throws Exception {
		mvc.perform(get("/api/v1/patients/probe").with(user("u")))
				.andExpect(status().isOk())
				.andExpect(header().stringValues("Cache-Control", "no-store"));
	}

	@Test
	void storageFailureIs503AndTheLogCarriesTheSafeMessageButNeverTheCause() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		String body;
		try {
			body = mvc.perform(get("/probe/storage-down").with(user("u")))
					.andExpect(status().isServiceUnavailable())
					.andExpect(content().contentTypeCompatibleWith(PROBLEM))
					.andExpect(jsonPath("$.type").value("https://crm.local/problems/unavailable"))
					.andReturn().getResponse().getContentAsString();
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(body).doesNotContain("HTTP 401", "supabase");
		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getFormattedMessage()).contains("Falha ao gravar no Storage (HTTP 401).", "/probe/storage-down");
			assertThat(event.getFormattedMessage()).doesNotContain("supabase", "segredo");
			assertThat(event.getThrowableProxy()).isNull();
		});
	}
}
