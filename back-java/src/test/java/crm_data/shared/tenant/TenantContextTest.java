package crm_data.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;

class TenantContextTest {

	private static final UUID CLINIC = UUID.randomUUID();
	private static final UUID OTHER_CLINIC = UUID.randomUUID();
	private static final UUID USER = UUID.randomUUID();

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	@Test
	void withoutTenantEveryAccessorThrowsUnauthorized() {
		assertThat(TenantContext.isPresent()).isFalse();
		for (Runnable accessor : new Runnable[] { TenantContext::clinicId, TenantContext::userId, TenantContext::role }) {
			assertThatThrownBy(accessor::run).isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.UNAUTHORIZED));
		}
	}

	@Test
	void returnsWhatWasSet() {
		TenantContext.set(CLINIC, USER, Role.DENTIST_ADMIN);

		assertThat(TenantContext.isPresent()).isTrue();
		assertThat(TenantContext.clinicId()).isEqualTo(CLINIC);
		assertThat(TenantContext.userId()).isEqualTo(USER);
		assertThat(TenantContext.role()).isEqualTo(Role.DENTIST_ADMIN);
	}

	@Test
	void clearRemovesEverything() {
		TenantContext.set(CLINIC, USER, Role.RECEPTIONIST);
		TenantContext.clear();

		assertThat(TenantContext.isPresent()).isFalse();
		assertThatThrownBy(TenantContext::clinicId).isInstanceOf(BusinessException.class);
	}

	@Test
	void clinicIdIsRequired() {
		assertThatThrownBy(() -> TenantContext.set(null, USER, Role.RECEPTIONIST))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void contextIsPerThread() throws Exception {
		TenantContext.set(CLINIC, USER, Role.DENTIST_ADMIN);
		AtomicBoolean otherThreadSawIt = new AtomicBoolean(true);

		Thread other = new Thread(() -> otherThreadSawIt.set(TenantContext.isPresent()));
		other.start();
		other.join();

		assertThat(otherThreadSawIt).isFalse();
		assertThat(TenantContext.clinicId()).isEqualTo(CLINIC);
	}

	@Test
	void idProviderDeliversTheContextTenant() {
		TenantContext.set(CLINIC, USER, Role.DENTIST_ADMIN);
		assertThat(TenantIdProvider.currentClinicId()).isEqualTo(CLINIC);

		TenantContext.clear();
		assertThatThrownBy(TenantIdProvider::currentClinicId).isInstanceOf(BusinessException.class);
	}

	// ---- TenantRunner ----

	@Test
	void runnerSetsTheTenantOnlyDuringTheBlock() {
		String seen = TenantRunner.call(CLINIC, () -> TenantContext.clinicId().toString());

		assertThat(seen).isEqualTo(CLINIC.toString());
		assertThat(TenantContext.isPresent()).isFalse();
	}

	@Test
	void runnerRestoresThePreviousContextEvenWhenTheBlockFails() {
		TenantContext.set(CLINIC, USER, Role.DENTIST_ADMIN);

		assertThatThrownBy(() -> TenantRunner.run(OTHER_CLINIC, () -> {
			assertThat(TenantContext.clinicId()).isEqualTo(OTHER_CLINIC);
			throw new IllegalArgumentException("falha do bloco");
		})).isInstanceOf(IllegalArgumentException.class);

		assertThat(TenantContext.clinicId()).isEqualTo(CLINIC);
		assertThat(TenantContext.userId()).isEqualTo(USER);
	}

	@Test
	void runnerInTheSameTenantKeepsUserAndRole() {
		TenantContext.set(CLINIC, USER, Role.DENTIST_ADMIN);

		TenantRunner.run(CLINIC, () -> {
			assertThat(TenantContext.userId()).isEqualTo(USER);
			assertThat(TenantContext.role()).isEqualTo(Role.DENTIST_ADMIN);
		});
	}

	@Test
	void runnerBlockHasNoUserOrRole() {
		TenantRunner.run(CLINIC, () -> {
			assertThat(TenantContext.clinicId()).isEqualTo(CLINIC);
			assertThatThrownBy(TenantContext::userId).isInstanceOf(BusinessException.class);
			assertThatThrownBy(TenantContext::role).isInstanceOf(BusinessException.class);
		});
	}

	@Test
	void runnerRejectsNullArguments() {
		assertThatThrownBy(() -> TenantRunner.run(null, () -> { })).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> TenantRunner.run(CLINIC, null)).isInstanceOf(NullPointerException.class);
	}
}
