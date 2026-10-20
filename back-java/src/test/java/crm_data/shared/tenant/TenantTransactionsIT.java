package crm_data.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import crm_data.shared.error.BusinessException;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures;
import crm_data.support.tenant.TestUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** {@link TenantTransactions}: tenant explícito + transação, com rollback e restauração do contexto. */
@SpringBootTest
@ActiveProfiles("test")
class TenantTransactionsIT {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	TenantTransactions tenantTransactions;
	@PersistenceContext
	EntityManager entityManager;

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private long usersNamed(String name) {
		return DatabaseContainer.admin().queryForObject("SELECT count(*) FROM core.users WHERE name = ?", Long.class, name);
	}

	@Test
	void commitsUnderTheGivenTenantAndLeavesNoContextBehind() {
		UUID clinicId = TenantFixtures.clinicWithUsers(0).id();
		String name = "Comitado " + UUID.randomUUID();

		UUID seen = tenantTransactions.execute(clinicId, () -> {
			UUID id = UUID.randomUUID();
			entityManager.persist(new TestUser(id, name, id + "@teste.local"));
			return TenantContext.clinicId();
		});

		assertThat(seen).isEqualTo(clinicId);
		assertThat(usersNamed(name)).isEqualTo(1L);
		assertThat(TenantContext.isPresent()).isFalse();
	}

	@Test
	void anExceptionInsideTheBlockRollsBackAndRestoresThePreviousContext() {
		UUID clinicId = TenantFixtures.clinicWithUsers(0).id();
		UUID previousClinic = UUID.randomUUID();
		TenantContext.set(previousClinic, null, null);
		String name = "Desfeito " + UUID.randomUUID();

		assertThatThrownBy(() -> tenantTransactions.run(clinicId, () -> {
			UUID id = UUID.randomUUID();
			entityManager.persist(new TestUser(id, name, id + "@teste.local"));
			entityManager.flush(); // o INSERT já foi enviado ao banco antes da falha
			throw new IllegalStateException("falha no meio do bloco");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(usersNamed(name)).as("rollback").isZero();
		assertThat(TenantContext.clinicId()).as("contexto anterior restaurado").isEqualTo(previousClinic);
	}

	@Test
	void runExecutesTheBlockInsideTheTenantTransaction() {
		UUID clinicId = TenantFixtures.clinicWithUsers(2).id();

		tenantTransactions.run(clinicId, () -> {
			Long visible = (Long) entityManager.createQuery("select count(u) from TestUser u").getSingleResult();
			assertThat(visible).isEqualTo(2L);
		});
	}

	@Test
	void switchingTenantInsideAnOpenTransactionIsRefused() {
		UUID clinicA = TenantFixtures.clinicWithUsers(0).id();
		UUID clinicB = TenantFixtures.clinicWithUsers(0).id();

		assertThatThrownBy(() -> tenantTransactions.run(clinicA, () -> tenantTransactions.run(clinicB, () -> { })))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("trocar de tenant");
	}

	@Test
	void nullTenantIsRejected() {
		assertThatThrownBy(() -> tenantTransactions.run(null, () -> { })).isInstanceOf(NullPointerException.class);
		assertThat(BusinessException.class).isNotNull();
	}
}
