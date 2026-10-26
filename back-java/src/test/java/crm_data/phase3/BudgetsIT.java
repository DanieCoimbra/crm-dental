package crm_data.phase3;

import static crm_data.phase2.AgendaDates.at;
import static crm_data.phase2.AgendaDates.monday;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import crm_data.shared.error.DatabaseErrors;
import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * F03 contra o PostgreSQL real (V001–V029): o banco calcula número, total, preço e carimbos; o backend grava na ordem que
 * o banco exige (cabeçalho → itens → desconto), troca a lista de itens do rascunho sem violar o total em estado
 * intermediário, trava edição depois do envio, serializa a numeração por clínica e respeita papel e isolamento.
 */
class BudgetsIT extends Phase3ItSupport {

	// ---- auxiliares ----

	// Kit, kit(), item(), budgetBody(), createBudget(), getBudget() e forceStatus() vêm de Phase3ItSupport.

	private MockHttpServletRequestBuilder update(Kit kit, String id, long version, String discount, String... items) {
		String discountField = discount == null ? "" : ",\"discountAmount\":\"" + discount + "\"";
		return json(put("/api/v1/budgets/" + id), "{\"items\":[%s]%s,\"version\":%d}".formatted(String.join(",", items), discountField, version))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN));
	}

	// ---- criar ----

	@Test
	void theDatabaseComputesNumberPricesLinesAndTotalAndTheResponseCarriesThem() throws Exception {
		Kit kit = kit();

		MockHttpServletRequestBuilder request = json(post("/api/v1/budgets"),
				"{\"patientId\":\"%s\",\"discountAmount\":\"50.00\",\"notes\":\"  Em 6x  \",\"validUntil\":\"2027-03-15\",\"items\":[%s,%s]}"
						.formatted(kit.patient(), item(kit.restoration(), 2, null), item(kit.root(), 1, "100.00")))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN));
		JsonNode created = read(mvc.perform(request).andExpect(status().isCreated()).andExpect(header().exists("Location"))
				.andExpect(header().string("Cache-Control", "no-store")).andReturn());

		assertThat(created.get("number").asInt()).isEqualTo(1);
		assertThat(created.get("status").asString()).isEqualTo("DRAFT");
		assertThat(created.get("subtotalAmount").asString()).isEqualTo("1300.00");
		assertThat(created.get("discountAmount").asString()).isEqualTo("50.00");
		assertThat(created.get("totalAmount").asString()).isEqualTo("1250.00");
		assertThat(created.get("notes").asString()).isEqualTo("Em 6x");
		assertThat(created.get("validUntil").asString()).isEqualTo("2027-03-15");
		assertThat(created.get("version").asLong()).as("cabeçalho + desconto por UPDATE").isGreaterThanOrEqualTo(1);
		assertThat(created.get("sentAt").isNull()).isTrue();
		JsonNode first = created.get("items").get(0);
		assertThat(first.get("position").asInt()).isEqualTo(1);
		assertThat(first.get("description").asString()).isEqualTo("Restauração");
		assertThat(first.get("unitPrice").asString()).isEqualTo("250.00");
		assertThat(first.get("lineTotal").asString()).isEqualTo("500.00");
		JsonNode second = created.get("items").get(1);
		assertThat(second.get("description").asString()).isEqualTo("Canal");
		assertThat(second.get("discountAmount").asString()).isEqualTo("100.00");
		assertThat(second.get("lineTotal").asString()).isEqualTo("800.00");
		assertThat(created.get("patient").get("name").asString()).isEqualTo("Maria Souza");
		assertThat(created.has("clinicId")).isFalse();
		assertThat(created.get("documents")).isEmpty();
	}

	@Test
	void numbersAreSequentialPerClinicAndEachClinicStartsAtOne() throws Exception {
		Kit a = kit();
		Kit b = kit();

		assertThat(createBudget(a, null, item(a.restoration(), 1, null)).get("number").asInt()).isEqualTo(1);
		assertThat(createBudget(a, null, item(a.restoration(), 1, null)).get("number").asInt()).isEqualTo(2);
		assertThat(createBudget(b, null, item(b.restoration(), 1, null)).get("number").asInt()).isEqualTo(1);
		assertThat(createBudget(a, null, item(a.restoration(), 1, null)).get("number").asInt()).isEqualTo(3);
	}

	@Test
	void serverOwnedFieldsAndCatalogPricesFromTheClientAreIgnored() throws Exception {
		Kit kit = kit();
		Clinic other = newClinic();
		String itemWithExtras = "{\"procedureTypeId\":\"%s\",\"quantity\":1,\"unitPrice\":\"1.00\",\"description\":\"Barato\",\"lineTotal\":\"1.00\"}"
				.formatted(kit.restoration());
		String extras = ",\"number\":99,\"totalAmount\":\"1.00\",\"status\":\"APPROVED\",\"clinicId\":\"%s\",\"createdBy\":\"%s\",\"sentAt\":\"2026-01-01T00:00:00Z\""
				.formatted(other.id(), UUID.randomUUID());

		JsonNode created = read(mvc.perform(json(post("/api/v1/budgets"), "{\"patientId\":\"%s\",\"items\":[%s]%s}".formatted(kit.patient(), itemWithExtras, extras))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn());

		assertThat(created.get("number").asInt()).isEqualTo(1);
		assertThat(created.get("status").asString()).isEqualTo("DRAFT");
		assertThat(created.get("totalAmount").asString()).isEqualTo("250.00");
		assertThat(created.get("items").get(0).get("unitPrice").asString()).isEqualTo("250.00");
		assertThat(created.get("items").get(0).get("description").asString()).isEqualTo("Restauração");
		assertThat(created.get("sentAt").isNull()).isTrue();
		assertThat(DatabaseContainer.admin().queryForObject("SELECT clinic_id FROM core.budgets WHERE id = ?::uuid", UUID.class, created.get("id").asString()))
				.isEqualTo(kit.clinic().id());
		assertThat(DatabaseContainer.admin().queryForObject("SELECT created_by FROM core.budgets WHERE id = ?::uuid", UUID.class, created.get("id").asString()))
				.isEqualTo(userOf(kit.clinic(), Role.DENTIST_ADMIN));
	}

	@Test
	void theItemKeepsTheCatalogPriceOfTheMomentEvenAfterThePriceChanges() throws Exception {
		Kit kit = kit();
		JsonNode created = createBudget(kit, null, item(kit.restoration(), 1, null));
		mvc.perform(json(put("/api/v1/procedure-types/" + kit.restoration()),
				procedureBody("Restauração", "999.00", 45, "#3B82F6").replace("}", ",\"version\":0}")).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());

		JsonNode fetched = getBudget(kit.clinic(), Role.RECEPTIONIST, created.get("id").asString());

		assertThat(fetched.get("items").get(0).get("unitPrice").asString()).isEqualTo("250.00");
		assertThat(fetched.get("totalAmount").asString()).isEqualTo("250.00");
	}

	@Test
	void twoSimultaneousCreationsInTheSameClinicGetDistinctSequentialNumbers() throws Exception {
		Kit kit = kit();
		Set<Integer> numbers = new HashSet<>();
		for (int round = 0; round < 20; round++) {
			CountDownLatch go = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				results.add(pool.submit(() -> {
					go.await();
					var response = mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), null, item(kit.restoration(), 1, null)))
							.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andReturn().getResponse();
					assertThat(response.getStatus()).isEqualTo(201);
					return read(response.getContentAsString()).get("number").asInt();
				}));
			}
			go.countDown();
			int first = results.get(0).get();
			int second = results.get(1).get();
			pool.shutdown();
			assertThat(first).isNotEqualTo(second);
			assertThat(numbers.add(first)).isTrue();
			assertThat(numbers.add(second)).isTrue();
		}
		assertThat(numbers).hasSize(40);
		assertThat(numbers).containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1, 40).boxed().toList());
	}

	private JsonNode read(String json) throws Exception {
		return mapper.readTree(json);
	}

	// ---- regras de criação ----

	@Test
	void anInactivePatientCannotReceiveANewBudgetAndAnUnknownOneIs404() throws Exception {
		Kit kit = kit();
		mvc.perform(json(patch("/api/v1/patients/" + kit.patient() + "/active"), "{\"active\":false,\"version\":0}").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());

		mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), null, item(kit.restoration(), 1, null))).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Paciente inativo."));
		mvc.perform(json(post("/api/v1/budgets"), budgetBody(UUID.randomUUID().toString(), null, item(kit.restoration(), 1, null)))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
	}

	@Test
	void theProcedureMustExistInTheClinicAndBeActive() throws Exception {
		Kit kit = kit();
		Clinic other = newClinic();
		String foreign = createProcedure(other, "Canal", "900", 90);
		String off = createProcedure(kit.clinic(), "Antigo", "10.00", 30);
		mvc.perform(json(patch("/api/v1/procedure-types/" + off + "/active"), "{\"active\":false,\"version\":0}").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());

		mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), null, item(foreign, 1, null))).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), null, item(kit.restoration(), 1, null), item(off, 1, null)))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.reason").value("PROCEDURE_INACTIVE"));
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.budgets WHERE clinic_id = ?", Long.class, kit.clinic().id())).isZero();
	}

	@Test
	void theAppointmentMustBelongToThePatient() throws Exception {
		Kit kit = kit();
		String otherPatient = createPatient(kit.clinic(), "João Lima");
		LocalDate monday = monday();
		String appointment = read(mvc.perform(json(post("/api/v1/appointments"),
				"{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(otherPatient, at(monday, 9, 0), at(monday, 9, 45)))
				.with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isCreated()).andReturn()).get("id").asString();

		mvc.perform(json(post("/api/v1/budgets"), "{\"patientId\":\"%s\",\"appointmentId\":\"%s\",\"items\":[%s]}".formatted(kit.patient(), appointment,
				item(kit.restoration(), 1, null))).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(json(post("/api/v1/budgets"), "{\"patientId\":\"%s\",\"appointmentId\":\"%s\",\"items\":[%s]}".formatted(kit.patient(), UUID.randomUUID(),
				item(kit.restoration(), 1, null))).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		JsonNode ok = read(mvc.perform(json(post("/api/v1/budgets"), "{\"patientId\":\"%s\",\"appointmentId\":\"%s\",\"items\":[%s]}".formatted(otherPatient,
				appointment, item(kit.restoration(), 1, null))).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn());
		assertThat(ok.get("appointmentId").asString()).isEqualTo(appointment);
	}

	@Test
	void valueLimitsComeAsFieldPathsAndNothingIsStored() throws Exception {
		Kit kit = kit();
		String luxury = createProcedure(kit.clinic(), "Implante", "999999.99", 120);

		String errors = mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), "9999.00", item(kit.restoration(), 1, "999.00"),
				item(luxury, 2, null))).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isBadRequest()).andReturn().getResponse()
				.getContentAsString();

		assertThat(errors).contains("items[0].discountAmount", "items[1].quantity", "discountAmount");
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.budgets WHERE clinic_id = ?", Long.class, kit.clinic().id())).isZero();
	}

	// ---- editar ----

	@Test
	void updateReplacesTheItemsReordersThemAndRecomputesTheTotal() throws Exception {
		Kit kit = kit();
		JsonNode created = createBudget(kit, "100.00", item(kit.restoration(), 2, null), item(kit.root(), 1, null));
		String id = created.get("id").asString();

		JsonNode updated = read(mvc.perform(update(kit, id, created.get("version").asLong(), "20.00", item(kit.root(), 2, "50.00"), item(kit.restoration(), 1, null)))
				.andExpect(status().isOk()).andReturn());

		assertThat(updated.get("items")).hasSize(2);
		assertThat(updated.get("items").get(0).get("description").asString()).isEqualTo("Canal");
		assertThat(updated.get("items").get(0).get("position").asInt()).isEqualTo(1);
		assertThat(updated.get("items").get(0).get("lineTotal").asString()).isEqualTo("1750.00");
		assertThat(updated.get("items").get(1).get("description").asString()).isEqualTo("Restauração");
		assertThat(updated.get("subtotalAmount").asString()).isEqualTo("2000.00");
		assertThat(updated.get("discountAmount").asString()).isEqualTo("20.00");
		assertThat(updated.get("totalAmount").asString()).isEqualTo("1980.00");
		assertThat(updated.get("number").asInt()).isEqualTo(created.get("number").asInt());
		assertThat(updated.get("version").asLong()).isGreaterThan(created.get("version").asLong());
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.budget_items WHERE budget_id = ?::uuid", Long.class, id)).isEqualTo(2);
	}

	@Test
	void aBigPreviousDiscountDoesNotBreakTheTotalWhileTheItemsAreSwapped() throws Exception {
		Kit kit = kit();
		JsonNode created = createBudget(kit, "1000.00", item(kit.root(), 2, null)); // total 800
		String id = created.get("id").asString();

		// os itens novos somam menos que o desconto antigo: sem zerar o desconto antes, o total intermediário ficaria negativo (23514)
		JsonNode updated = read(mvc.perform(update(kit, id, created.get("version").asLong(), "0.00", item(kit.restoration(), 1, null)))
				.andExpect(status().isOk()).andReturn());

		assertThat(updated.get("totalAmount").asString()).isEqualTo("250.00");
		assertThat(updated.get("discountAmount").asString()).isEqualTo("0.00");
	}

	@Test
	void updateAlwaysAdvancesTheVersionEvenWhenOnlyTheItemsChange() throws Exception {
		Kit kit = kit();
		JsonNode created = createBudget(kit, null, item(kit.restoration(), 1, null));
		String id = created.get("id").asString();
		long v0 = created.get("version").asLong();

		JsonNode first = read(mvc.perform(update(kit, id, v0, null, item(kit.restoration(), 2, null))).andExpect(status().isOk()).andReturn());
		long v1 = first.get("version").asLong();
		JsonNode same = read(mvc.perform(update(kit, id, v1, null, item(kit.restoration(), 2, null))).andExpect(status().isOk()).andReturn());

		assertThat(v1).isGreaterThan(v0);
		assertThat(same.get("version").asLong()).as("até um PUT idêntico avança a versão").isGreaterThan(v1);
		mvc.perform(update(kit, id, v0, null, item(kit.restoration(), 3, null))).andExpect(status().isConflict()); // versão defasada
		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("items").get(0).get("quantity").asInt()).isEqualTo(2);
	}

	@Test
	void twoSimultaneousUpdatesWithTheSameVersionLeaveOneWinnerAndOne409() throws Exception {
		Kit kit = kit();
		for (int round = 0; round < 20; round++) {
			JsonNode created = createBudget(kit, null, item(kit.restoration(), 1, null));
			String id = created.get("id").asString();
			long version = created.get("version").asLong();
			CountDownLatch go = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			List<Future<Integer>> results = new ArrayList<>();
			for (int quantity = 2; quantity <= 3; quantity++) {
				int chosen = quantity;
				results.add(pool.submit(() -> {
					go.await();
					return mvc.perform(update(kit, id, version, null, item(kit.root(), chosen, null))).andReturn().getResponse().getStatus();
				}));
			}
			go.countDown();
			List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
			pool.shutdown();

			assertThat(statuses).as("rodada %d", round).containsExactlyInAnyOrder(200, 409);
			JsonNode finalState = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id);
			int quantity = finalState.get("items").get(0).get("quantity").asInt();
			assertThat(finalState.get("items")).as("sem item duplicado nem perdido").hasSize(1);
			assertThat(quantity).isIn(2, 3);
			assertThat(finalState.get("totalAmount").asString()).isEqualTo(quantity == 2 ? "1800.00" : "2700.00");
		}
	}

	@Test
	void aSentBudgetCannotBeEditedOrDiscardedAndKeepsItsItems() throws Exception {
		Kit kit = kit();
		JsonNode created = createBudget(kit, null, item(kit.restoration(), 1, null));
		String id = created.get("id").asString();
		forceStatus(kit.clinic(), id, "SENT");
		long version = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("version").asLong();

		mvc.perform(update(kit, id, version, null, item(kit.root(), 5, null))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("SENT")));
		mvc.perform(json(post("/api/v1/budgets/" + id + "/discard"), "{\"version\":%d}".formatted(version)).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());

		JsonNode fetched = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id);
		assertThat(fetched.get("items")).hasSize(1);
		assertThat(fetched.get("items").get(0).get("description").asString()).isEqualTo("Restauração");
		assertThat(fetched.get("status").asString()).isEqualTo("SENT");
	}

	@Test
	void thePatientOfABudgetNeverChanges() throws Exception {
		Kit kit = kit();
		String other = createPatient(kit.clinic(), "João Lima");
		JsonNode created = createBudget(kit, null, item(kit.restoration(), 1, null));
		String id = created.get("id").asString();

		mvc.perform(json(put("/api/v1/budgets/" + id), "{\"patientId\":\"%s\",\"items\":[%s],\"version\":%d}".formatted(other, item(kit.restoration(), 1, null),
				created.get("version").asLong())).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());

		assertThat(getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("patient").get("id").asString()).isEqualTo(kit.patient());
	}

	// ---- descartar ----

	@Test
	void discardTurnsADraftIntoRejectedWithTheDatabaseStampAndASecondDiscardIs409() throws Exception {
		Kit kit = kit();
		JsonNode created = createBudget(kit, null, item(kit.restoration(), 1, null));
		String id = created.get("id").asString();

		JsonNode discarded = read(mvc.perform(json(post("/api/v1/budgets/" + id + "/discard"), "{\"version\":%d}".formatted(created.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk()).andReturn());

		assertThat(discarded.get("status").asString()).isEqualTo("REJECTED");
		assertThat(discarded.get("decidedAt").isNull()).isFalse();
		assertThat(discarded.get("sentAt").isNull()).isTrue();
		mvc.perform(json(post("/api/v1/budgets/" + id + "/discard"), "{\"version\":%d}".formatted(discarded.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(update(kit, id, discarded.get("version").asLong(), null, item(kit.restoration(), 1, null))).andExpect(status().isConflict());
	}

	// ---- leitura e papéis ----

	@Test
	void theReceptionistReadsButNeverWritesAndSeesTheSameBudget() throws Exception {
		Kit kit = kit();
		JsonNode created = createBudget(kit, "10.00", item(kit.restoration(), 1, null));
		String id = created.get("id").asString();

		JsonNode seen = getBudget(kit.clinic(), Role.RECEPTIONIST, id);

		assertThat(seen.get("totalAmount").asString()).isEqualTo("240.00");
		assertThat(seen.get("items")).hasSize(1);
		assertThat(seen.has("installments")).isFalse();
		mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), null, item(kit.restoration(), 1, null))).with(as(kit.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
		mvc.perform(update(kit, id, 0, null, item(kit.root(), 1, null)).with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(post("/api/v1/budgets/" + id + "/discard"), "{\"version\":0}").with(as(kit.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isForbidden());
	}

	@Test
	void theListFiltersByStatusPatientAndNumberAndPagesNewestFirst() throws Exception {
		Kit kit = kit();
		String otherPatient = createPatient(kit.clinic(), "João Lima");
		JsonNode one = createBudget(kit, null, item(kit.restoration(), 1, null));
		JsonNode two = createBudget(kit, null, item(kit.root(), 1, null));
		mvc.perform(json(post("/api/v1/budgets"), budgetBody(otherPatient, null, item(kit.restoration(), 1, null))).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated());
		forceStatus(kit.clinic(), two.get("id").asString(), "SENT");

		JsonNode all = read(mvc.perform(get("/api/v1/budgets").with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isOk()).andReturn());
		JsonNode sent = read(mvc.perform(get("/api/v1/budgets").param("status", "SENT").with(as(kit.clinic(), Role.RECEPTIONIST))).andReturn());
		JsonNode drafts = read(mvc.perform(get("/api/v1/budgets").param("status", "DRAFT").param("status", "REJECTED").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andReturn());
		JsonNode byNumber = read(mvc.perform(get("/api/v1/budgets").param("number", "1").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andReturn());
		JsonNode byPatient = read(mvc.perform(get("/api/v1/budgets").param("patientId", kit.patient()).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andReturn());
		JsonNode page = read(mvc.perform(get("/api/v1/budgets").param("size", "2").param("page", "1").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andReturn());

		assertThat(all.get("totalElements").asInt()).isEqualTo(3);
		assertThat(all.get("content").get(0).get("number").asInt()).as("mais recente primeiro").isEqualTo(3);
		assertThat(sent.get("content")).hasSize(1);
		assertThat(sent.get("content").get(0).get("id").asString()).isEqualTo(two.get("id").asString());
		assertThat(drafts.get("totalElements").asInt()).isEqualTo(2);
		assertThat(byNumber.get("content")).hasSize(1);
		assertThat(byNumber.get("content").get(0).get("id").asString()).isEqualTo(one.get("id").asString());
		assertThat(byPatient.get("totalElements").asInt()).isEqualTo(2);
		assertThat(page.get("content")).hasSize(1);
		assertThat(page.get("totalPages").asInt()).isEqualTo(2);
		assertThat(all.get("content").get(0).has("items")).isFalse();
		mvc.perform(get("/api/v1/budgets").param("size", "51").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
	}

	@Test
	void thePatientHistoryListsEveryStatusAndWorksForInactivePatients() throws Exception {
		Kit kit = kit();
		JsonNode created = createBudget(kit, null, item(kit.restoration(), 1, null));
		mvc.perform(json(patch("/api/v1/patients/" + kit.patient() + "/active"), "{\"active\":false,\"version\":0}").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());

		JsonNode history = read(mvc.perform(get("/api/v1/patients/" + kit.patient() + "/budgets").with(as(kit.clinic(), Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andReturn());

		assertThat(history.get("content")).hasSize(1);
		assertThat(history.get("content").get(0).get("id").asString()).isEqualTo(created.get("id").asString());
		mvc.perform(get("/api/v1/patients/" + UUID.randomUUID() + "/budgets").with(as(kit.clinic(), Role.RECEPTIONIST))).andExpect(status().isNotFound());
	}

	// ---- isolamento e papel no banco ----

	@Test
	void aBudgetOfAnotherClinicIs404EverywhereAndTheListNeverShowsIt() throws Exception {
		Kit kit = kit();
		Kit other = kit();
		JsonNode foreign = createBudget(other, null, item(other.restoration(), 1, null));
		String id = foreign.get("id").asString();

		mvc.perform(get("/api/v1/budgets/" + id).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
		mvc.perform(update(kit, id, 0, null, item(kit.restoration(), 1, null))).andExpect(status().isNotFound());
		mvc.perform(json(post("/api/v1/budgets/" + id + "/discard"), "{\"version\":0}").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/budgets").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(jsonPath("$.totalElements").value(0));
		mvc.perform(get("/api/v1/budgets").param("number", "1").with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void theDatabaseItselfHidesOtherClinicsAndRefusesTheReceptionistsWrites() throws Exception {
		Kit kit = kit();
		Kit other = kit();
		createBudget(other, null, item(other.restoration(), 1, null));
		createBudget(kit, null, item(kit.restoration(), 1, null));

		assertThat(asRole(kit.clinic(), Role.DENTIST_ADMIN, () -> jdbc.queryForList("SELECT number FROM core.budgets", Integer.class))).containsExactly(1);
		assertThat(asRole(kit.clinic(), Role.RECEPTIONIST, () -> jdbc.queryForList("SELECT id FROM core.budget_items", String.class))).hasSize(1);
		assertThatThrownBy(() -> asRole(kit.clinic(), Role.RECEPTIONIST, () -> jdbc.update("""
				INSERT INTO core.budgets (id, clinic_id, patient_id, created_by) VALUES (?, core.current_clinic_id(), ?::uuid, core.current_user_id())""",
				UUID.randomUUID(), kit.patient()))).satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).contains("42501"));
		assertThat(asRole(kit.clinic(), Role.RECEPTIONIST, () -> jdbc.update("UPDATE core.budgets SET notes = 'x'"))).isZero();
		assertThatThrownBy(() -> asRole(kit.clinic(), Role.DENTIST_ADMIN, () -> jdbc.update("DELETE FROM core.budgets")))
				.satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).contains("42501"));
	}
}
