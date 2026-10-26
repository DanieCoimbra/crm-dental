package crm_data.phase3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.phase3.Phase3Routes.Route;
import crm_data.shared.security.Role;
import crm_data.support.PlatformWebTest;

/**
 * Regras de rota da Fase 3 contra a {@code SecurityConfig} <strong>real</strong>. As rotas ainda não têm controlador
 * (cada feature entrega o seu), então a autorização, que roda antes do roteamento, é o que se vê: recepção em rota do
 * dentista → 403; sem token → 401; as rotas permitidas chegam ao roteador (404 por não haver controlador, nunca
 * 401/403). Quando as features chegarem, a F08 reaproveita {@link Phase3Routes} nas matrizes completas.
 */
@PlatformWebTest
class Phase3SecurityRulesTest {

	@Autowired
	MockMvc mvc;

	private static RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("clinic_id", UUID.randomUUID().toString())
				.claim("role", role.name())).authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private int statusOf(Route route, RequestPostProcessor token) throws Exception {
		var request = route.request();
		return mvc.perform(token == null ? request : request.with(token)).andReturn().getResponse().getStatus();
	}

	@Test
	void theRouteListIsCompleteAndWithoutDuplicates() {
		assertThat(Phase3Routes.all()).hasSize(29);
		assertThat(Phase3Routes.all().stream().map(Route::label).distinct()).hasSize(29);
		assertThat(Phase3Routes.all().stream().filter(Route::receptionistAllowed).map(Route::label)).containsExactly(
				"GET /api/v1/procedure-types", "GET /api/v1/procedure-types/11111111-1111-1111-1111-111111111111",
				"GET /api/v1/budgets", "GET /api/v1/budgets/11111111-1111-1111-1111-111111111111",
				"GET /api/v1/budgets/11111111-1111-1111-1111-111111111111/documents",
				"GET /api/v1/budgets/11111111-1111-1111-1111-111111111111/documents/22222222-2222-2222-2222-222222222222/download-url",
				"GET /api/v1/patients/33333333-3333-3333-3333-333333333333/budgets");
	}

	@Test
	void withoutTokenEveryRouteIs401() throws Exception {
		for (Route route : Phase3Routes.all()) {
			assertThat(statusOf(route, null)).as(route.label()).isEqualTo(401);
		}
	}

	@Test
	void receptionistIs403OnEveryWriteAndOnAllFinanceRoutesAndPassesOnTheReads() throws Exception {
		for (Route route : Phase3Routes.all()) {
			int code = statusOf(route, as(Role.RECEPTIONIST));
			if (route.receptionistAllowed()) {
				assertThat(code).as(route.label()).isNotIn(401, 403);
			}
			else {
				assertThat(code).as(route.label()).isEqualTo(403);
			}
		}
	}

	@Test
	void theDentistPassesTheAuthorizationOnEveryRoute() throws Exception {
		for (Route route : Phase3Routes.all()) {
			assertThat(statusOf(route, as(Role.DENTIST_ADMIN))).as(route.label()).isNotIn(401, 403);
		}
	}

	@Test
	void everyRouteResponseOfThePhaseIsMarkedNoStore() throws Exception {
		for (Route route : Phase3Routes.all()) {
			String cache = mvc.perform(route.request().with(as(Role.DENTIST_ADMIN))).andReturn().getResponse()
					.getHeader("Cache-Control");
			assertThat(cache).as(route.label()).isEqualTo("no-store");
		}
	}
}
