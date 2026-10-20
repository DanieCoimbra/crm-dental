package crm_data.clinic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.auth.domain.Clinic;
import crm_data.auth.repository.ClinicRepository;
import crm_data.clinic.service.ClinicService.ClinicData;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class ClinicServiceTest {

	private final ClinicRepository clinics = mock(ClinicRepository.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final ClinicService service = new ClinicService(clinics, events);

	private final UUID clinicId = UUID.randomUUID();
	private Clinic clinic;

	@BeforeEach
	void setUp() {
		clinic = new Clinic(clinicId, "Clínica Sorriso");
		ReflectionTestUtils.setField(clinic, "version", 4L);
		TenantContext.set(clinicId, UUID.randomUUID(), Role.DENTIST_ADMIN);
		when(clinics.findById(clinicId)).thenReturn(Optional.of(clinic));
		when(clinics.saveAndFlush(any(Clinic.class))).thenAnswer(call -> call.getArgument(0));
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	@Test
	void readsOnlyTheClinicOfTheTenant() {
		assertThat(service.get()).isSameAs(clinic);
		verify(clinics).findById(clinicId);
	}

	@Test
	void missingClinicIsNotFound() {
		when(clinics.findById(clinicId)).thenReturn(Optional.empty());

		assertThatThrownBy(service::get).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.NOT_FOUND));
	}

	@Test
	void updatesAllFieldsWhenTheVersionMatchesAndPublishesTheEvent() {
		Clinic updated = service.update(new ClinicData("Clínica Nova", "12.345.678/0001-90", "11 3333-4444", "contato@clinica.com", "Rua A, 10"), 4L);

		assertThat(updated.getName()).isEqualTo("Clínica Nova");
		assertThat(updated.getDocument()).isEqualTo("12.345.678/0001-90");
		assertThat(updated.getPhone()).isEqualTo("11 3333-4444");
		assertThat(updated.getEmail()).isEqualTo("contato@clinica.com");
		assertThat(updated.getAddress()).isEqualTo("Rua A, 10");
		verify(events).publishEvent(new ClinicEvents.Updated(clinicId));
	}

	@Test
	void optionalFieldsCanBeClearedWithNull() {
		clinic.setPhone("11 3333-4444");

		Clinic updated = service.update(new ClinicData("Clínica", null, null, null, null), 4L);

		assertThat(updated.getPhone()).isNull();
	}

	@Test
	void staleVersionIsConflictAndNothingIsSaved() {
		assertThatThrownBy(() -> service.update(new ClinicData("Outra", null, null, null, null), 3L))
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(ProblemTypes.CONFLICT));

		assertThat(clinic.getName()).isEqualTo("Clínica Sorriso");
		verify(clinics, never()).saveAndFlush(any());
		verifyNoInteractions(events);
	}
}
