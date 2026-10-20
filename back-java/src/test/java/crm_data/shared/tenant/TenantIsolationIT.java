package crm_data.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.support.DatabaseContainer;
import crm_data.support.RecordingStatementInspector;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import crm_data.support.tenant.TestUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integração backend ↔ banco real (scripts de {@code ../database}, papel {@code crm_app}): as duas camadas de
 * isolamento, a obrigatoriedade do tenant e o contrato do aplicativo com {@code app.clinic_id}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIsolationIT {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	JdbcTemplate jdbc; // conecta como crm_app
	@Autowired
	Environment environment;
	@PersistenceContext
	EntityManager entityManager;

	TransactionTemplate tx;
	Clinic clinicA;
	Clinic clinicB;

	@BeforeEach
	void seed() {
		tx = new TransactionTemplate(transactionManager);
		clinicA = TenantFixtures.clinicWithUsers(3);
		clinicB = TenantFixtures.clinicWithUsers(2);
		TenantContext.clear();
		RecordingStatementInspector.reset();
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private <T> T asTenant(UUID clinicId, java.util.function.Supplier<T> block) {
		return TenantRunner.call(clinicId, () -> tx.execute(status -> block.get()));
	}

	private static RequestPostProcessor tokenOf(UUID clinicId) {
		return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("clinic_id", clinicId.toString())
				.claim("role", "DENTIST_ADMIN"));
	}

	// ---- Conexão e schema ----

	@Test
	void applicationConnectsAsTheUnprivilegedApplicationRole() {
		var role = jdbc.queryForMap("""
				SELECT current_user AS who, r.rolsuper, r.rolbypassrls,
				       (SELECT count(*) FROM pg_class c WHERE c.relnamespace = 'core'::regnamespace
				                                          AND pg_get_userbyid(c.relowner) = current_user) AS owned
				  FROM pg_roles r WHERE r.rolname = current_user""");

		assertThat(role).containsEntry("who", "crm_app").containsEntry("rolsuper", false)
				.containsEntry("rolbypassrls", false).containsEntry("owned", 0L);
	}

	@Test
	void ddlAutoValidatePassesAgainstTheRealSchema() {
		// o contexto só sobe se o mapeamento (TestUser sobre core.users) bater com o schema entregue pelo banco
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
		assertThat(environment.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("core");
	}

	// ---- Camada 1 (filtro Hibernate) e camada 2 (RLS) ----

	@Test
	void hibernateFilterIsEnabledWithTheContextTenantInEveryTransaction() {
		asTenant(clinicA.id(), () -> {
			var filter = entityManager.unwrap(Session.class).getEnabledFilter(TenantAwareEntity.FILTER);
			assertThat(filter).isNotNull();
			assertThat(filter.getName()).isEqualTo(TenantAwareEntity.FILTER);
			return null;
		});

		asTenant(clinicB.id(), () -> {
			RecordingStatementInspector.reset();
			List<UUID> ids = entityManager.createQuery("select u.id from TestUser u", UUID.class).getResultList();
			assertThat(ids).containsExactlyInAnyOrderElementsOf(clinicB.userIds());
			assertThat(RecordingStatementInspector.statements()).anySatisfy(sql -> assertThat(sql).contains(".clinic_id = ?"));
			return null;
		});
	}

	@Test
	void rowLevelSecurityAloneBlocksCrossTenantReadWithTheHibernateFilterOff() {
		asTenant(clinicA.id(), () -> {
			Session session = entityManager.unwrap(Session.class);
			session.disableFilter(TenantAwareEntity.FILTER);
			assertThat(session.getEnabledFilter(TenantAwareEntity.FILTER)).isNull();

			RecordingStatementInspector.reset();
			List<UUID> viaJpql = entityManager.createQuery("select u.id from TestUser u", UUID.class).getResultList();
			assertThat(RecordingStatementInspector.statements()).noneSatisfy(sql -> assertThat(sql).contains(".clinic_id = ?"));
			assertThat(viaJpql).containsExactlyInAnyOrderElementsOf(clinicA.userIds()).doesNotContainAnyElementsOf(clinicB.userIds());

			Long viaNative = (Long) entityManager.createNativeQuery("select count(*) from core.users where clinic_id = ?1")
					.setParameter(1, clinicB.id()).getSingleResult();
			assertThat(viaNative).isZero();

			assertThat(entityManager.find(TestUser.class, clinicB.userIds().get(0))).isNull();
			return null;
		});
	}

	@Test
	void eachTenantSeesOnlyItsOwnRows() {
		assertThat(asTenant(clinicA.id(), () -> entityManager.createQuery("select u.id from TestUser u", UUID.class).getResultList()))
				.containsExactlyInAnyOrderElementsOf(clinicA.userIds());
		assertThat(asTenant(clinicB.id(), () -> entityManager.createQuery("select u.id from TestUser u", UUID.class).getResultList()))
				.containsExactlyInAnyOrderElementsOf(clinicB.userIds());
	}

	@Test
	void entityIsStampedWithTheContextTenantAndPersisted() {
		UUID id = UUID.randomUUID();
		asTenant(clinicA.id(), () -> {
			entityManager.persist(new TestUser(id, "Novo", id + "@teste.local"));
			return null;
		});

		assertThat(DatabaseContainer.admin().queryForObject("SELECT clinic_id FROM core.users WHERE id = ?", UUID.class, id))
				.isEqualTo(clinicA.id());
	}

	// ---- Escrita com clinic_id divergente ----

	@Test
	void applicationRejectsAnEntityWhoseClinicIdDiffersFromTheContext() throws Exception {
		TestUser foreign = new TestUser(UUID.randomUUID(), "Intruso", UUID.randomUUID() + "@teste.local");
		var field = TenantAwareEntity.class.getDeclaredField("clinicId");
		field.setAccessible(true);
		field.set(foreign, clinicB.id());

		assertThatThrownBy(() -> asTenant(clinicA.id(), () -> {
			entityManager.persist(foreign);
			entityManager.flush();
			return null;
		})).hasStackTraceContaining("diverge do tenant");
	}

	@Test
	void databaseRejectsInsertWithAnotherClinicIdEvenBypassingTheEntity() {
		assertThatThrownBy(() -> asTenant(clinicA.id(), () -> jdbc.update("""
				INSERT INTO core.users (id, clinic_id, name, email, password_hash, role)
				VALUES (?, ?, 'Intruso', ?, 'hash', 'RECEPTIONIST')""",
				UUID.randomUUID(), clinicB.id(), UUID.randomUUID() + "@teste.local")))
				.rootCause().isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42501"));
	}

	// ---- Sem tenant ----

	@Test
	void transactionWithoutTenantFailsWith401AndNothingReachesTheDatabase() {
		assertThatThrownBy(() -> tx.execute(status -> entityManager.createQuery("select u.id from TestUser u", UUID.class)
				.getResultList()))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.UNAUTHORIZED));

		assertThat(RecordingStatementInspector.statements()).isEmpty();
	}

	@Test
	void httpRequestWithoutTenantGets401AndNothingReachesTheDatabase() throws Exception {
		mvc.perform(get("/probe/tenant/users").with(user("sem-tenant")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://crm.local/problems/unauthorized"));

		assertThat(RecordingStatementInspector.statements()).isEmpty();
	}

	@Test
	void httpRequestUsesOnlyTheTokenTenantEvenWhenTheClientSendsAnotherClinicId() throws Exception {
		mvc.perform(get("/probe/tenant/users").param("clinicId", clinicB.id().toString()).with(tokenOf(clinicA.id())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(clinicA.userIds().size()))
				.andExpect(jsonPath("$[?(@ == '" + clinicB.userIds().get(0) + "')]").isEmpty());
	}

	// ---- Contrato com o banco ----

	@Test
	void tenantSettingIsLocalToTheTransactionAndMatchesDatabaseFunction() {
		UUID duringTx = asTenant(clinicA.id(), () -> jdbc.queryForObject("SELECT core.current_clinic_id()", UUID.class));
		assertThat(duringTx).isEqualTo(clinicA.id());

		// fora de transação (mesma conexão do pool): a variável voltou vazia, falha fechada
		assertThat(jdbc.queryForObject("SELECT core.current_clinic_id()", UUID.class)).isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM core.users", Long.class)).isZero();
	}

	@Test
	void transactionManagerIsTheTenantAwareOne() {
		assertThat(transactionManager).isInstanceOf(TenantJpaTransactionManager.class).isInstanceOf(JpaTransactionManager.class);
	}

	@Test
	void clinicIdIsNotUpdatableOnAManagedEntity() throws Exception {
		UUID userId = clinicA.userIds().get(0);
		var field = TenantAwareEntity.class.getDeclaredField("clinicId");
		field.setAccessible(true);

		asTenant(clinicA.id(), () -> {
			TestUser managed = entityManager.find(TestUser.class, userId);
			try {
				field.set(managed, clinicB.id());
			}
			catch (IllegalAccessException ex) {
				throw new IllegalStateException(ex);
			}
			managed.setName("Renomeado"); // força um UPDATE
			RecordingStatementInspector.reset();
			entityManager.flush();
			assertThat(RecordingStatementInspector.statements()).singleElement().asString()
					.startsWith("update core.users").doesNotContain("clinic_id");
			return null;
		});

		assertThat(DatabaseContainer.admin().queryForObject("SELECT clinic_id FROM core.users WHERE id = ?", UUID.class, userId))
				.isEqualTo(clinicA.id());
	}

	// ---- Desempenho ----

	@Test
	void tenantSetupAddsOnlyAFewMillisecondsPerTransaction() {
		// Mesma carga nos dois lados (BEGIN + SELECT 1 + COMMIT); a diferença é só o set_config + filtro do tenant.
		var plain = new TransactionTemplate(new JpaTransactionManager(entityManager.getEntityManagerFactory()));
		int rounds = 400;
		Runnable withTenant = () -> TenantRunner.run(clinicA.id(),
				() -> tx.executeWithoutResult(s -> jdbc.queryForObject("SELECT 1", Integer.class)));
		Runnable withoutTenant = () -> plain.executeWithoutResult(s -> jdbc.queryForObject("SELECT 1", Integer.class));

		for (int i = 0; i < 100; i++) { // aquecimento
			withTenant.run();
			withoutTenant.run();
		}
		double tenantMs = averageMillis(withTenant, rounds);
		double plainMs = averageMillis(withoutTenant, rounds);
		double overhead = tenantMs - plainMs;
		System.out.printf("F02 sobrecarga de set_config + filtro por transação: %.3f ms (com tenant %.3f ms, sem %.3f ms)%n",
				overhead, tenantMs, plainMs);

		// meta de projeto: < 1 ms (medido ~0,7 ms localmente); o teto de 3 ms evita falso negativo em CI compartilhado
		assertThat(overhead).isLessThan(3.0);
	}

	private static double averageMillis(Runnable action, int rounds) {
		long start = System.nanoTime();
		for (int i = 0; i < rounds; i++) {
			action.run();
		}
		return (System.nanoTime() - start) / 1_000_000.0 / rounds;
	}
}
