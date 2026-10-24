package crm_data.scheduling.web;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.patient.service.PatientBrief;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.service.AppointmentService.AppointmentView;
import crm_data.scheduling.service.AppointmentStatusService;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Matriz papel × rota das seis ações de status, mais o corpo, os erros repassados e o formato da resposta. */
@WebMvcTest(AppointmentStatusController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class AppointmentStatusWebTest {

	@Autowired
	MockMvc mvc;
	@MockitoBean
	AppointmentStatusService status;

	private final UUID id = UUID.randomUUID();
	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private AppointmentView view(AppointmentStatus appointmentStatus) {
		Instant start = Instant.parse("2026-11-03T13:00:00Z");
		Appointment appointment = new Appointment(id, UUID.randomUUID(), start, start.plusSeconds(3600), null, userId);
		appointment.setStatus(appointmentStatus);
		ReflectionTestUtils.setField(appointment, "version", 4L);
		return new AppointmentView(appointment, new PatientBrief(appointment.getPatientId(), "Maria Souza", "119"));
	}

	/** rota, papéis que podem (true = recepção também) */
	static Stream<Arguments> routes() {
		return Stream.of(Arguments.of("confirm", true), Arguments.of("cancel", true), Arguments.of("no-show", true),
				Arguments.of("start", false), Arguments.of("finish", false), Arguments.of("reactivate", false));
	}

	private void stubSuccess(String route) {
		AppointmentView ok = view(AppointmentStatus.CONFIRMED);
		switch (route) {
			case "confirm" -> when(status.confirm(eq(id), anyLong())).thenReturn(ok);
			case "cancel" -> when(status.cancel(eq(id), anyLong())).thenReturn(ok);
			case "no-show" -> when(status.noShow(eq(id), anyLong())).thenReturn(ok);
			case "start" -> when(status.start(eq(id), anyLong())).thenReturn(ok);
			case "finish" -> when(status.finish(eq(id), anyLong())).thenReturn(ok);
			default -> when(status.reactivate(eq(id), anyLong())).thenReturn(ok);
		}
	}

	@ParameterizedTest
	@MethodSource("routes")
	void dentistAlwaysPassesAndGetsTheUpdatedAppointment(String route, boolean receptionistToo) throws Exception {
		stubSuccess(route);

		mvc.perform(post("/api/v1/appointments/" + id + "/" + route).contentType(MediaType.APPLICATION_JSON).content("{\"version\":4}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.version").value(4)).andExpect(jsonPath("$.patient.name").value("Maria Souza"));
	}

	@ParameterizedTest
	@MethodSource("routes")
	void receptionistPassesOnlyOnTheActionsThatAreHers(String route, boolean receptionistToo) throws Exception {
		stubSuccess(route);

		mvc.perform(post("/api/v1/appointments/" + id + "/" + route).contentType(MediaType.APPLICATION_JSON).content("{\"version\":4}")
				.with(as(Role.RECEPTIONIST))).andExpect(status().is(receptionistToo ? 200 : 403));

		if (!receptionistToo) {
			verifyNoInteractions(status);
		}
	}

	@ParameterizedTest
	@MethodSource("routes")
	void withoutTokenIs401AndGetIsNotAllowed(String route, boolean receptionistToo) throws Exception {
		mvc.perform(post("/api/v1/appointments/" + id + "/" + route).contentType(MediaType.APPLICATION_JSON).content("{\"version\":4}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/appointments/" + id + "/" + route).with(as(Role.DENTIST_ADMIN))).andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(status);
	}

	@ParameterizedTest
	@MethodSource("routes")
	void theVersionIsRequiredAndValidated(String route, boolean receptionistToo) throws Exception {
		for (String body : new String[] { "{}", "{\"version\":-1}", "{\"version\":null}" }) {
			mvc.perform(post("/api/v1/appointments/" + id + "/" + route).contentType(MediaType.APPLICATION_JSON).content(body)
					.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
					.andExpect(jsonPath("$.errors[?(@.field=='version')]").isNotEmpty());
		}
		verifyNoInteractions(status);
	}

	@Test
	void anIdThatIsNotAUuidIs400AndExtraBodyFieldsAreIgnored() throws Exception {
		stubSuccess("confirm");

		mvc.perform(post("/api/v1/appointments/nao-e-uuid/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/appointments/" + id + "/confirm").contentType(MediaType.APPLICATION_JSON)
				.content("{\"version\":1,\"status\":\"COMPLETED\",\"clinicId\":\"" + UUID.randomUUID() + "\"}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isOk());

		verify(status).confirm(id, 1L);
	}

	@Test
	void ruleAndConflictErrorsFromTheServiceKeepTheirStatusAndReason() throws Exception {
		when(status.noShow(eq(id), anyLong()))
				.thenThrow(new BusinessException(ProblemTypes.BUSINESS_RULE, "O horário do agendamento ainda não começou.", "NOT_STARTED"));
		when(status.start(eq(id), anyLong())).thenThrow(new BusinessException(ProblemTypes.CONFLICT, "Já existe um atendimento em andamento."));
		when(status.finish(eq(id), anyLong())).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Agendamento não encontrado."));

		mvc.perform(post("/api/v1/appointments/" + id + "/no-show").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")
				.with(as(Role.RECEPTIONIST))).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.reason").value("NOT_STARTED"));
		mvc.perform(post("/api/v1/appointments/" + id + "/start").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isConflict());
		mvc.perform(post("/api/v1/appointments/" + id + "/finish").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isNotFound());
	}
}
