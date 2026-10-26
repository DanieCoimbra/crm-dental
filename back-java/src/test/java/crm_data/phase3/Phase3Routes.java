package crm_data.phase3;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * As rotas novas da Fase 3 (seção 3.2 da spec global) e quem pode chamá-las. Fonte única da matriz papel × endpoint: a
 * F01 a usa contra a {@code SecurityConfig} real; a F08 a reutiliza nos testes web e de integração de cada feature.
 *
 * <p>{@code receptionistAllowed} é {@code true} só nas leituras de procedimentos, orçamentos e PDFs (C2): parcela,
 * pagamento, relatórios e ficha financeira, e toda escrita, são do dentista.
 */
public final class Phase3Routes {

	private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID DOC = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID PATIENT = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private Phase3Routes() {
	}

	public record Route(String label, HttpMethod method, String path, String query, String body,
			boolean receptionistAllowed) {

		/** Requisição pronta (sem token). */
		public MockHttpServletRequestBuilder request() {
			MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path + (query == null ? "" : "?" + query));
			return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
	}

	private static Route read(String path, boolean receptionistAllowed) {
		return read(path, null, receptionistAllowed);
	}

	private static Route read(String path, String query, boolean receptionistAllowed) {
		return new Route("GET " + path, HttpMethod.GET, path, query, null, receptionistAllowed);
	}

	private static Route write(HttpMethod method, String path, String body, boolean receptionistAllowed) {
		return new Route(method.name() + " " + path, method, path, null, body, receptionistAllowed);
	}

	/** As 29 rotas, na ordem do contrato. */
	public static List<Route> all() {
		String version = "{\"version\":0}";
		String procedure = "{\"name\":\"Restauração\",\"basePrice\":\"250.00\",\"defaultDurationMinutes\":45,\"color\":\"#3B82F6\"}";
		String budget = "{\"patientId\":\"" + PATIENT + "\",\"items\":[{\"procedureTypeId\":\"" + ID + "\",\"quantity\":1}]}";
		String plan = "{\"mode\":\"AUTO\",\"count\":3,\"firstDueDate\":\"2026-11-27\"}";
		String window = "from=2026-10-01&to=2026-10-31";
		return List.of(
				read("/api/v1/procedure-types", true),
				read("/api/v1/procedure-types/" + ID, true),
				write(HttpMethod.POST, "/api/v1/procedure-types", procedure, false),
				write(HttpMethod.PUT, "/api/v1/procedure-types/" + ID, procedure.replace("}", ",\"version\":0}"), false),
				write(HttpMethod.PATCH, "/api/v1/procedure-types/" + ID + "/active", "{\"active\":false,\"version\":0}", false),
				write(HttpMethod.POST, "/api/v1/budgets", budget, false),
				read("/api/v1/budgets", true),
				read("/api/v1/budgets/" + ID, true),
				write(HttpMethod.PUT, "/api/v1/budgets/" + ID, budget.replace("}", ",\"version\":0}"), false),
				write(HttpMethod.POST, "/api/v1/budgets/" + ID + "/send", version, false),
				write(HttpMethod.POST, "/api/v1/budgets/" + ID + "/approve", version, false),
				write(HttpMethod.POST, "/api/v1/budgets/" + ID + "/reject", version, false),
				write(HttpMethod.POST, "/api/v1/budgets/" + ID + "/discard", version, false),
				read("/api/v1/budgets/" + ID + "/documents", true),
				read("/api/v1/budgets/" + ID + "/documents/" + DOC + "/download-url", true),
				write(HttpMethod.POST, "/api/v1/budgets/" + ID + "/documents", null, false),
				read("/api/v1/patients/" + PATIENT + "/budgets", true),
				read("/api/v1/budgets/" + ID + "/installments", false),
				write(HttpMethod.POST, "/api/v1/budgets/" + ID + "/installments", plan, false),
				write(HttpMethod.PUT, "/api/v1/budgets/" + ID + "/installments", plan, false),
				write(HttpMethod.PATCH, "/api/v1/installments/" + ID + "/due-date", "{\"dueDate\":\"2026-12-01\",\"version\":0}", false),
				read("/api/v1/installments/" + ID + "/payments", false),
				write(HttpMethod.POST, "/api/v1/installments/" + ID + "/payments", "{\"amount\":\"100.00\",\"method\":\"PIX\"}", false),
				write(HttpMethod.POST, "/api/v1/payments/" + ID + "/reversal", null, false),
				read("/api/v1/finance/receivables", window, false),
				read("/api/v1/finance/overdue", false),
				read("/api/v1/finance/cash-flow", window, false),
				read("/api/v1/finance/revenue", window, false),
				read("/api/v1/patients/" + PATIENT + "/financial", false));
	}
}
