package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import crm_data.phase3.Phase3Routes.Route;
import crm_data.shared.security.Role;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * A matriz papel × rota da Fase 3 contra o contexto completo e o PostgreSQL real, com tokens de usuários reais da
 * clínica: sem token → 401; recepção → 403 em toda rota do dentista e passagem nas leituras comerciais; dentista passa em
 * todas. Nenhuma rota responde 5xx, nem com ids que não existem, e toda resposta da fase é {@code no-store}. A mesma lista
 * ({@link Phase3Routes}) alimenta o teste de regras da {@code SecurityConfig} sem banco.
 */
class Phase3RoleMatrixIT extends Phase3ItSupport {

	private int statusOf(Route route, Clinic clinic, Role role) throws Exception {
		var request = route.request();
		return mvc.perform(role == null ? request : request.with(as(clinic, role))).andReturn().getResponse().getStatus();
	}

	@Test
	void everyRouteOfThePhaseObeysTheRoleMatrixAndNeverAnswers5xx() throws Exception {
		Clinic clinic = openClinic().clinic();

		for (Route route : Phase3Routes.all()) {
			assertThat(statusOf(route, clinic, null)).as("%s sem token", route.label()).isEqualTo(401);

			int dentist = statusOf(route, clinic, Role.DENTIST_ADMIN);
			assertThat(dentist).as("%s dentista", route.label()).isNotIn(401, 403).isLessThan(500);

			int receptionist = statusOf(route, clinic, Role.RECEPTIONIST);
			if (route.receptionistAllowed()) {
				assertThat(receptionist).as("%s recepção (leitura permitida)", route.label()).isNotIn(401, 403).isLessThan(500);
			}
			else {
				assertThat(receptionist).as("%s recepção", route.label()).isEqualTo(403);
			}
		}
	}

	@Test
	void everyRouteResponseIsMarkedNoStore() throws Exception {
		Clinic clinic = openClinic().clinic();

		for (Route route : Phase3Routes.all()) {
			var response = mvc.perform(route.request().with(as(clinic, Role.DENTIST_ADMIN))).andReturn().getResponse();
			assertThat(response.getHeader("Cache-Control")).as(route.label()).isEqualTo("no-store");
		}
	}

	@Test
	void theRouteListCoversEveryRouteOfTheContracts() {
		assertThat(Phase3Routes.all()).hasSize(29);
		assertThat(Phase3Routes.all().stream().map(Route::label).distinct()).hasSize(29);
	}
}
