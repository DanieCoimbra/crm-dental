package crm_data.clinic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import crm_data.auth.domain.Clinic;
import crm_data.auth.repository.ClinicRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class ClinicZonesServiceTest {

	private final ClinicRepository clinics = mock(ClinicRepository.class);
	private final ClinicZonesService zones = new ClinicZonesService(clinics);
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
	void returnsTheZoneOfTheClinicOfTheToken() {
		Clinic clinic = new Clinic(clinicId, "Clínica");
		clinic.setTimezone("America/Manaus");
		when(clinics.findById(clinicId)).thenReturn(Optional.of(clinic));

		assertThat(zones.zone()).isEqualTo(ZoneId.of("America/Manaus"));
	}

	@Test
	void newClinicsDefaultToSaoPaulo() {
		when(clinics.findById(clinicId)).thenReturn(Optional.of(new Clinic(clinicId, "Clínica")));

		assertThat(zones.zone()).isEqualTo(ZoneId.of("America/Sao_Paulo"));
	}

	@Test
	void unknownClinicIsNotFound() {
		when(clinics.findById(clinicId)).thenReturn(Optional.empty());

		assertThatThrownBy(zones::zone).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}

	@Test
	void invalidZoneWrittenOutsideTheApiIsAnInternalFailure() {
		Clinic clinic = new Clinic(clinicId, "Clínica");
		clinic.setTimezone("Mars/Phobos");
		when(clinics.findById(clinicId)).thenReturn(Optional.of(clinic));

		assertThatThrownBy(zones::zone).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("Phobos");
	}
}
