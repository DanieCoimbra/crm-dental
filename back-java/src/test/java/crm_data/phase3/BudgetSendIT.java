package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import crm_data.commercial.service.DocumentSlots;
import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.security.Role;
import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.StorageException;
import crm_data.support.DatabaseContainer;
import crm_data.support.FaultyDocumentStorageConfig;
import crm_data.support.FaultyFileStorage;
import tools.jackson.databind.JsonNode;

/**
 * F04 contra o PostgreSQL real (V001–V029) e um Storage com falhas injetadas: o envio é tudo-ou-nada (ou o orçamento fica
 * {@code SENT} com o PDF registrado e o objeto no Storage, ou continua {@code DRAFT} sem linha e sem objeto órfão), em
 * falha do Storage, do registro do metadado, do commit e da própria compensação; revisões, download, concorrência,
 * limites por usuário e por instância, papéis e isolamento.
 */
@Import(FaultyDocumentStorageConfig.class)
@SuppressWarnings("deprecation") // OpenPDF 2.x: ver BudgetPdfRenderer
class BudgetSendIT extends Phase3ItSupport {

	@Autowired
	@Qualifier("documentStorage")
	FileStorage documentStorage;
	@Autowired
	DocumentSlots slots;

	private FaultyFileStorage storage;

	@BeforeEach
	void resetFaults() {
		storage = (FaultyFileStorage) documentStorage;
		storage.reset();
	}

	@AfterEach
	void dropFaultTriggers() {
		var admin = DatabaseContainer.admin();
		admin.execute("DROP TRIGGER IF EXISTS itest_fail ON core.budget_documents");
		admin.execute("DROP TRIGGER IF EXISTS itest_commit_fail ON core.budget_documents");
		admin.execute("DROP FUNCTION IF EXISTS core.itest_fail()");
	}

	// ---- auxiliares ----

