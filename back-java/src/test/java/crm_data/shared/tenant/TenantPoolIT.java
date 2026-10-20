package crm_data.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * Pool com UMA única conexão: o tenant de uma requisição jamais pode aparecer na seguinte, mesmo reutilizando
 * exatamente a mesma conexão física.
 */
@SpringBootTest(properties = { "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=1" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantPoolIT {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private static RequestPostProcessor tokenOf(UUID clinicId) {
		return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("clinic_id", clinicId.toString())
				.claim("role", "DENTIST_ADMIN"));
	}

	@Test
	void sameConnectionCarriesNoTenantFromOneTransactionToTheNext() {
		Clinic a = TenantFixtures.clinicWithUsers(2);
		Clinic b = TenantFixtures.clinicWithUsers(1);
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		Integer pidA = TenantRunner.call(a.id(), () -> tx.execute(s -> {
			assertThat(jdbc.queryForObject("SELECT current_setting('app.clinic_id')", String.class)).isEqualTo(a.id().toString());
			assertThat(jdbc.queryForObject("SELECT count(*) FROM core.users", Long.class)).isEqualTo(2L);
			return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
		}));

		// fora de transação, na mesma (única) conexão física
		Integer pidOutside = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
		assertThat(pidOutside).as("a conexão do pool é reaproveitada").isEqualTo(pidA);
		assertThat(jdbc.queryForObject("SELECT coalesce(current_setting('app.clinic_id', true), '')", String.class)).isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM core.users", Long.class)).isZero();

		// próxima transação, outro tenant, mesma conexão: só enxerga o seu
		Integer pidB = TenantRunner.call(b.id(), () -> tx.execute(s -> {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM core.users", Long.class)).isEqualTo(1L);
			return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
		}));
		assertThat(pidB).isEqualTo(pidA);
	}

	@Test
	void consecutiveHttpRequestsOnAOneConnectionPoolDoNotLeakTenants() throws Exception {
		Clinic a = TenantFixtures.clinicWithUsers(3);
		Clinic b = TenantFixtures.clinicWithUsers(1);

		mvc.perform(get("/probe/tenant/users").with(tokenOf(a.id())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
		mvc.perform(get("/probe/tenant/users").with(tokenOf(b.id())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0]").value(b.userIds().get(0).toString()));
		// requisição sem tenant logo depois de duas com tenant: 401, nunca dados residuais
		mvc.perform(get("/probe/tenant/users").with(user("sem-tenant"))).andExpect(status().isUnauthorized());
		mvc.perform(get("/probe/tenant/users").with(tokenOf(a.id())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
	}
}
