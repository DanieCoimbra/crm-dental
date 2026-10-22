package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import crm_data.shared.security.Role;
import crm_data.shared.storage.StorageException;
import crm_data.support.tenant.PatientFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * Higiene de log e de resposta: percorre os fluxos que mais tendem a vazar (CPF duplicado, falha de decifra, falha do
 * Storage, download, anonimização) com um appender no logger raiz e afirma que nenhuma linha de log, nenhum
 * {@code throwable} e nenhuma resposta de erro (400–503) contém CPF, nome do paciente, conteúdo clínico, chave do
 * objeto, nome de arquivo, URL assinada, mensagem do driver, SQL ou stack trace.
 */
class Phase1LogHygieneIT extends Phase1ItSupport {

	private static final String CPF = "52998224725";
	private static final String PATIENT_NAME = "Maria Higienizada";
	private static final String HISTORY = "SENTINELA-HISTORICO-LOG";
	private static final String NOTE = "SENTINELA-NOTA-LOG";
	private static final String FILENAME = "exame-higiene-secreto.png";

	/** Trechos que jamais podem aparecer numa resposta de erro. */
	private static final List<String> LEAKS_IN_ERRORS = List.of("Exception", "\tat ", "at crm_data", "org.postgresql", "org.hibernate",
			"org.springframework", "SQLState", "ERROR:", "Detail:", "duplicate key", "Key (", "jdbc:", "insert into", "select ",
			"update core", "violates", "Caused by");

	private final List<String> errorBodies = new ArrayList<>();

