package crm_data.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import crm_data.patient.service.PatientBrief;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.service.AffectedAppointments;
import crm_data.scheduling.service.AppointmentSlotRules.Reason;
import crm_data.scheduling.service.BusinessHoursService;
import crm_data.scheduling.service.BusinessHoursService.DayInput;
import crm_data.scheduling.service.BusinessHoursService.DayView;
import crm_data.scheduling.service.BusinessHoursService.UpdateResult;
import crm_data.scheduling.service.DaySchedule;
import crm_data.scheduling.service.HolidayService;
import crm_data.scheduling.service.HolidayService.HolidayChange;
import crm_data.scheduling.service.HolidayService.HolidayView;
import crm_data.scheduling.service.HolidayService.Source;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/** Contrato HTTP do expediente e dos feriados: matriz papel × rota, validação, formato das respostas e do aviso. */
@WebMvcTest({ BusinessHoursController.class, HolidayController.class, ClinicHolidayController.class })
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class SchedulingConfigWebTest {

	private static final String OFFICE_BODY = """
			{"days":[{"weekday":1,"isOpen":true,"opensAt":"08:00","closesAt":"18:00","lunchStartsAt":"12:00","lunchEndsAt":"13:00","version":3},
			         {"weekday":7,"isOpen":false}]}""";

	@Autowired
	MockMvc mvc;
	@MockitoBean
	BusinessHoursService businessHours;
	@MockitoBean
	HolidayService holidays;

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	private RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(userId.toString()).claim("clinic_id", clinicId.toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static UpdateResult emptyResult(boolean saved) {
		return new UpdateResult(saved, List.of(new DayView(DayOfWeek.MONDAY, true,
				new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0), LocalTime.of(13, 0)), 4L)),
				AffectedAppointments.NONE);
	}

	// ---- matriz papel × rota ----

	@Test
	void bothRolesReadBusinessHoursAndHolidays() throws Exception {
		when(businessHours.week()).thenReturn(List.of());
		when(holidays.list(any(), any())).thenReturn(List.of());

		for (Role role : Role.values()) {
			mvc.perform(get("/api/v1/business-hours").with(as(role))).andExpect(status().isOk())
					.andExpect(header().string("Cache-Control", "no-store"));
			mvc.perform(get("/api/v1/holidays").param("from", "2026-01-01").param("to", "2026-12-31").with(as(role)))
					.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
		}
	}

	@Test
	void receptionistCannotWriteBusinessHoursOrClinicHolidays_403WithoutTouchingTheServices() throws Exception {
		UUID id = UUID.randomUUID();
		String holiday = "{\"date\":\"2026-12-24\",\"name\":\"Recesso\"}";
		String holidayUpdate = "{\"date\":\"2026-12-24\",\"name\":\"Recesso\",\"version\":0}";

		mvc.perform(json(put("/api/v1/business-hours"), OFFICE_BODY).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"));
		mvc.perform(json(post("/api/v1/clinic-holidays"), holiday).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(json(put("/api/v1/clinic-holidays/" + id), holidayUpdate).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/clinic-holidays/" + id).with(as(Role.RECEPTIONIST))).andExpect(status().isForbidden());

		verifyNoInteractions(businessHours, holidays);
	}

	@Test
	void withoutTokenEveryRouteIs401() throws Exception {
		UUID id = UUID.randomUUID();

		mvc.perform(get("/api/v1/business-hours")).andExpect(status().isUnauthorized());
		mvc.perform(json(put("/api/v1/business-hours"), OFFICE_BODY)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/holidays").param("from", "2026-01-01").param("to", "2026-12-31")).andExpect(status().isUnauthorized());
		mvc.perform(json(post("/api/v1/clinic-holidays"), "{}")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/v1/clinic-holidays/" + id)).andExpect(status().isUnauthorized());
	}

	// ---- business hours ----

	@Test
	void getReturnsHhmmStringsTheIsOpenPropertyAndHidesServerOwnedFields() throws Exception {
		when(businessHours.week()).thenReturn(List.of(
				new DayView(DayOfWeek.MONDAY, true, new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0),
						LocalTime.of(13, 0)), 4L),
				new DayView(DayOfWeek.SATURDAY, false, DaySchedule.CLOSED, null)));

		mvc.perform(get("/api/v1/business-hours").with(as(Role.RECEPTIONIST))).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].weekday").value(1)).andExpect(jsonPath("$[0].configured").value(true))
				.andExpect(jsonPath("$[0].isOpen").value(true)).andExpect(jsonPath("$[0].opensAt").value("08:00"))
				.andExpect(jsonPath("$[0].closesAt").value("18:00")).andExpect(jsonPath("$[0].lunchStartsAt").value("12:00"))
				.andExpect(jsonPath("$[0].version").value(4)).andExpect(jsonPath("$[1].weekday").value(6))
				.andExpect(jsonPath("$[1].configured").value(false)).andExpect(jsonPath("$[1].isOpen").value(false))
				.andExpect(jsonPath("$[1].opensAt").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$[1].version").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("clinicId"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("createdBy"))));
	}

	@Test
	void putPassesTheInputsAndDryRunAndReturnsTheSavedFlagWithTheImpact() throws Exception {
		AffectedAppointments.Item item = new AffectedAppointments.Item(UUID.randomUUID(),
				new PatientBrief(UUID.randomUUID(), "Maria Souza", "11987654321"), Instant.parse("2026-11-02T15:30:00Z"),
				Instant.parse("2026-11-02T16:00:00Z"), AppointmentStatus.CONFIRMED, Reason.LUNCH);
		when(businessHours.update(any(), anyBoolean()))
				.thenReturn(new UpdateResult(false, List.of(), new AffectedAppointments(List.of(item), 1, false)));

		mvc.perform(json(put("/api/v1/business-hours").param("dryRun", "true"), OFFICE_BODY).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false))
				.andExpect(jsonPath("$.affectedCount").value(1)).andExpect(jsonPath("$.truncated").value(false))
				.andExpect(jsonPath("$.affectedAppointments[0].reason").value("LUNCH"))
				.andExpect(jsonPath("$.affectedAppointments[0].status").value("CONFIRMED"))
				.andExpect(jsonPath("$.affectedAppointments[0].startsAt").value("2026-11-02T15:30:00Z"))
				.andExpect(jsonPath("$.affectedAppointments[0].patient.name").value("Maria Souza"))
				.andExpect(jsonPath("$.affectedAppointments[0].patient.phone").value("11987654321"));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<DayInput>> inputs = ArgumentCaptor.forClass(List.class);
		verify(businessHours).update(inputs.capture(), eq(true));
		assertThat(inputs.getValue()).hasSize(2);
		assertThat(inputs.getValue().get(0).day()).isEqualTo(DayOfWeek.MONDAY);
		assertThat(inputs.getValue().get(0).version()).isEqualTo(3L);
		assertThat(inputs.getValue().get(0).schedule()).isEqualTo(
				new DaySchedule(true, LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(12, 0), LocalTime.of(13, 0)));
		assertThat(inputs.getValue().get(1).schedule()).isEqualTo(DaySchedule.CLOSED);
		assertThat(inputs.getValue().get(1).version()).isNull();
	}

	@Test
	void putWithoutDryRunSaves() throws Exception {
		when(businessHours.update(any(), anyBoolean())).thenReturn(emptyResult(true));

		mvc.perform(json(put("/api/v1/business-hours"), OFFICE_BODY).with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.affectedAppointments.length()").value(0))
				.andExpect(jsonPath("$.affectedCount").value(0)).andExpect(jsonPath("$.days[0].isOpen").value(true));

		verify(businessHours).update(any(), eq(false));
	}

	@Test
	void aClosedDayDropsTheTimesTheClientSentOnlyWhenTheyAreNotPresent() throws Exception {
		mvc.perform(json(put("/api/v1/business-hours"),
				"{\"days\":[{\"weekday\":7,\"isOpen\":false,\"opensAt\":\"08:00\"}]}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='days[0].opensAt')]").isNotEmpty());
		verifyNoInteractions(businessHours);
	}

	@Test
	void everyInvalidFieldIsReportedAtOnceWithItsPath() throws Exception {
		String body = """
				{"days":[
				 {"weekday":1,"isOpen":true,"opensAt":"18:00","closesAt":"08:00","lunchStartsAt":"12:00"},
				 {"weekday":1,"isOpen":true},
				 {"weekday":8,"isOpen":true,"opensAt":"08:00","closesAt":"18:00","lunchStartsAt":"07:00","lunchEndsAt":"19:00"},
				 {"weekday":3,"isOpen":true,"opensAt":"08:00:30","closesAt":"18:00"},
				 {"isOpen":true,"opensAt":"08:00","closesAt":"18:00","version":-1}]}""";

		String response = mvc.perform(json(put("/api/v1/business-hours"), body).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andReturn().getResponse().getContentAsString();

		assertThat(response).contains("days[0].closesAt", "days[0].lunchEndsAt", "days[1].weekday", "days[1].opensAt",
				"days[1].closesAt", "days[2].weekday", "days[2].lunchStartsAt", "days[2].lunchEndsAt", "days[3].opensAt",
				"days[4].weekday", "days[4].version");
		verifyNoInteractions(businessHours);
	}

	@Test
	void lunchOutsideTheWorkingHoursOrWithOnlyOneEndIsRejected() throws Exception {
		String lunchOutside = "{\"days\":[{\"weekday\":2,\"isOpen\":true,\"opensAt\":\"08:00\",\"closesAt\":\"18:00\",\"lunchStartsAt\":\"07:00\",\"lunchEndsAt\":\"09:00\"}]}";
		String lunchEndsAtClosing = "{\"days\":[{\"weekday\":2,\"isOpen\":true,\"opensAt\":\"08:00\",\"closesAt\":\"18:00\",\"lunchStartsAt\":\"12:00\",\"lunchEndsAt\":\"18:00\"}]}";
		String onlyStart = "{\"days\":[{\"weekday\":2,\"isOpen\":true,\"opensAt\":\"08:00\",\"closesAt\":\"18:00\",\"lunchStartsAt\":\"12:00\"}]}";

		mvc.perform(json(put("/api/v1/business-hours"), lunchOutside).with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='days[0].lunchStartsAt')]").isNotEmpty());
		mvc.perform(json(put("/api/v1/business-hours"), lunchEndsAtClosing).with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='days[0].lunchEndsAt')]").isNotEmpty());
		mvc.perform(json(put("/api/v1/business-hours"), onlyStart).with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='days[0].lunchEndsAt')]").isNotEmpty());
		verifyNoInteractions(businessHours);
	}

	@Test
	void closingAt2359IsAcceptedAnd2400IsRejected() throws Exception {
		when(businessHours.update(any(), anyBoolean())).thenReturn(emptyResult(true));

		mvc.perform(json(put("/api/v1/business-hours"),
				"{\"days\":[{\"weekday\":2,\"isOpen\":true,\"opensAt\":\"08:00\",\"closesAt\":\"23:59\"}]}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		mvc.perform(json(put("/api/v1/business-hours"),
				"{\"days\":[{\"weekday\":2,\"isOpen\":true,\"opensAt\":\"08:00\",\"closesAt\":\"24:00\"}]}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void emptyMissingOrMoreThanSevenDaysAreRejected() throws Exception {
		String eight = "{\"days\":[" + "{\"weekday\":1,\"isOpen\":false},".repeat(7) + "{\"weekday\":1,\"isOpen\":false}]}";

		mvc.perform(json(put("/api/v1/business-hours"), "{\"days\":[]}").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='days')]").isNotEmpty());
		mvc.perform(json(put("/api/v1/business-hours"), "{}").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='days')]").isNotEmpty());
		mvc.perform(json(put("/api/v1/business-hours"), eight).with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		verifyNoInteractions(businessHours);
	}

	@Test
	void staleVersionFromTheServiceIs409() throws Exception {
		when(businessHours.update(any(), anyBoolean())).thenThrow(new BusinessException(ProblemTypes.CONFLICT, "Alterado."));

		mvc.perform(json(put("/api/v1/business-hours"), OFFICE_BODY).with(as(Role.DENTIST_ADMIN))).andExpect(status().isConflict());
	}

	// ---- holidays ----

	@Test
	void holidaysListShowsClinicIdAndVersionOnlyOnClinicHolidays() throws Exception {
		UUID id = UUID.randomUUID();
		when(holidays.list(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))).thenReturn(List.of(
				new HolidayView(LocalDate.of(2026, 12, 24), "Recesso", Source.CLINIC, id, 1L),
				new HolidayView(LocalDate.of(2026, 12, 25), "Natal", Source.NATIONAL, null, null)));

		mvc.perform(get("/api/v1/holidays").param("from", "2026-12-01").param("to", "2026-12-31").with(as(Role.RECEPTIONIST)))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].source").value("CLINIC"))
				.andExpect(jsonPath("$[0].id").value(id.toString())).andExpect(jsonPath("$[0].version").value(1))
				.andExpect(jsonPath("$[0].date").value("2026-12-24")).andExpect(jsonPath("$[1].source").value("NATIONAL"))
				.andExpect(jsonPath("$[1].id").doesNotExist()).andExpect(jsonPath("$[1].version").doesNotExist());
	}

	@Test
	void holidaysWindowParametersAreRequiredAndValidDates() throws Exception {
		mvc.perform(get("/api/v1/holidays").param("from", "2026-12-01").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='to')]").isNotEmpty());
		mvc.perform(get("/api/v1/holidays").param("from", "01/12/2026").param("to", "2026-12-31").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[?(@.field=='from')]").isNotEmpty());
	}

	@Test
	void windowErrorsFromTheServiceAre400() throws Exception {
		when(holidays.list(any(), any())).thenThrow(new BusinessException(ProblemTypes.VALIDATION, "Janela longa."));

		mvc.perform(get("/api/v1/holidays").param("from", "2026-01-01").param("to", "2027-12-31").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void clinicHolidayCreateNormalizesTheNameIgnoresServerFieldsAndReturnsTheImpact() throws Exception {
		UUID id = UUID.randomUUID();
		AffectedAppointments.Item item = new AffectedAppointments.Item(UUID.randomUUID(), null, Instant.parse("2026-12-24T12:00:00Z"),
				Instant.parse("2026-12-24T13:00:00Z"), AppointmentStatus.SCHEDULED, Reason.HOLIDAY);
		when(holidays.create(any(), any())).thenReturn(new HolidayChange(
				new HolidayView(LocalDate.of(2026, 12, 24), "Recesso", Source.CLINIC, id, 0L),
				new AffectedAppointments(List.of(item), 1, false)));

		mvc.perform(json(post("/api/v1/clinic-holidays"),
				"{\"date\":\"2026-12-24\",\"name\":\"  Recesso  \",\"id\":\"%s\",\"clinicId\":\"%s\",\"source\":\"NATIONAL\"}"
						.formatted(UUID.randomUUID(), UUID.randomUUID())).with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.holiday.id").value(id.toString()))
				.andExpect(jsonPath("$.holiday.source").value("CLINIC")).andExpect(jsonPath("$.affectedCount").value(1))
				.andExpect(jsonPath("$.affectedAppointments[0].reason").value("HOLIDAY"));

		verify(holidays).create(LocalDate.of(2026, 12, 24), "Recesso");
	}

	@Test
	void clinicHolidayValidation() throws Exception {
		mvc.perform(json(post("/api/v1/clinic-holidays"), "{}").with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='date')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").isNotEmpty());
		mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"1999-12-31\",\"name\":\"X\"}").with(as(Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[?(@.field=='date')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").isNotEmpty());
		mvc.perform(json(post("/api/v1/clinic-holidays"), "{\"date\":\"2026-12-24\",\"name\":\"" + "x".repeat(81) + "\"}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").isNotEmpty());
		mvc.perform(json(put("/api/v1/clinic-holidays/" + UUID.randomUUID()), "{\"date\":\"2026-12-24\",\"name\":\"Recesso\"}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='version')]").isNotEmpty());
		verifyNoInteractions(holidays);
	}

	@Test
	void clinicHolidayUpdateAndDelete() throws Exception {
		UUID id = UUID.randomUUID();
		when(holidays.update(any(), any(), any(), anyLong())).thenReturn(new HolidayChange(
				new HolidayView(LocalDate.of(2026, 12, 26), "Recesso", Source.CLINIC, id, 1L), AffectedAppointments.NONE));

		mvc.perform(json(put("/api/v1/clinic-holidays/" + id), "{\"date\":\"2026-12-26\",\"name\":\"Recesso\",\"version\":0}")
				.with(as(Role.DENTIST_ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.holiday.version").value(1))
				.andExpect(jsonPath("$.affectedCount").value(0));
		mvc.perform(delete("/api/v1/clinic-holidays/" + id).with(as(Role.DENTIST_ADMIN))).andExpect(status().isNoContent());

		verify(holidays).update(id, LocalDate.of(2026, 12, 26), "Recesso", 0L);
		verify(holidays).delete(id);
	}

	@ParameterizedTest
	@EnumSource(Role.class)
	void clinicHolidayHasNoGetByIdAndItsRoutesAreDentistOnly(Role role) throws Exception {
		mvc.perform(get("/api/v1/clinic-holidays/" + UUID.randomUUID()).with(as(role)))
				.andExpect(status().is(role == Role.DENTIST_ADMIN ? 405 : 403));
	}
}
