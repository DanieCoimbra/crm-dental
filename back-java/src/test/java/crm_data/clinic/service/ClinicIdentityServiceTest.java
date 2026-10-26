package crm_data.clinic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import crm_data.auth.domain.Clinic;
import crm_data.auth.repository.ClinicRepository;
import crm_data.clinic.service.ClinicIdentity.ClinicInfo;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class ClinicIdentityServiceTest {

	private final ClinicRepository clinics = mock(ClinicRepository.class);
	private final ClinicIdentityService identity = new ClinicIdentityService(clinics);
	private final UUID clinicId = UUID.randomUUID();

	@BeforeEach
	void tenant() {
		TenantContext.set(clinicId, UUID.randomUUID(), Role.DENTIST_ADMIN);
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	@Test
	void returnsNameAddressAndPhoneOfTheClinicOfTheToken() {
		Clinic clinic = mock(Clinic.class);
		when(clinic.getName()).thenReturn("Clínica Sorriso");
		when(clinic.getAddress()).thenReturn("Rua A, 10");
		when(clinic.getPhone()).thenReturn("1133334444");
		when(clinics.findById(clinicId)).thenReturn(Optional.of(clinic));

		ClinicInfo info = identity.current();

		assertThat(info.name()).isEqualTo("Clínica Sorriso");
		assertThat(info.address()).isEqualTo("Rua A, 10");
		assertThat(info.phone()).isEqualTo("1133334444");
	}

	@Test
	void addressAndPhoneMayBeNull() {
		Clinic clinic = mock(Clinic.class);
		when(clinic.getName()).thenReturn("Clínica Sorriso");
		when(clinics.findById(clinicId)).thenReturn(Optional.of(clinic));

		ClinicInfo info = identity.current();

		assertThat(info.address()).isNull();
		assertThat(info.phone()).isNull();
	}

	@Test
	void unknownClinicIs404() {
		when(clinics.findById(clinicId)).thenReturn(Optional.empty());

		assertThatThrownBy(identity::current).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}

	@Test
	void theToStringNeverPrintsTheClinicData() {
		assertThat(new ClinicInfo("Clínica Sorriso", "Rua A, 10", "1133334444").toString())
				.doesNotContain("Sorriso", "Rua A", "1133334444");
	}
}