	private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = mvc.perform(request).andReturn();
		if (result.getResponse().getStatus() >= 400) {
			errorBodies.add(result.getResponse().getContentAsString());
		}
		return result;
	}

	private MvcResult sendUpload(Clinic clinic, String patient, MockMultipartFile file, Role role) throws Exception {
		MvcResult result = mvc.perform(multipart("/api/v1/patients/" + patient + "/files").file(file).param("kind", "OTHER").with(as(clinic, role)))
				.andReturn();
		if (result.getResponse().getStatus() >= 400) {
			errorBodies.add(result.getResponse().getContentAsString());
		}
		return result;
	}

	private static MockMultipartFile png(String name, int size) {
		return new MockMultipartFile("file", name, "image/png", Arrays.copyOf(PNG_HEAD, size));
	}

	@Test
	void noLogLineNorErrorResponseCarriesSensitiveData() throws Exception {
		Clinic clinic = newClinic();
		var dentist = as(clinic, Role.DENTIST_ADMIN);
		var receptionist = as(clinic, Role.RECEPTIONIST);

		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		List<String> secrets = new ArrayList<>(List.of(CPF, PATIENT_NAME, HISTORY, NOTE, FILENAME));
		try {
			// 1. CPF duplicado (a mensagem do driver repete o CPF)
			String patient = createPatient(clinic, PATIENT_NAME, CPF);
			assertThat(send(json(post("/api/v1/patients"), "{\"name\":\"Outra\",\"cpf\":\"" + CPF + "\",\"birthDate\":\"1990-01-01\",\"phone\":\"11987654321\"}")
					.with(receptionist)).getResponse().getStatus()).isEqualTo(409);
			send(json(post("/api/v1/patients"), "{\"name\":\"Inválida\",\"cpf\":\"52998224724\",\"birthDate\":\"1990-01-01\",\"phone\":\"123\"}").with(receptionist));

			// 2. prontuário, conflito de versão, papel proibido e inexistente
			saveHistory(clinic, patient, HISTORY);
			String note = addNote(clinic, patient, NOTE);
			send(json(put("/api/v1/patients/" + patient + "/notes/" + note),
					"{\"content\":\"" + NOTE + "\",\"notedAt\":\"2020-01-01T00:00:00Z\",\"version\":9}").with(dentist));
			send(get("/api/v1/patients/" + patient + "/medical-history").with(receptionist));
			send(get("/api/v1/patients/" + java.util.UUID.randomUUID()).with(dentist));
			send(get("/api/v1/patients/" + patient));

			// 3. exame: envio, lista, URL assinada, remoção; Storage fora do ar; arquivo grande demais
			String file = addFile(clinic, patient, FILENAME);
			secrets.add(clinic.id() + "/" + patient + "/" + file);
			send(get("/api/v1/patients/" + patient + "/files").with(dentist));
			MvcResult url = send(get("/api/v1/patients/" + patient + "/files/" + file + "/download-url").with(dentist));
			secrets.add(read(url).get("url").asString());
			doThrow(new StorageException("Falha ao gravar no Storage (HTTP 500).",
					new IllegalStateException("causa com https://projeto.supabase.co/" + clinic.id() + "/" + patient)))
					.when(storage).save(anyString(), any(), anyString(), anyLong());
			assertThat(sendUpload(clinic, patient, png(FILENAME, 64), Role.DENTIST_ADMIN).getResponse().getStatus()).isEqualTo(503);
			doThrow(new StorageException("Falha ao assinar no Storage (HTTP 503).", new IllegalStateException("https://projeto.supabase.co/token=abc")))
					.when(storage).createSignedUrl(anyString(), any());
			assertThat(send(get("/api/v1/patients/" + patient + "/files/" + file + "/download-url").with(dentist)).getResponse().getStatus()).isEqualTo(503);
			org.mockito.Mockito.reset(storage);
			assertThat(sendUpload(clinic, patient, png(FILENAME, 10_485_761), Role.RECEPTIONIST).getResponse().getStatus()).isEqualTo(413);
			send(delete("/api/v1/patients/" + patient + "/files/" + file).with(dentist));

			// 4. falha de decifra (texto cifrado adulterado)
			PatientFixtures.corruptHistory(clinic, patient);
			assertThat(send(get("/api/v1/patients/" + patient + "/medical-history").with(dentist)).getResponse().getStatus()).isEqualTo(500);

			// 5. anonimização: com a limpeza do Storage funcionando e falhando
			for (boolean storageFails : new boolean[] { false, true }) {
				String other = createPatient(clinic, PATIENT_NAME, null);
				String otherFile = addFile(clinic, other, FILENAME);
				secrets.add(clinic.id() + "/" + other + "/" + otherFile);
				deactivate(clinic, other);
				PatientFixtures.backdateClinicalRecords(other, 21);
				if (storageFails) {
					doThrow(new StorageException("Falha ao apagar objetos no Storage (HTTP 500).")).when(storage).deleteAll(any());
				}
				assertThat(send(post("/api/v1/patients/" + other + "/anonymize").with(dentist)).getResponse().getStatus()).isEqualTo(200);
			}
			send(post("/api/v1/patients/" + patient + "/anonymize").with(dentist)); // ativo: 409
		}
		finally {
			root.detachAppender(appender);
		}

		assertThat(appender.list).as("o appender capturou os logs dos fluxos").isNotEmpty();
		for (ILoggingEvent event : appender.list) {
			List<String> texts = new ArrayList<>();
			texts.add(event.getFormattedMessage());
			for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
				texts.add(t.getMessage());
				texts.add(t.getClassName());
			}
			for (String text : texts) {
				for (String secret : secrets) {
					assertThat(text).as("log: " + event.getLoggerName()).doesNotContain(secret);
				}
			}
		}

		assertThat(errorBodies).as("o teste gerou respostas de erro 4xx e 5xx").hasSizeGreaterThanOrEqualTo(10);
		for (String body : errorBodies) {
			for (String leak : LEAKS_IN_ERRORS) {
				assertThat(body).as("resposta de erro: " + body).doesNotContain(leak);
			}
			for (String secret : secrets) {
				assertThat(body).as("resposta de erro: " + body).doesNotContain(secret);
			}
		}
	}

	@Test
	void theEleventhUploadIs429AndItsBodyIsClean() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, PATIENT_NAME, null);
		for (int i = 0; i < 10; i++) {
			assertThat(sendUpload(clinic, patient, png(FILENAME, 64), Role.RECEPTIONIST).getResponse().getStatus()).isEqualTo(201);
		}

		MvcResult limited = sendUpload(clinic, patient, png(FILENAME, 64), Role.RECEPTIONIST);

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
		for (String leak : LEAKS_IN_ERRORS) {
			assertThat(limited.getResponse().getContentAsString()).doesNotContain(leak);
		}
		assertThat(limited.getResponse().getContentAsString()).doesNotContain(FILENAME, PATIENT_NAME);
	}
}
