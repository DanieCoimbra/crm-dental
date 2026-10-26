package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.security.Role;
import crm_data.shared.storage.FileStorage;
import crm_data.shared.storage.StorageException;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.PatientFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * F07 contra o PostgreSQL real (V001–V029): a anonimização do paciente com orçamentos, parcelas e PDFs (bloqueio por
 * parcela em aberto, o que sobrevive, PDFs apagados do Storage, falha do Storage → {@code PENDING}) e a auditoria das
 * quatro tabelas novas, sem valores.
 */
@org.springframework.context.annotation.Import(crm_data.support.FaultyDocumentStorageConfig.class)
class CommercialAnonymizationIT extends Phase3ItSupport {

	@MockitoSpyBean(name = "fileStorage")
	FileStorage examStorage;
	@Autowired
	@Qualifier("documentStorage")
	FileStorage documentStorage;

	@AfterEach
	void resetSpy() {
		org.mockito.Mockito.reset(examStorage);
	}

	// ---- auxiliares ----

	private MockHttpServletRequestBuilder anonymize(Kit kit) {
		return post("/api/v1/patients/" + kit.patient() + "/anonymize").with(as(kit.clinic(), Role.DENTIST_ADMIN));
	}

	private void inactivate(Kit kit) throws Exception {
		adminSql(kit.clinic(), "UPDATE core.patients SET active = false, deactivated_at = now() WHERE id = '%s'".formatted(kit.patient()));
		PatientFixtures.backdateClinicalRecords(kit.patient(), 21);
	}

	/** Orçamento enviado de verdade (PDF no Storage dos documentos), aprovado e com 1 parcela de R$ 800,00. */
	private String sentApprovedWithPlan(Kit kit) throws Exception {
		return sentApprovedWithPlan(kit, null);
	}

