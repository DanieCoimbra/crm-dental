package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.SqlRecordingDataSourceConfig;
import tools.jackson.databind.JsonNode;

/**
 * O formato do SQL que a Fase 3 envia ao banco (pontos 8 e 12 da seção 9 da spec global): as entidades sem
 * {@code @Version} ({@code Payment}, {@code BudgetItem}, {@code BudgetDocument}) entram por {@code persist} sem
 * {@code SELECT} prévio e nunca recebem {@code UPDATE}; a trava {@code FOR UPDATE} do orçamento aparece no SQL e não
 * deixa linha de auditoria quando a operação é recusada.
 */
@Import(SqlRecordingDataSourceConfig.class)
class Phase3SqlShapeIT extends Phase3ItSupport {

	private static List<String> statements() {
		return SqlRecordingDataSourceConfig.prepared().stream().map(sql -> sql.toLowerCase(Locale.ROOT)).toList();
	}

	/** {@code select … from core.<tabela> alias where alias.id=?}: a leitura por chave que o {@code merge} faria antes do insert. */
	private static boolean selectsByIdOf(String sql, String table) {
		return Pattern.compile("^select .* from core\\." + table + " \\w+ where \\w+\\.id\\s*=\\s*\\?").matcher(sql).find();
	}

	private static int firstInsertInto(List<String> sql, String table) {
		for (int i = 0; i < sql.size(); i++) {
			if (sql.get(i).startsWith("insert into core." + table + " ")) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void immutableEntitiesArePersistedWithoutAPriorSelectAndNeverUpdated() throws Exception {
		Kit kit = kit();
		SqlRecordingDataSourceConfig.reset();
		JsonNode draft = createBudget(kit, null, item(kit.restoration(), 2, null), item(kit.root(), 1, null));
		List<String> created = statements();
		int firstItem = firstInsertInto(created, "budget_items");
		assertThat(firstItem).as("o item foi inserido").isGreaterThanOrEqualTo(0);
		assertThat(created.subList(0, firstItem)).as("nenhum SELECT por chave do item antes do INSERT").noneMatch(sql -> selectsByIdOf(sql, "budget_items"));
		assertThat(created).noneMatch(sql -> sql.startsWith("update core.budget_items"));

		SqlRecordingDataSourceConfig.reset();
		String id = draft.get("id").asString();
		mvc.perform(json(post("/api/v1/budgets/" + id + "/send"), "{\"version\":%d}".formatted(draft.get("version").asLong()))
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		List<String> sent = statements();
		int firstDocument = firstInsertInto(sent, "budget_documents");
		assertThat(firstDocument).as("o metadado do PDF foi inserido").isGreaterThanOrEqualTo(0);
		assertThat(sent.subList(0, firstDocument)).as("nenhum SELECT por chave do documento antes do INSERT").noneMatch(sql -> selectsByIdOf(sql, "budget_documents"));
		assertThat(sent).noneMatch(sql -> sql.startsWith("update core.budget_documents"));

		mvc.perform(json(post("/api/v1/budgets/" + id + "/approve"), "{\"version\":%d}".formatted(
				getBudget(kit.clinic(), Role.DENTIST_ADMIN, id).get("version").asLong())).with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isOk());
		String installment = planOf(kit, id, 1).get("installments").get(0).get("id").asString();
		SqlRecordingDataSourceConfig.reset();
		mvc.perform(json(post("/api/v1/installments/" + installment + "/payments"), "{\"amount\":\"100.00\",\"method\":\"PIX\"}")
				.with(as(kit.clinic(), Role.DENTIST_ADMIN))).andExpect(status().isCreated());
		List<String> paid = statements();
		int firstPayment = firstInsertInto(paid, "payments");
		assertThat(firstPayment).as("o pagamento foi inserido").isGreaterThanOrEqualTo(0);
		assertThat(paid.subList(0, firstPayment)).as("nenhum SELECT por chave do pagamento antes do INSERT").noneMatch(sql -> selectsByIdOf(sql, "payments"));
		assertThat(paid.subList(0, firstPayment)).as("a entidade Installment nunca é carregada no pagamento (o trigger a muda por baixo)")
				.noneMatch(sql -> sql.startsWith("select") && sql.contains("from core.installments") && sql.contains("created_by"));
		assertThat(paid).noneMatch(sql -> sql.startsWith("update core.payments") || sql.startsWith("delete from core.payments"));
	}

	@Test
	void theBudgetLockShowsInTheSqlAndARefusedOperationLeavesNoAuditRow() throws Exception {
		Kit kit = kit();
		JsonNode draft = createBudget(kit, null, item(kit.restoration(), 1, null));
		String id = draft.get("id").asString();
		forceStatus(kit.clinic(), id, "SENT");
		long auditBefore = DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.audit_log WHERE clinic_id = ?::uuid", Long.class, kit.clinic().id().toString());
		SqlRecordingDataSourceConfig.reset();

		mvc.perform(json(post("/api/v1/budgets/" + id + "/approve"), "{\"version\":999}").with(as(kit.clinic(), Role.DENTIST_ADMIN)))
				.andExpect(status().isConflict());

		assertThat(statements()).as("a trava da linha do orçamento aparece no SQL").anyMatch(sql -> sql.contains("from core.budgets") && sql.contains(" for "));
		assertThat(DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.audit_log WHERE clinic_id = ?::uuid", Long.class, kit.clinic().id().toString()))
				.as("a trava não gera linha de auditoria").isEqualTo(auditBefore);
	}
}
