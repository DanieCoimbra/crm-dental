package crm_data.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.catalog.service.ProcedureBrief;
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

/** Contrato HTTP dos agendamentos: papéis, validação da janela, formato da resposta e repasse ao serviço. */
@WebMvcTest(AppointmentController.class)
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class AppointmentWebTest {

	private static final String PATIENT = "6f0a1b9e-1d3c-4a52-9c0e-3a7b5f2d8e11";
	private static final Instant START = Instant.parse("2026-11-03T13:00:00Z");

	@Autowired
	MockMvc mvc;
	@MockitoBean
	AppointmentService appointments;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private AppointmentView view(AppointmentStatus status) {
		Appointment appointment = new Appointment(UUID.fromString("b3f1c7d2-0a54-4e8b-8c2f-61d9a4e7f003"), UUID.fromString(PATIENT), START,
				START.plusSeconds(2700), "Retorno", userId);
		appointment.setStatus(status);
		ReflectionTestUtils.setField(appointment, "version", 2L);
		ReflectionTestUtils.setField(appointment, "createdAt", Instant.parse("2026-10-24T12:00:00Z"));
		ReflectionTestUtils.setField(appointment, "updatedAt", Instant.parse("2026-10-24T12:30:00Z"));
		return new AppointmentView(appointment, new PatientBrief(UUID.fromString(PATIENT), "Maria Souza", "11987654321"));
	}

	private static String create(String start, String end, String extra) {
		return "{\"patientId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\"%s}".formatted(PATIENT, start, end, extra);
	}

	// ---- criar ----

	@ParameterizedTest
	@EnumSource(Role.class)
	void bothRolesCreateAndTheResponseHasTheContractShape(Role role) throws Exception {
		when(appointments.create(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(view(AppointmentStatus.SCHEDULED));

		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T13:00:00Z", "2026-11-03T13:45:00Z", ",\"notes\":\"  Retorno  \"")).with(as(role)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/appointments/b3f1c7d2-0a54-4e8b-8c2f-61d9a4e7f003"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.id").value("b3f1c7d2-0a54-4e8b-8c2f-61d9a4e7f003"))
				.andExpect(jsonPath("$.patient.name").value("Maria Souza")).andExpect(jsonPath("$.patient.phone").value("11987654321"))
				.andExpect(jsonPath("$.startsAt").value("2026-11-03T13:00:00Z")).andExpect(jsonPath("$.endsAt").value("2026-11-03T13:45:00Z"))
				.andExpect(jsonPath("$.status").value("SCHEDULED")).andExpect(jsonPath("$.notes").value("Retorno"))
				.andExpect(jsonPath("$.createdBy").value(userId.toString())).andExpect(jsonPath("$.createdAt").value("2026-10-24T12:00:00Z"))
				.andExpect(jsonPath("$.updatedAt").value("2026-10-24T12:30:00Z")).andExpect(jsonPath("$.version").value(2))
				.andExpect(content().string(Matchers.not(Matchers.containsString("clinicId"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("procedureTypeId"))));

		verify(appointments).create(UUID.fromString(PATIENT), START, START.plusSeconds(2700), "Retorno", false, null);
	}

	@Test
	void createIgnoresServerOwnedFieldsAndReadsOffsetInstants() throws Exception {
		when(appointments.create(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(view(AppointmentStatus.SCHEDULED));
		String extra = ",\"id\":\"%s\",\"clinicId\":\"%s\",\"createdBy\":\"%s\",\"status\":\"COMPLETED\",\"allowOutsideHours\":true"
				.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T10:00:00-03:00", "2026-11-03T10:45:00-03:00", extra))
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isCreated());

		verify(appointments).create(UUID.fromString(PATIENT), START, START.plusSeconds(2700), null, true, null);
	}

	// ---- procedimento (Fase 3, F02) ----

	private static final UUID PROCEDURE = UUID.fromString("c1a2b3d4-0e5f-4a6b-8c7d-9e0f1a2b3c4d");

	private AppointmentView viewWithProcedure() {
		AppointmentView base = view(AppointmentStatus.SCHEDULED);
		return new AppointmentView(base.appointment(), base.patient(), new ProcedureBrief(PROCEDURE, "Restauração", "#3B82F6"));
	}

	@Test
	void theProcedureIdIsPassedOnAndTheResponseCarriesTheProcedureSummaryButNeverItsId() throws Exception {
		when(appointments.create(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(viewWithProcedure());

		mvc.perform(json(post("/api/v1/appointments"),
				create("2026-11-03T13:00:00Z", "2026-11-03T13:45:00Z", ",\"procedureTypeId\":\"" + PROCEDURE + "\""))
				.with(as(Role.RECEPTIONIST)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.procedure.id").value(PROCEDURE.toString()))
				.andExpect(jsonPath("$.procedure.name").value("Restauração"))
				.andExpect(jsonPath("$.procedure.color").value("#3B82F6"))
				.andExpect(content().string(Matchers.not(Matchers.containsString("procedureTypeId"))));

		verify(appointments).create(UUID.fromString(PATIENT), START, START.plusSeconds(2700), null, false, PROCEDURE);
	}

	@Test
	void anAppointmentWithoutProcedureHasANullProcedure() throws Exception {
		when(appointments.get(any())).thenReturn(view(AppointmentStatus.SCHEDULED));

		mvc.perform(get("/api/v1/appointments/" + UUID.randomUUID()).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.procedure").value(Matchers.nullValue()));
	}

	@Test
	void reschedulePassesTheProcedureIdAndWithoutItTheServiceReceivesNullToClearTheLink() throws Exception {
		when(appointments.reschedule(any(), any(), any(), any(), anyBoolean(), any(), anyLong())).thenReturn(viewWithProcedure());
		UUID id = UUID.randomUUID();
		String window = "\"startsAt\":\"2026-11-03T14:00:00Z\",\"endsAt\":\"2026-11-03T14:45:00Z\"";

		mvc.perform(json(put("/api/v1/appointments/" + id), "{" + window + ",\"procedureTypeId\":\"" + PROCEDURE + "\",\"version\":2}")
				.with(as(Role.RECEPTIONIST))).andExpect(status().isOk());
		mvc.perform(json(put("/api/v1/appointments/" + id), "{" + window + ",\"version\":3}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isOk());

		verify(appointments).reschedule(id, Instant.parse("2026-11-03T14:00:00Z"), Instant.parse("2026-11-03T14:45:00Z"), null, false,
				PROCEDURE, 2L);
		verify(appointments).reschedule(id, Instant.parse("2026-11-03T14:00:00Z"), Instant.parse("2026-11-03T14:45:00Z"), null, false,
				null, 3L);
	}

	@Test
	void aProcedureThatIsNotAUuidIsRejectedWith400() throws Exception {
		mvc.perform(json(post("/api/v1/appointments"),
				create("2026-11-03T13:00:00Z", "2026-11-03T13:45:00Z", ",\"procedureTypeId\":\"restauracao\""))
				.with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(appointments);
	}

	@Test
	void theServiceErrorsOfTheProcedureKeepTheirStatusAndReason() throws Exception {
		when(appointments.create(any(), any(), any(), any(), anyBoolean(), any()))
				.thenThrow(new BusinessException(ProblemTypes.BUSINESS_RULE, "O procedimento está inativo.", "PROCEDURE_INACTIVE"))
				.thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Procedimento não encontrado."));
		String body = create("2026-11-03T13:00:00Z", "2026-11-03T13:45:00Z", ",\"procedureTypeId\":\"" + PROCEDURE + "\"");

		mvc.perform(json(post("/api/v1/appointments"), body).with(as(Role.RECEPTIONIST)))
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.reason").value("PROCEDURE_INACTIVE"));
		mvc.perform(json(post("/api/v1/appointments"), body).with(as(Role.RECEPTIONIST))).andExpect(status().isNotFound());
	}

	@Test
	void theFitFlagFromTheReceptionistIsPassedOnSoTheServiceCanRefuseIt() throws Exception {
		when(appointments.create(any(), any(), any(), any(), eq(true), any()))
				.thenThrow(new BusinessException(ProblemTypes.FORBIDDEN, "Somente o dentista pode agendar fora do expediente."));

		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T13:00:00Z", "2026-11-03T13:45:00Z", ",\"allowOutsideHours\":true"))
				.with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
	}

	@Test
	void withoutTokenEveryRouteIs401() throws Exception {
		UUID id = UUID.randomUUID();

		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T13:00:00Z", "2026-11-03T13:45:00Z", ""))).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/appointments").param("from", "2026-11-01").param("to", "2026-11-07")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/appointments/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(json(put("/api/v1/appointments/" + id), "{}")).andExpect(status().isUnauthorized());
		verifyNoInteractions(appointments);
	}

	@Test
	void everyInvalidWindowFieldIsReportedAtOnce() throws Exception {
		String body = "{\"startsAt\":\"2026-11-03T13:00:30Z\",\"endsAt\":\"2026-11-03T13:07:30Z\",\"notes\":\"" + "x".repeat(501) + "\"}";

		mvc.perform(json(post("/api/v1/appointments"), body).with(as(Role.RECEPTIONIST))).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.errors[?(@.field=='patientId')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='startsAt')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='endsAt')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='notes')]").isNotEmpty());
		verifyNoInteractions(appointments);
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({ "13:00:00Z,13:00:00Z", "13:00:00Z,12:00:00Z", "13:00:00Z,13:04:00Z", "13:00:00Z,13:07:00Z",
			"13:00:00Z,01:00:00Z" })
	void durationAndOrderRules(String start, String end) throws Exception {
		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T" + start, "2026-11-03T" + end, "")).with(as(Role.RECEPTIONIST)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[?(@.field=='endsAt')]").isNotEmpty());
		verifyNoInteractions(appointments);
	}

	@Test
	void theLimitsOf5MinutesAnd12HoursAreAccepted() throws Exception {
		when(appointments.create(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(view(AppointmentStatus.SCHEDULED));

		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T13:00:00Z", "2026-11-03T13:05:00Z", "")).with(as(Role.RECEPTIONIST)))
				.andExpect(status().isCreated());
		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T08:00:00Z", "2026-11-03T20:00:00Z", "")).with(as(Role.RECEPTIONIST)))
				.andExpect(status().isCreated());
		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T08:00:00Z", "2026-11-03T20:05:00Z", "")).with(as(Role.RECEPTIONIST)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void notesThatAreBlankAreRejectedAndEmptyOrMissingMeansNone() throws Exception {
		when(appointments.create(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(view(AppointmentStatus.SCHEDULED));

		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T13:00:00Z", "2026-11-03T13:45:00Z", ",\"notes\":\"   \""))
				.with(as(Role.RECEPTIONIST))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[?(@.field=='notes')]").isNotEmpty());
		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T13:00:00Z", "2026-11-03T13:45:00Z", ",\"notes\":null"))
				.with(as(Role.RECEPTIONIST))).andExpect(status().isCreated());

		verify(appointments).create(any(), any(), any(), isNull(), eq(false), isNull());
	}

	@Test
	void ruleErrorsFromTheServiceKeepTheirStatusAndReason() throws Exception {
		when(appointments.create(any(), any(), any(), any(), anyBoolean(), any()))
				.thenThrow(new BusinessException(ProblemTypes.BUSINESS_RULE, "O horário cai no intervalo de almoço.", "LUNCH"));

		mvc.perform(json(post("/api/v1/appointments"), create("2026-11-03T15:00:00Z", "2026-11-03T15:45:00Z", "")).with(as(Role.RECEPTIONIST)))
				.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.reason").value("LUNCH"));
	}

	// ---- agenda ----

	@Test
	void agendaParsesTheLocalDatesAndRepeatedStatuses() throws Exception {
		when(appointments.list(any(), any(), anyCollection())).thenReturn(List.of(view(AppointmentStatus.CONFIRMED)));

		mvc.perform(get("/api/v1/appointments").param("from", "2026-11-01").param("to", "2026-11-07").param("status", "SCHEDULED", "CONFIRMED")
				.with(as(Role.RECEPTIONIST))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("CONFIRMED"));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<AppointmentStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
		verify(appointments).list(eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 7)), statuses.capture());
		assertThat(statuses.getValue()).containsExactly(AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED);
	}

	@Test
	void agendaWithoutStatusPassesNone() throws Exception {
		when(appointments.list(any(), any(), any())).thenReturn(List.of());

		mvc.perform(get("/api/v1/appointments").param("from", "2026-11-01").param("to", "2026-11-07").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

		verify(appointments).list(any(), any(), isNull());
	}

	@Test
	void agendaParameterErrorsAre400WithTheField() throws Exception {
		mvc.perform(get("/api/v1/appointments").param("from", "2026-11-01").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='to')]").isNotEmpty());
		mvc.perform(get("/api/v1/appointments").param("from", "01/11/2026").param("to", "2026-11-07").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[?(@.field=='from')]").isNotEmpty());
		mvc.perform(get("/api/v1/appointments").param("from", "2026-11-01").param("to", "2026-11-07").param("status", "WAITING")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[?(@.field=='status')]").isNotEmpty());
		verifyNoInteractions(appointments);
	}

	@Test
	void windowErrorsFromTheServiceAre400() throws Exception {
		when(appointments.list(any(), any(), any())).thenThrow(new BusinessException(ProblemTypes.VALIDATION, "Janela longa."));

		mvc.perform(get("/api/v1/appointments").param("from", "2026-01-01").param("to", "2026-12-31").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void getByIdReturnsTheAppointmentOr404() throws Exception {
		UUID id = UUID.randomUUID();
		when(appointments.get(id)).thenReturn(view(AppointmentStatus.SCHEDULED));
		UUID unknown = UUID.randomUUID();
		when(appointments.get(unknown)).thenThrow(new BusinessException(ProblemTypes.NOT_FOUND, "Agendamento não encontrado."));

		mvc.perform(get("/api/v1/appointments/" + id).with(as(Role.RECEPTIONIST))).andExpect(status().isOk())
				.andExpect(jsonPath("$.patient.id").value(PATIENT));
		mvc.perform(get("/api/v1/appointments/" + unknown).with(as(Role.RECEPTIONIST))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/appointments/nao-e-uuid").with(as(Role.RECEPTIONIST))).andExpect(status().isBadRequest());
	}

	// ---- remarcar ----

	@ParameterizedTest
	@EnumSource(Role.class)
	void bothRolesRescheduleAndPatientIdInTheBodyIsIgnored(Role role) throws Exception {
		UUID id = UUID.randomUUID();
		when(appointments.reschedule(any(), any(), any(), any(), anyBoolean(), any(), anyLong())).thenReturn(view(AppointmentStatus.SCHEDULED));
		String body = "{\"patientId\":\"%s\",\"startsAt\":\"2026-11-03T14:00:00Z\",\"endsAt\":\"2026-11-03T14:45:00Z\",\"notes\":null,\"version\":2}"
				.formatted(UUID.randomUUID());

		mvc.perform(json(put("/api/v1/appointments/" + id), body).with(as(role))).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SCHEDULED"));

		verify(appointments).reschedule(id, Instant.parse("2026-11-03T14:00:00Z"), Instant.parse("2026-11-03T14:45:00Z"), null, false, null, 2L);
	}

	@Test
	void rescheduleRequiresTimesAndVersionAndTheSameWindowRules() throws Exception {
		UUID id = UUID.randomUUID();

		mvc.perform(json(put("/api/v1/appointments/" + id), "{}").with(as(Role.RECEPTIONIST))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='startsAt')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='endsAt')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='version')]").isNotEmpty());
		mvc.perform(json(put("/api/v1/appointments/" + id),
				"{\"startsAt\":\"2026-11-03T14:00:00Z\",\"endsAt\":\"2026-11-03T14:03:00Z\",\"version\":-1}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[?(@.field=='endsAt')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='version')]").isNotEmpty());
		verifyNoInteractions(appointments);
	}

	@Test
	void staleVersionAndWrongStatusFromTheServiceAre409() throws Exception {
		when(appointments.reschedule(any(), any(), any(), any(), anyBoolean(), any(), anyLong()))
				.thenThrow(new BusinessException(ProblemTypes.CONFLICT, "O agendamento está COMPLETED e não pode ser alterado."));

		mvc.perform(json(put("/api/v1/appointments/" + UUID.randomUUID()),
				"{\"startsAt\":\"2026-11-03T14:00:00Z\",\"endsAt\":\"2026-11-03T14:45:00Z\",\"version\":0}").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isConflict());
	}
}