	private MockHttpServletRequestBuilder send(Kit kit, Role role, String id, long version) {
		return json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(version)).with(as(kit.clinic(), role));
	}

	private MockHttpServletRequestBuilder regenerate(Kit kit, Role role, String id) {
		return post("/api/v1/budgets/" + id + "/documents").with(as(kit.clinic(), role));
	}

	/** Orçamento em rascunho de 1 item (R$ 250,00 × 2 − R$ 50,00 = R$ 450,00). */
	private JsonNode draft(Kit kit) throws Exception {
		return createBudget(kit, "50.00", item(kit.restoration(), 2, null));
	}

	private JsonNode sent(Kit kit) throws Exception {
		JsonNode draft = draft(kit);
		return read(mvc.perform(send(kit, Role.DENTIST_ADMIN, draft.get("id").asString(), draft.get("version").asLong())).andExpect(status().isOk()).andReturn());
	}

	private long documentRows(String budgetId) {
		return DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.budget_documents WHERE budget_id = ?::uuid", Long.class, budgetId);
	}

	private Map<String, Object> documentRow(String budgetId, int revision) {
		return DatabaseContainer.admin().queryForMap(
				"SELECT storage_key, size_bytes, sha256, revision, content_type FROM core.budget_documents WHERE budget_id = ?::uuid AND revision = ?",
				budgetId, revision);
	}

	private byte[] stored(String key) throws Exception {
		try (InputStream in = documentStorage.load(key)) {
			return in.readAllBytes();
		}
	}

	private static String pdfText(byte[] pdf) throws Exception {
		PdfReader reader = new PdfReader(pdf);
		try {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			StringBuilder all = new StringBuilder();
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				all.append(extractor.getTextFromPage(page)).append('\n');
			}
			return all.toString().replaceAll("[\\u00a0 ]+", " ");
		}
		finally {
			reader.close();
		}
	}

	private void createFault(String sql) {
		DatabaseContainer.admin().execute("CREATE FUNCTION core.itest_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha de teste'; END $$");
		DatabaseContainer.admin().execute(sql);
	}

	// ---- caminho feliz ----

	@Test
	void sendMakesTheBudgetSentAndStoresAPdfWhoseBytesHashAndTextMatchTheDatabase() throws Exception {
		Kit kit = kit();
		JsonNode draft = draft(kit);
		String id = draft.get("id").asString();

		JsonNode sent = read(mvc.perform(send(kit, Role.DENTIST_ADMIN, id, draft.get("version").asLong())).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andReturn());

		assertThat(sent.get("status").asString()).isEqualTo("SENT");
		assertThat(sent.get("sentAt").isNull()).isFalse();
		assertThat(sent.get("version").asLong()).isGreaterThan(draft.get("version").asLong());
		assertThat(sent.get("documents")).hasSize(1);
		JsonNode doc = sent.get("documents").get(0);
		assertThat(doc.get("revision").asInt()).isEqualTo(1);
		assertThat(doc.propertyNames()).containsExactlyInAnyOrder("id", "revision", "generatedAt", "sizeBytes");
		Map<String, Object> row = documentRow(id, 1);
		String key = (String) row.get("storage_key");
		byte[] object = stored(key);
		assertThat(object.length).isEqualTo(((Number) row.get("size_bytes")).intValue());
		assertThat(doc.get("sizeBytes").asLong()).isEqualTo(object.length);
		assertThat((String) row.get("sha256")).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(object)));
		assertThat(row.get("content_type")).isEqualTo("application/pdf");
		assertThat(key).matches(kit.clinic().id() + "/" + kit.patient() + "/budgets/[0-9a-f-]{36}");
		String text = pdfText(object);
		assertThat(text).contains("Orçamento nº 1", "Paciente: Maria Souza", "Restauração", "R$ 500,00", "TOTAL", "R$ 450,00");
		assertThat(text).doesNotContain("Revisão");
		assertThat(sent.toString()).as("a chave do objeto nunca sai pela API").doesNotContain(key).doesNotContain((String) row.get("sha256"));
		assertThat(storage.saved).containsExactly(key);
		assertThat(storage.deleted).isEmpty();
		assertThat(slots.available()).isEqualTo(3);
	}

	@Test
	void theReceptionistAndTheDentistListTheRevisionsAndGetADownloadUrl() throws Exception {
		Kit kit = kit();
		JsonNode sent = sent(kit);
		String id = sent.get("id").asString();
		String documentId = sent.get("documents").get(0).get("id").asString();

		for (Role role : Role.values()) {
			JsonNode list = read(mvc.perform(get("/api/v1/budgets/" + id + "/documents").with(as(kit.clinic(), role))).andExpect(status().isOk())
					.andExpect(header().string("Cache-Control", "no-store")).andReturn());
			assertThat(list).hasSize(1);
			JsonNode url = read(mvc.perform(get("/api/v1/budgets/" + id + "/documents/" + documentId + "/download-url").with(as(kit.clinic(), role)))
					.andExpect(status().isOk()).andReturn());
			assertThat(url.get("url").asString()).startsWith("local://");
			assertThat(url.get("expiresInSeconds").asLong()).isEqualTo(60);
		}
	}

	@Test
	void aRemovedAForeignOrAnUnknownDocumentIs404AndTheListHidesTheRemovedOne() throws Exception {
		Kit kit = kit();
		Kit other = kit();
		JsonNode sent = sent(kit);
		String id = sent.get("id").asString();
		String documentId = sent.get("documents").get(0).get("id").asString();

		mvc.perform(get("/api/v1/budgets/" + id + "/documents/" + documentId + "/download-url").with(as(other.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/budgets/" + id + "/documents").with(as(other.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/budgets/" + id + "/documents/" + UUID.randomUUID() + "/download-url").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		adminSql(kit.clinic(), "UPDATE core.budget_documents SET deleted_at = now() WHERE id = '%s'".formatted(documentId));

		mvc.perform(get("/api/v1/budgets/" + id + "/documents").with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/v1/budgets/" + id + "/documents/" + documentId + "/download-url").with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isNotFound());
		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("documents")).isEmpty();
	}

	// ---- recusas ----

	@Test
	void onlyTheDentistSendsAndAStaleVersionOrASecondSendIs409WithoutStoringAnything() throws Exception {
		Kit kit = kit();
		JsonNode draft = draft(kit);
		String id = draft.get("id").asString();
		long version = draft.get("version").asLong();

		mvc.perform(send(kit, Role.RECEPTIONIST, id, version)).andExpect(status().isForbidden());
		mvc.perform(send(kit, Role.DENTIST_ADMIN, id, version + 5)).andExpect(status().isConflict());
		assertThat(storage.saved).isEmpty();
		JsonNode sent = read(mvc.perform(send(kit, Role.DENTIST_ADMIN, id, version)).andExpect(status().isOk()).andReturn());
		mvc.perform(send(kit, Role.DENTIST_ADMIN, id, sent.get("version").asLong())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("SENT")));

		assertThat(storage.saved).hasSize(1);
		assertThat(documentRows(id)).isEqualTo(1);
	}

	@Test
	void aBudgetWithAZeroTotalCannotBeSentAndStaysADraftWithoutAnyObject() throws Exception {
		Kit kit = kit();
		String free = createProcedure(kit.clinic(), "Cortesia", "0.00", 15);
		JsonNode draft = createBudget(kit, null, item(free, 1, null));
		String id = draft.get("id").asString();

		mvc.perform(send(kit, Role.DENTIST_ADMIN, id, draft.get("version").asLong())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Inclua ao menos um item com valor maior que zero."));

		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("status").asString()).isEqualTo("DRAFT");
		assertThat(storage.saved).isEmpty();
		assertThat(documentRows(id)).isZero();
	}

	// ---- atomicidade ----

	@Test
	void aStorageFailureLeavesTheBudgetADraftWithoutARowAndTheSendWorksAfterwards() throws Exception {
		Kit kit = kit();
		JsonNode draft = draft(kit);
		String id = draft.get("id").asString();
		long version = draft.get("version").asLong();
		storage.failOnSave = true;

		mvc.perform(send(kit, Role.DENTIST_ADMIN, id, version)).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unavailable"));

		JsonNode after = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id);
		assertThat(after.get("status").asString()).isEqualTo("DRAFT");
		assertThat(after.get("sentAt").isNull()).isTrue();
		assertThat(after.get("version").asLong()).as("o rollback devolve a versão").isEqualTo(version);
		assertThat(documentRows(id)).isZero();
		assertThat(storage.deleted).as("a chave registrada antes do upload é limpa").hasSize(1);
		assertThat(storage.saved).isEmpty();
		assertThat(slots.available()).isEqualTo(3);

		storage.reset();
		mvc.perform(send(kit, Role.DENTIST_ADMIN, id, version)).andExpect(status().isOk());
		assertThat(documentRows(id)).isEqualTo(1);
	}

	@Test
	void aFailureToRegisterTheMetadataRollsEverythingBackAndDeletesTheStoredObject() throws Exception {
		Kit kit = kit();
		JsonNode draft = draft(kit);
		String id = draft.get("id").asString();
		createFault("CREATE TRIGGER itest_fail BEFORE INSERT ON core.budget_documents FOR EACH ROW EXECUTE FUNCTION core.itest_fail()");

		mvc.perform(send(kit, Role.DENTIST_ADMIN, id, draft.get("version").asLong())).andExpect(status().isInternalServerError());

		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("status").asString()).isEqualTo("DRAFT");
		assertThat(documentRows(id)).isZero();
		assertThat(storage.saved).hasSize(1);
		assertThat(storage.deleted).containsExactlyElementsOf(storage.saved);
		assertThatThrownBy(() -> stored(storage.saved.get(0))).isInstanceOf(StorageException.class);
		assertThat(slots.available()).isEqualTo(3);
	}

	@Test
	void aFailureOnlyAtCommitTimeAlsoRollsBackAndDeletesTheObject() throws Exception {
		Kit kit = kit();
		JsonNode draft = draft(kit);
		String id = draft.get("id").asString();
		createFault("CREATE CONSTRAINT TRIGGER itest_commit_fail AFTER INSERT ON core.budget_documents DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION core.itest_fail()");

		mvc.perform(send(kit, Role.DENTIST_ADMIN, id, draft.get("version").asLong())).andExpect(status().isInternalServerError());

		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("status").asString()).isEqualTo("DRAFT");
		assertThat(documentRows(id)).isZero();
		assertThat(storage.saved).hasSize(1);
		assertThat(storage.deleted).as("sem linha registrada, o objeto do commit que falhou é apagado").containsExactlyElementsOf(storage.saved);
		assertThatThrownBy(() -> stored(storage.saved.get(0))).isInstanceOf(StorageException.class);
		assertThat(slots.available()).isEqualTo(3);
	}

	@Test
	void whenTheCompensationItselfFailsTheEventIsLoggedWithoutTheKeyAndTheClientStillGetsTheOriginalError() throws Exception {
		Kit kit = kit();
		JsonNode draft = draft(kit);
		String id = draft.get("id").asString();
		storage.failOnSave = true;
		storage.failOnDelete = true;
		Logger logger = (Logger) LoggerFactory.getLogger("crm_data.commercial.audit");
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			mvc.perform(send(kit, Role.DENTIST_ADMIN, id, draft.get("version").asLong())).andExpect(status().isServiceUnavailable());
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(storage.deleted).hasSize(1);
		assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("File.CompensationFailed", id));
		assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(storage.deleted.get(0)));
		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("status").asString()).isEqualTo("DRAFT");
	}

	// ---- nova revisão ----

	@Test
	void regenerateAddsARevisionKeepsTheStatusAndTheOlderPdfAndShowsTheRevisionInTheDocument() throws Exception {
		Kit kit = kit();
		JsonNode sent = sent(kit);
		String id = sent.get("id").asString();

		JsonNode second = read(mvc.perform(regenerate(kit, Role.DENTIST_ADMIN, id)).andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn());

		assertThat(second.get("revision").asInt()).isEqualTo(2);
		JsonNode list = read(mvc.perform(get("/api/v1/budgets/" + id + "/documents").with(as(kit.clinic(), Role.RECEPTIONIST))).andReturn());
		assertThat(list).hasSize(2);
		assertThat(list.get(0).get("revision").asInt()).as("maior revisão primeiro").isEqualTo(2);
		assertThat(list.get(1).get("revision").asInt()).isEqualTo(1);
		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("status").asString()).isEqualTo("SENT");
		String text = pdfText(stored((String) documentRow(id, 2).get("storage_key")));
		assertThat(text).contains("Orçamento nº 1 — Revisão 2", "Revisão gerada em");
		assertThat(stored((String) documentRow(id, 1).get("storage_key"))).as("a revisão anterior é conservada").isNotEmpty();
		assertThat(documentRow(id, 1).get("storage_key")).isNotEqualTo(documentRow(id, 2).get("storage_key"));
	}

	@Test
	void regenerateIsForTheDentistAndNeverForADraftOrAnUnknownBudget() throws Exception {
		Kit kit = kit();
		Kit other = kit();
		JsonNode draft = draft(kit);
		JsonNode sent = sent(kit);

		mvc.perform(regenerate(kit, Role.DENTIST_ADMIN, draft.get("id").asString())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Envie o orçamento antes de gerar o PDF."));
		mvc.perform(regenerate(kit, Role.RECEPTIONIST, sent.get("id").asString())).andExpect(status().isForbidden());
		mvc.perform(regenerate(kit, Role.DENTIST_ADMIN, UUID.randomUUID().toString())).andExpect(status().isNotFound());
		mvc.perform(regenerate(other, Role.DENTIST_ADMIN, sent.get("id").asString())).andExpect(status().isNotFound());
		assertThat(documentRows(draft.get("id").asString())).isZero();
	}

	@Test
	void regenerateAlsoWorksForApprovedAndRejectedBudgets() throws Exception {
		Kit kit = kit();
		for (String finalStatus : new String[] { "APPROVED", "REJECTED" }) {
			JsonNode sent = sent(kit);
			String id = sent.get("id").asString();
			forceStatus(kit.clinic(), id, finalStatus);

			JsonNode created = read(mvc.perform(regenerate(kit, Role.DENTIST_ADMIN, id)).andExpect(status().isCreated()).andReturn());

			assertThat(created.get("revision").asInt()).isEqualTo(2);
			assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("status").asString()).isEqualTo(finalStatus);
		}
	}

	// ---- concorrência ----

	@Test
	void twoSimultaneousSendsOfTheSameVersionLeaveOneSentBudgetOneDocumentAndNoOrphanObject() throws Exception {
		for (int round = 0; round < 10; round++) {
			Kit kit = kit(); // clínica e usuário novos a cada rodada: o limite de 6 envios por minuto é por usuário
			storage.reset();
			JsonNode draft = draft(kit);
			String id = draft.get("id").asString();
			long version = draft.get("version").asLong();
			CountDownLatch go = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				results.add(pool.submit(() -> {
					go.await();
					return mvc.perform(send(kit, Role.DENTIST_ADMIN, id, version)).andReturn().getResponse().getStatus();
				}));
			}
			go.countDown();
			List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
			pool.shutdown();

			assertThat(statuses).as("rodada %d", round).containsExactlyInAnyOrder(200, 409);
			assertThat(documentRows(id)).isEqualTo(1);
			assertThat(storage.saved).hasSize(1);
			assertThat(storage.deleted).isEmpty();
		}
		assertThat(slots.available()).isEqualTo(3);
	}

	@Test
	void twoSimultaneousRevisionsGetDistinctSequentialNumbers() throws Exception {
		Kit kit = kit();
		JsonNode sent = sent(kit);
		String id = sent.get("id").asString();
		int expectedNext = 2;
		for (int round = 0; round < 2; round++) {
			CountDownLatch go = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			List<Future<Integer>> revisions = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				revisions.add(pool.submit(() -> {
					go.await();
					var response = mvc.perform(regenerate(kit, Role.DENTIST_ADMIN, id)).andReturn().getResponse();
					assertThat(response.getStatus()).isEqualTo(201);
					return mapper.readTree(response.getContentAsString()).get("revision").asInt();
				}));
			}
			go.countDown();
			List<Integer> got = List.of(revisions.get(0).get(), revisions.get(1).get());
			pool.shutdown();

			assertThat(got).containsExactlyInAnyOrder(expectedNext, expectedNext + 1);
			expectedNext += 2;
		}
		assertThat(documentRows(id)).isEqualTo(5);
	}

	// ---- limites ----

	@Test
	void theSeventhSendOrReissueInAMinuteIs429WithRetryAfter() throws Exception {
		Kit kit = kit();
		JsonNode sent = sent(kit); // 1 de 6
		String id = sent.get("id").asString();

		for (int i = 0; i < 5; i++) {
			mvc.perform(regenerate(kit, Role.DENTIST_ADMIN, id)).andExpect(status().isCreated());
		}
		mvc.perform(regenerate(kit, Role.DENTIST_ADMIN, id)).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/rate-limited"));
		mvc.perform(get("/api/v1/budgets/" + id + "/documents").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());

		assertThat(documentRows(id)).as("a 7ª não gerou nada").isEqualTo(6);
	}

	@Test
	void withoutAFreeSlotTheSendIs503WithRetryAfterAndTheBudgetStaysADraft() throws Exception {
		Kit kit = kit();
		JsonNode draft = draft(kit);
		String id = draft.get("id").asString();
		assertThat(slots.tryAcquire()).isTrue();
		assertThat(slots.tryAcquire()).isTrue();
		assertThat(slots.tryAcquire()).isTrue();
		try {
			mvc.perform(send(kit, Role.DENTIST_ADMIN, id, draft.get("version").asLong())).andExpect(status().isServiceUnavailable())
					.andExpect(header().string("Retry-After", "5"));
			assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("status").asString()).isEqualTo("DRAFT");
			assertThat(storage.saved).isEmpty();
		}
		finally {
			slots.release();
			slots.release();
			slots.release();
		}
		mvc.perform(send(kit, Role.DENTIST_ADMIN, id, draft.get("version").asLong())).andExpect(status().isOk());
	}

	// ---- banco ----

	@Test
	void theDatabaseHidesOtherClinicsDocumentsAndRefusesTheReceptionistAndAnyEditButDeletedAt() throws Exception {
		Kit kit = kit();
		Kit other = kit();
		sent(kit);
		sent(other);

		assertThat(asRole(kit.clinic(), Role.DENTIST_ADMIN, () -> jdbc.queryForList("SELECT revision FROM core.budget_documents", Integer.class))).hasSize(1);
		assertThat(asRole(kit.clinic(), Role.RECEPTIONIST, () -> jdbc.queryForList("SELECT revision FROM core.budget_documents", Integer.class))).hasSize(1);
		assertThat(asRole(kit.clinic(), Role.RECEPTIONIST, () -> jdbc.update("UPDATE core.budget_documents SET deleted_at = now()")))
				.as("a política de UPDATE é só do dentista: a recepção não enxerga a linha para atualizar").isZero();
		assertThatThrownBy(() -> asRole(kit.clinic(), Role.RECEPTIONIST, () -> jdbc.update("""
				INSERT INTO core.budget_documents (id, clinic_id, budget_id, patient_id, storage_key, content_type, size_bytes, sha256, created_by)
				SELECT gen_random_uuid(), clinic_id, budget_id, patient_id, storage_key || 'x', content_type, size_bytes, sha256, core.current_user_id()
				  FROM core.budget_documents""")))
				.satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).isNotEmpty());
		assertThatThrownBy(() -> asRole(kit.clinic(), Role.DENTIST_ADMIN, () -> jdbc.update("UPDATE core.budget_documents SET sha256 = repeat('a', 64)")))
				.satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).contains("42501"));
		assertThatThrownBy(() -> asRole(kit.clinic(), Role.DENTIST_ADMIN, () -> jdbc.update("DELETE FROM core.budget_documents")))
				.satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).contains("42501"));
	}
}
