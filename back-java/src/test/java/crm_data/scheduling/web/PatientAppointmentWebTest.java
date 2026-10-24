package crm_data.scheduling.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.patient.service.PatientBrief;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.service.AppointmentService;
import crm_data.scheduling.service.AppointmentService.AppointmentView;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Histórico de agendamentos do paciente: os dois papéis, paginação limitada e resposta sem prontuário. */
@WebMvcTest(PatientAppointmentController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class PatientAppointmentWebTest {

	@Autowired
	MockMvc mvc;
	@MockitoBean
	AppointmentService appointments;

	private final UUID patientId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", UUID.randomUUID().toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private AppointmentView view() {
		Instant start = Instant.parse("2026-11-03T13:00:00Z");
		Appointment appointment = new Appointment(UUID.randomUUID(), patientId, start, start.plusSeconds(3600), "obs", userId);
		appointment.setStatus(AppointmentStatus.COMPLETED);
		return new AppointmentView(appointment, new PatientBrief(patientId, "Maria Souza", "11987654321"));
	}

	@ParameterizedTest
	@EnumSource(Role.class)
	void bothRolesReadThePagedHistoryWithTheAppointmentFieldsOnly(Role role) throws Exception {
		when(appointments.historyOf(eq(patientId), anyInt(), anyInt()))
				.thenReturn(new PageImpl<>(List.of(view()), PageRequest.of(0, 20), 1));

		mvc.perform(get("/api/v1/patients/" + patientId + "/appointments").with(as(role))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].status").value("COMPLETED")).andExpect(jsonPath("$.content[0].patient.name").value("Maria Souza"))
				.andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].medicalHistory").doesNotExist()).andExpect(jsonPath("$.content[0].clinicId").doesNotExist());

		verify(appointments).historyOf(patientId, 0, 20);
	}

	@Test
	void pageAndSizeArePassedAndLimitedTo50() throws Exception {
		when(appointments.historyOf(any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

		mvc.perform(get("/api/v1/patients/" + patientId + "/appointments").param("page", "2").param("size", "50").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isOk());
		verify(appointments).historyOf(patientId, 2, 50);

		for (String[] bad : new String[][] { { "size", "51" }, { "size", "0" }, { "page", "-1" } }) {
			mvc.perform(get("/api/v1/patients/" + patientId + "/appointments").param(bad[0], bad[1]).with(as(Role.DENTIST_ADMIN)))
					.andExpect(status().isBadRequest());
		}
	}

	@Test
	void unknownPatientIs404AndWithoutTokenIs401() throws Exception {
		when(appointments.historyOf(any(), anyInt(), anyInt())).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado."));

		mvc.perform(get("/api/v1/patients/" + patientId + "/appointments").with(as(Role.RECEPTIONIST))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/patients/" + patientId + "/appointments")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/patients/nao-e-uuid/appointments").with(as(Role.RECEPTIONIST))).andExpect(status().isBadRequest());
	}
}
