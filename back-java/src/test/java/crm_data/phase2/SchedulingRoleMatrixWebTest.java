package crm_data.phase2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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

import crm_data.clinic.service.ClinicService;
import crm_data.clinic.web.ClinicController;
import crm_data.patient.service.PatientAnonymizationService;
import crm_data.patient.service.PatientService;
import crm_data.patient.web.PatientController;
import crm_data.record.service.AuditQueryService;
import crm_data.record.service.ClinicalNoteService;
import crm_data.record.web.AuditLogController;
import crm_data.record.web.ClinicalNoteController;
import crm_data.scheduling.service.AppointmentService;
import crm_data.scheduling.service.AppointmentStatusService;
import crm_data.scheduling.service.BusinessHoursService;
import crm_data.scheduling.service.HolidayService;
import crm_data.scheduling.web.AppointmentController;
import crm_data.scheduling.web.AppointmentStatusController;
import crm_data.scheduling.web.BusinessHoursController;
import crm_data.scheduling.web.ClinicHolidayController;
import crm_data.scheduling.web.HolidayController;
import crm_data.scheduling.web.PatientAppointmentController;
import crm_data.shared.config.CorsConfig;
import crm_data.shared.config.JwtConfig;
import crm_data.shared.config.ProblemResponseWriter;
import crm_data.shared.config.SecurityConfig;
import crm_data.shared.config.SecurityHeadersConfig;
import crm_data.shared.security.Role;
import crm_data.shared.web.RequestIdFilter;

/**
 * Matriz papel × rota de TODAS as rotas novas e alteradas da Fase 2 (seção 3 do PRD global), numa só fatia web com os
 * serviços simulados: sem token → 401; recepção → 403 nas rotas do dentista (sem tocar nenhum serviço) e passagem nas
 * demais; dentista → passagem em todas. "Passagem" é qualquer resposta que não seja 401/403: o conteúdo de cada rota é
 * contrato das features, e os serviços simulados devolvem {@code null}.
 */
@WebMvcTest({ ClinicController.class, BusinessHoursController.class, HolidayController.class, ClinicHolidayController.class,
		AppointmentController.class, AppointmentStatusController.class, PatientAppointmentController.class,
		ClinicalNoteController.class, PatientController.class, AuditLogController.class })
@Import({ SecurityConfig.class, CorsConfig.class, SecurityHeadersConfig.class, ProblemResponseWriter.class,
		RequestIdFilter.class, JwtConfig.class })
@TestPropertySource(properties = { "crm.cors-origins=https://app.test.local",
		"crm.jwt.secret=segredo-somente-de-teste-com-mais-de-32-bytes" })
class SchedulingRoleMatrixWebTest {

	@Autowired
	MockMvc mvc;
	@MockitoBean
	ClinicService clinics;
	@MockitoBean
	BusinessHoursService businessHours;
	@MockitoBean
	HolidayService holidays;
	@MockitoBean
	AppointmentService appointments;
	@MockitoBean
	AppointmentStatusService status;
	@MockitoBean
	ClinicalNoteService notes;
	@MockitoBean
	PatientService patients;
	@MockitoBean
	PatientAnonymizationService anonymization;
	@MockitoBean
	AuditQueryService audit;

	private final UUID id = UUID.randomUUID();
	private final UUID patientId = UUID.randomUUID();