	private String sentApprovedWithPlan(Kit kit, String notes) throws Exception {
		JsonNode draft = createBudget(kit, "600.00", item(kit.restoration(), 2, null), item(kit.root(), 1, null));
		String id = draft.get("id").asString();
		if (notes != null) {
			adminSql(kit.clinic(), "UPDATE core.budgets SET notes = '%s' WHERE id = '%s'".formatted(notes, id));
			draft = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id);
		}
		JsonNode sent = read(mvc.perform(json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(draft.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());
		mvc.perform(json(post("/api/v1/budgets/" + id + "/approve"), "{\"version\":%d}".formatted(sent.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		planOf(kit, id, 1);
		return id;
	}

	private String installmentOf(String budgetId) {
		return DatabaseContainer.admin().queryForObject("SELECT id::text FROM core.installments WHERE budget_id = ?::uuid AND status <> 'CANCELED'", String.class, budgetId);
	}

	private void settle(Kit kit, String budgetId) throws Exception {
		mvc.perform(json(post("/api/v1/installments/" + installmentOf(budgetId) + "/payments"), "{\"amount\":\"800.00\",\"method\":\"PIX\"}")
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated());
	}

	private List<String> documentKeys(String budgetId) {
		return DatabaseContainer.admin().queryForList("SELECT storage_key FROM core.budget_documents WHERE budget_id = ?::uuid", String.class, budgetId);
	}

	private JsonNode audit(Kit kit, String table, String recordId) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/v1/audit-log").param("tableName", table).param("size", "50")
				.with(as(kit.clinic(), Role.DENTIST_ADMIN));
		if (recordId != null) {
			request.param("recordId", recordId);
		}
		return read(mvc.perform(request).andExpect(status().isOk()).andReturn()).get("content");
	}

	private List<String> actions(JsonNode entries) {
		List<String> actions = new ArrayList<>();
		entries.forEach(entry -> actions.add(entry.get("action").asString()));
		return actions;
	}

	// ---- bloqueio e o que sobrevive ----

	@Test
	void anOpenInstallmentBlocksWithCR006AndNothingChanges() throws Exception {
		Kit kit = kit();
		String budget = sentApprovedWithPlan(kit);
		inactivate(kit);

		mvc.perform(anonymize(kit)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Quite ou cancele as parcelas em aberto antes de anonimizar."));

		assertThat(DatabaseContainer.admin().queryForObject("SELECT anonymized_at IS NULL FROM core.patients WHERE id = ?::uuid", Boolean.class, kit.patient())).isTrue();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.budget_documents WHERE budget_id = ?::uuid AND deleted_at IS NULL", Long.class, budget)).isEqualTo(1);
	}

	@Test
	void settledInstallmentsDoNotBlockAndEverythingFinancialSurvivesWhileNotesAndPdfsGo() throws Exception {
		Kit kit = kit();
		JsonNode draft = createBudget(kit, "600.00", item(kit.restoration(), 2, null), item(kit.root(), 1, null));
		String id = draft.get("id").asString();
		adminSql(kit.clinic(), "UPDATE core.budgets SET notes = 'Pagamento combinado em 3x' WHERE id = '%s'".formatted(id));
		JsonNode sent = read(mvc.perform(json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());
		mvc.perform(json(post("/api/v1/budgets/" + id + "/approve"), "{\"version\":%d}".formatted(sent.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		planOf(kit, id, 1);
		settle(kit, id);
		String openSent = sentApprovedWithPlan(kit); // outro orçamento aprovado, com a parcela paga também
		settle(kit, openSent);
		List<String> keys = new ArrayList<>(documentKeys(id));
		keys.addAll(documentKeys(openSent));
		JsonNode before = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id);
		String documentId = DatabaseContainer.admin().queryForObject("SELECT id::text FROM core.budget_documents WHERE budget_id = ?::uuid", String.class, id);
		// antes de anonimizar o download do PDF funciona; depois o mesmo documento some (deleted_at)
		mvc.perform(get("/api/v1/budgets/" + id + "/documents/" + documentId + "/download-url").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		inactivate(kit);
		assertThat(keys).hasSize(2);
		keys.forEach(key -> assertThat(loadable(key)).isTrue());

		JsonNode result = read(mvc.perform(anonymize(kit)).andExpect(status().isOk()).andReturn());

		assertThat(result.get("storageCleanup").asString()).isEqualTo("COMPLETED");
		assertThat(result.get("openBudgets").asInt()).isZero();
		JsonNode after = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id);
		assertThat(after.get("notes").isNull()).isTrue();
		assertThat(after.get("number").asInt()).isEqualTo(before.get("number").asInt());
		assertThat(after.get("totalAmount").asString()).isEqualTo("800.00");
		assertThat(after.get("status").asString()).isEqualTo("APPROVED");
		assertThat(after.get("items")).hasSize(before.get("items").size());
		assertThat(after.get("documents")).as("PDFs removidos somem da listagem").isEmpty();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.budget_documents WHERE budget_id = ?::uuid AND deleted_at IS NOT NULL", Long.class, id)).isEqualTo(1);
		assertThat(DatabaseContainer.admin().queryForObject("SELECT status FROM core.installments WHERE budget_id = ?::uuid", String.class, id)).isEqualTo("PAID");
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.payments WHERE installment_id = ?::uuid", Long.class, installmentOf(id))).isEqualTo(1);
		keys.forEach(key -> assertThat(loadable(key)).as("objeto apagado do Storage").isFalse());
		mvc.perform(get("/api/v1/budgets/" + id + "/documents/" + documentId + "/download-url").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
	}

	@Test
	void aSentBudgetWithoutInstallmentsDoesNotBlockAndIsCountedAsOpen() throws Exception {
		Kit kit = kit();
		JsonNode draft = createBudget(kit, null, item(kit.restoration(), 1, null));
		createBudget(kit, null, item(kit.restoration(), 1, null)); // rascunho
		forceStatus(kit.clinic(), draft.get("id").asString(), "SENT");
		inactivate(kit);

		JsonNode result = read(mvc.perform(anonymize(kit)).andExpect(status().isOk()).andReturn());

		assertThat(result.get("openBudgets").asInt()).isEqualTo(2);
		assertThat(result.get("storageCleanup").asString()).isEqualTo("COMPLETED");
	}

	@Test
	void aCanceledInstallmentDoesNotBlockEither() throws Exception {
		Kit kit = kit();
		String budget = sentApprovedWithPlan(kit);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/budgets/" + budget + "/installments")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTO\",\"count\":1,\"firstDueDate\":\"2027-05-01\"}")
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		settle(kit, budget); // a nova parcela é paga; a antiga ficou CANCELED
		inactivate(kit);

		mvc.perform(anonymize(kit)).andExpect(status().isOk());
	}

	// ---- Storage ----

	@Test
	void aStorageFailureKeepsTheAnonymizationAndReportsPending() throws Exception {
		Kit kit = kit();
		String budget = sentApprovedWithPlan(kit);
		settle(kit, budget);
		inactivate(kit);
		doThrow(new StorageException("Falha ao apagar objetos no Storage (HTTP 500).")).when(examStorage).deleteAll(any());

		JsonNode result = read(mvc.perform(anonymize(kit)).andExpect(status().isOk()).andReturn());

		assertThat(result.get("storageCleanup").asString()).isEqualTo("PENDING");
		assertThat(DatabaseContainer.admin().queryForObject("SELECT anonymized_at IS NOT NULL FROM core.patients WHERE id = ?::uuid", Boolean.class, kit.patient())).isTrue();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.budget_documents WHERE budget_id = ?::uuid AND deleted_at IS NOT NULL", Long.class, budget)).isEqualTo(1);
		documentKeys(budget).forEach(key -> assertThat(loadable(key)).as("o objeto fica para a conciliação manual").isTrue());
	}

	// ---- depois de anonimizado ----

	@Test
	void anAnonymizedPatientGetsNoNewBudgetPlanOrPaymentAndTheErrorsNeverShowKeys() throws Exception {
		Kit kit = kit();
		String budget = sentApprovedWithPlan(kit);
		String installment = installmentOf(budget);
		settle(kit, budget);
		String other = sentApprovedWithPlan(kit);
		settle(kit, other);
		inactivate(kit);
		mvc.perform(anonymize(kit)).andExpect(status().isOk());

		mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), null, item(kit.restoration(), 1, null))).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"1.00\",\"method\":\"PIX\"}").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());
		mvc.perform(anonymize(kit)).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Paciente anonimizado."));
	}

	// ---- auditoria ----

	@Test
	void theAuditLogShowsTheFourNewTablesWithoutValuesAndAnonymizationAddsOneUpdatePerCleanedBudget() throws Exception {
		Kit kit = kit();
		String budget = sentApprovedWithPlan(kit, "Pagamento combinado");
		String installment = installmentOf(budget);
		String paymentId = read(mvc.perform(json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"800.00\",\"method\":\"PIX\"}")
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn()).get("payment").get("id").asString();
		mvc.perform(post("/api/v1/payments/" + paymentId + "/reversal").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated());
		settle(kit, budget);
		int updatesBefore = actions(audit(kit, "budgets", budget)).stream().filter("UPDATE"::equals).toList().size();
		inactivate(kit);
		mvc.perform(anonymize(kit)).andExpect(status().isOk());

		JsonNode budgets = audit(kit, "budgets", budget);
		JsonNode installments = audit(kit, "installments", installment);
		JsonNode payments = audit(kit, "payments", paymentId);
		JsonNode documents = audit(kit, "budget_documents", null);

		assertThat(actions(budgets)).contains("INSERT", "UPDATE");
		assertThat(actions(budgets).stream().filter("UPDATE"::equals).count()).as("a limpeza de notes gera 1 UPDATE a mais").isEqualTo(updatesBefore + 1);
		assertThat(actions(installments)).as("o status da parcela muda por trigger e aparece").contains("INSERT", "UPDATE");
		assertThat(actions(payments)).contains("INSERT");
		assertThat(actions(audit(kit, "payments", null))).as("o estorno é uma linha nova").filteredOn("INSERT"::equals).hasSizeGreaterThanOrEqualTo(3);
		assertThat(actions(documents)).contains("INSERT");
		assertThat(actions(audit(kit, "patients", kit.patient()))).contains("ANONYMIZE");
		for (JsonNode page : List.of(budgets, installments, payments, documents)) {
			assertThat(page.toString()).doesNotContain("800.00", "600.00", "250.00", "Restauração", "storage", "amount", "notes");
		}
	}

	@Test
	void unauditedTablesAreRejectedAndTheReceptionistIsForbidden() throws Exception {
		Kit kit = kit();

		for (String table : List.of("procedure_types", "budget_items", "nao_existe")) {
			mvc.perform(get("/api/v1/audit-log").param("tableName", table).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/v1/audit-log").param("recordId", java.util.UUID.randomUUID().toString()).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/audit-log").param("tableName", "budgets").with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/patients/" + kit.patient() + "/anonymize").with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/patients/" + java.util.UUID.randomUUID() + "/anonymize").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(anonymize(kit)).andExpect(status().isConflict()); // paciente ativo (CR001)
	}

	private boolean loadable(String key) {
		try (java.io.InputStream in = documentStorage.load(key)) {
			return in != null;
		}
		catch (java.io.IOException | RuntimeException ex) {
			return false;
		}
	}

	// ---- corrida: envio do PDF × anonimização ----

	@Test
	void aSendRacingTheAnonymizationEndsConsistentWithNoLivePdfAndNoOrphanObject() throws Exception {
		crm_data.support.FaultyFileStorage storage = (crm_data.support.FaultyFileStorage) documentStorage;
		for (int attempt = 0; attempt < 10; attempt++) {
			int round = attempt;
			Kit kit = kit(); // clínica e usuário novos: o limite de 6 envios por minuto é por usuário
			storage.reset();
			JsonNode draft = createBudget(kit, null, item(kit.restoration(), 1, null));
			String id = draft.get("id").asString();
			long version = draft.get("version").asLong();
			inactivate(kit);
			java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
			java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
			java.util.concurrent.Future<Integer> send = pool.submit(() -> {
				go.await();
				return mvc.perform(json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(version))
						.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andReturn().getResponse().getStatus();
			});
			java.util.concurrent.Future<Integer> anonymize = pool.submit(() -> {
				go.await();
				return mvc.perform(anonymize(kit)).andReturn().getResponse().getStatus();
			});
			go.countDown();
			int sendStatus = send.get();
			int anonymizeStatus = anonymize.get();
			pool.shutdown();

			assertThat(anonymizeStatus).as("rodada %d: anonimizar nunca falha (SENT sem parcela não bloqueia)", round).isEqualTo(200);
			assertThat(sendStatus).as("rodada %d: o envio ou passou antes ou falhou limpo", round).isIn(200, 409);
			assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.budget_documents WHERE budget_id = ?::uuid AND deleted_at IS NULL", Long.class, id))
					.as("rodada %d: nenhum PDF vivo de paciente anonimizado", round).isZero();
			for (String key : storage.saved) {
				assertThat(loadable(key)).as("rodada %d: nenhum objeto órfão no Storage", round).isFalse();
			}
		}
	}
}
