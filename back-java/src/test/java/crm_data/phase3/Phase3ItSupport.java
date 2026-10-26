package crm_data.phase3;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import crm_data.phase2.Phase2ItSupport;
import crm_data.shared.security.Role;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/**
 * Base dos ITs da Fase 3 (comercial e financeiro). Reaproveita tudo da Fase 2 (clínica com dentista e recepção, token,
 * papéis, JSON, sessão forjada, paciente pela API) e, por ter a <strong>mesma</strong> configuração de contexto, o Spring
 * reaproveita o contexto e o contêiner PostgreSQL, que aplica {@code database/bootstrap} e todas as migrations
 * (V001–V029: o {@code DatabaseContainer} copia a pasta inteira). Cada teste cria suas clínicas; nada depende de dados
 * de outro. As features da fase acrescentam aqui os auxiliares de procedimento, orçamento, parcela e pagamento.
 */
public abstract class Phase3ItSupport extends Phase2ItSupport {

	/** Clínica com expediente e paciente ativo, mais dois procedimentos: restauração R$ 250,00 e canal R$ 900,00. */
	protected record Kit(Setup setup, String restoration, String root) {

		protected Clinic clinic() {
			return setup.clinic();
		}

		protected String patient() {
			return setup.patient();
		}
	}

	/** Corpo de procedimento válido; {@code price} em texto decimal. */
	protected static String procedureBody(String name, String price, int minutes, String color) {
		return "{\"name\":\"%s\",\"basePrice\":\"%s\",\"defaultDurationMinutes\":%d,\"color\":\"%s\"}".formatted(name, price, minutes,
				color);
	}

	/** Cria um procedimento ativo pela API (como o dentista) e devolve o id. */
	protected String createProcedure(Clinic clinic, String name, String price, int minutes) throws Exception {
		return read(mvc.perform(json(post("/api/v1/procedure-types"), procedureBody(name, price, minutes, "#3B82F6"))
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isCreated()).andReturn()).get("id").asString();
	}

	protected Kit kit() throws Exception {
		Setup setup = openClinic();
		return new Kit(setup, createProcedure(setup.clinic(), "Restauração", "250.00", 45),
				createProcedure(setup.clinic(), "Canal", "900.00", 90));
	}

	protected static String item(String procedure, int quantity, String discount) {
		return "{\"procedureTypeId\":\"%s\",\"quantity\":%d%s}".formatted(procedure, quantity,
				discount == null ? "" : ",\"discountAmount\":\"" + discount + "\"");
	}

	protected static String budgetBody(String patient, String discount, String... items) {
		return "{\"patientId\":\"%s\"%s,\"items\":[%s]}".formatted(patient, discount == null ? "" : ",\"discountAmount\":\"" + discount + "\"",
				String.join(",", items));
	}

	/** Cria um orçamento em rascunho pela API (como o dentista) e devolve a resposta. */
	protected JsonNode createBudget(Kit kit, String discount, String... items) throws Exception {
		return read(mvc.perform(json(post("/api/v1/budgets"), budgetBody(kit.patient(), discount, items)).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn());
	}

	protected JsonNode getBudget(Clinic clinic, Role role, String id) throws Exception {
		return read(mvc.perform(get("/api/v1/budgets/" + id).with(as(clinic, role))).andExpect(status().isOk()).andReturn());
	}

	/** Muda o status direto no banco, como superusuário com a sessão do dentista (o trigger de auditoria exige usuário). */
	protected void forceStatus(Clinic clinic, String budgetId, String status) {
		adminSql(clinic, "UPDATE core.budgets SET status = '%s' WHERE id = '%s'".formatted(status, budgetId));
	}

	/**
	 * Orçamento de R$ 800,00 (2 × 250,00 + 1 × 900,00 − 600,00 de desconto) aprovado pela API (rascunho → {@code SENT} no
	 * banco → {@code approve}). Devolve o id.
	 */
	protected String approvedBudgetOf(Kit kit) throws Exception {
		JsonNode draft = createBudget(kit, "600.00", item(kit.restoration(), 2, null), item(kit.root(), 1, null));
		String id = draft.get("id").asString();
		forceStatus(kit.clinic(), id, "SENT");
		long version = getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("version").asLong();
		mvc.perform(json(post("/api/v1/budgets/" + id + "/approve"), "{\"version\":%d}".formatted(version))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		return id;
	}

	/** Gera o plano AUTO do orçamento ({@code count} parcelas, vencendo todo mês a partir de 2027-03-01) e devolve a resposta. */
	protected JsonNode planOf(Kit kit, String budgetId, int count) throws Exception {
		return read(mvc.perform(json(post("/api/v1/budgets/" + budgetId + "/installments"),
				"{\"mode\":\"AUTO\",\"count\":%d,\"firstDueDate\":\"2027-03-01\"}".formatted(count)).with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andReturn());
	}

	/** Kit com um paciente e procedimentos escolhidos pelo teste (por exemplo com nome e preços sentinela). */
	protected Kit kitOf(Clinic clinic, String patient, String restoration, String root) {
		return new Kit(setupOf(clinic, patient), restoration, root);
	}
}