	private record Route(String label, MockHttpServletRequestBuilder request, boolean receptionistAllowed) {
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private List<Route> routes() {
		String action = "{\"version\":0}";
		String window = "{\"startsAt\":\"2026-11-03T13:00:00Z\",\"endsAt\":\"2026-11-03T13:45:00Z\"";
		return List.of(
				new Route("GET /clinic", get("/api/v1/clinic"), true),
				new Route("PUT /clinic", json(put("/api/v1/clinic"), "{\"name\":\"Clínica\",\"timezone\":\"UTC\",\"version\":0}"), false),
				new Route("GET /business-hours", get("/api/v1/business-hours"), true),
				new Route("PUT /business-hours", json(put("/api/v1/business-hours"), "{\"days\":[{\"weekday\":7,\"isOpen\":false}]}"), false),
				new Route("GET /holidays", get("/api/v1/holidays").param("from", "2026-01-01").param("to", "2026-12-31"), true),
				new Route("POST /clinic-holidays", json(post("/api/v1/clinic-holidays"), "{\"date\":\"2026-12-24\",\"name\":\"Recesso\"}"), false),
				new Route("PUT /clinic-holidays/{id}", json(put("/api/v1/clinic-holidays/" + id), "{\"date\":\"2026-12-24\",\"name\":\"Recesso\",\"version\":0}"), false),
				new Route("DELETE /clinic-holidays/{id}", delete("/api/v1/clinic-holidays/" + id), false),
				new Route("POST /appointments", json(post("/api/v1/appointments"), window + ",\"patientId\":\"" + patientId + "\"}"), true),
				new Route("GET /appointments", get("/api/v1/appointments").param("from", "2026-11-01").param("to", "2026-11-07"), true),
				new Route("GET /appointments/{id}", get("/api/v1/appointments/" + id), true),
				new Route("PUT /appointments/{id}", json(put("/api/v1/appointments/" + id), window + ",\"version\":0}"), true),
				new Route("POST /appointments/{id}/confirm", json(post("/api/v1/appointments/" + id + "/confirm"), action), true),
				new Route("POST /appointments/{id}/cancel", json(post("/api/v1/appointments/" + id + "/cancel"), action), true),
				new Route("POST /appointments/{id}/no-show", json(post("/api/v1/appointments/" + id + "/no-show"), action), true),
				new Route("POST /appointments/{id}/start", json(post("/api/v1/appointments/" + id + "/start"), action), false),
				new Route("POST /appointments/{id}/finish", json(post("/api/v1/appointments/" + id + "/finish"), action), false),
				new Route("POST /appointments/{id}/reactivate", json(post("/api/v1/appointments/" + id + "/reactivate"), action), false),
				new Route("GET /patients/{id}/appointments", get("/api/v1/patients/" + patientId + "/appointments"), true),
				new Route("POST /patients/{id}/notes", json(post("/api/v1/patients/" + patientId + "/notes"), "{\"content\":\"x\",\"appointmentId\":\"" + id + "\"}"), false),
				new Route("PUT /patients/{id}/notes/{noteId}", json(put("/api/v1/patients/" + patientId + "/notes/" + id),
						"{\"content\":\"x\",\"notedAt\":\"2026-11-03T13:00:00Z\",\"version\":0}"), false),
				new Route("PATCH /patients/{id}/active", json(patch("/api/v1/patients/" + patientId + "/active"), "{\"active\":false}"), false),
				new Route("POST /patients/{id}/anonymize", post("/api/v1/patients/" + patientId + "/anonymize"), false),
				new Route("GET /audit-log?tableName=appointments", get("/api/v1/audit-log").param("tableName", "appointments"), false));
	}

	private static RequestPostProcessor as(Role role) {
		return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("clinic_id", UUID.randomUUID().toString()).claim("role", role.name()))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private int statusOf(Route route, RequestPostProcessor token) throws Exception {
		MockHttpServletRequestBuilder request = route.request();
		return mvc.perform(token == null ? request : request.with(token)).andReturn().getResponse().getStatus();
	}

	@Test
	void theMatrixCoversEveryNewAndAlteredRoute() {
		assertThat(routes()).hasSize(24);
		assertThat(routes().stream().map(Route::label).distinct()).hasSize(24);
	}

	@Test
	void withoutTokenEveryRouteIs401() throws Exception {
		for (Route route : routes()) {
			assertThat(statusOf(route, null)).as(route.label()).isEqualTo(401);
		}
		verifyNoInteractions(clinics, businessHours, holidays, appointments, status, notes, patients, anonymization, audit);
	}

	@Test
	void receptionistIs403OnTheDentistRoutesAndPassesOnTheOthers() throws Exception {
		for (Route route : routes()) {
			int code = statusOf(route, as(Role.RECEPTIONIST));
			if (route.receptionistAllowed()) {
				assertThat(code).as(route.label()).isNotIn(401, 403);
			}
			else {
				assertThat(code).as(route.label()).isEqualTo(403);
			}
		}
	}

	@Test
	void theDentistPassesOnEveryRoute() throws Exception {
		for (Route route : routes()) {
			assertThat(statusOf(route, as(Role.DENTIST_ADMIN))).as(route.label()).isNotIn(401, 403);
		}
	}

	@Test
	void theDeniedReceptionistRoutesNeverReachAService() throws Exception {
		for (Route route : routes()) {
			if (!route.receptionistAllowed()) {
				statusOf(route, as(Role.RECEPTIONIST));
			}
		}

		verifyNoInteractions(clinics, businessHours, holidays, notes, patients, anonymization, audit);
		verifyNoInteractions(status); // start, finish e reactivate; confirm/cancel/no-show não estão na lista negada
	}
}
