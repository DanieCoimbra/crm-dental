package crm_data.phase2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MvcResult;

import crm_data.patient.service.PatientBrief;
import crm_data.patient.service.PatientLookup;
import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;
import crm_data.scheduling.domain.BusinessHour;
import crm_data.scheduling.domain.ClinicHoliday;
import crm_data.scheduling.repository.AppointmentRepository;
import crm_data.scheduling.repository.BusinessHourRepository;
import crm_data.scheduling.repository.ClinicHolidayRepository;
import crm_data.scheduling.repository.NationalHolidayRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.clinic.service.ClinicZones;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.PatientFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import tools.jackson.databind.JsonNode;

/**
 * F01 contra o PostgreSQL real (V001–V021): mapeamento das entidades da agenda com {@code ddl-auto=validate},
 * fuso da clínica pela API (inclusive o trigger do banco), resumo e checagem de paciente, e a trilha de auditoria
 * aceitando {@code appointments}.
 */
class SchedulingPlatformIT extends Phase2ItSupport {

	@Autowired
	Environment environment;
	@Autowired
	EntityManagerFactory entityManagerFactory;
	@Autowired
	PatientLookup patients;
	@Autowired
	ClinicZones zones;
	@Autowired
	AppointmentRepository appointments;
	@Autowired
	BusinessHourRepository businessHours;
	@Autowired
	ClinicHolidayRepository clinicHolidays;
	@Autowired
	NationalHolidayRepository nationalHolidays;

	/** Troca o fuso direto no banco com a sessão de dentista (o trigger exige o papel; o admin ignora só a RLS). */
	private static void setTimezoneAsDentist(Clinic clinic, String timezone) {
		try (Connection connection = DatabaseContainer.admin().getDataSource().getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("SELECT set_config('app.role', 'DENTIST_ADMIN', false)");
			statement.execute("UPDATE core.clinics SET timezone = '" + timezone + "' WHERE id = '" + clinic.id() + "'");
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex.getMessage(), ex);
		}
	}

	// ---- mapeamento ----

	@Test
	void hibernateValidatesTheAgendaMappingAgainstV001ToV021() {
		assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
		Set<String> mapped = entityManagerFactory.getMetamodel().getEntities().stream().map(EntityType::getName)
				.collect(Collectors.toSet());

		assertThat(mapped).contains("Appointment", "BusinessHour", "ClinicHoliday", "NationalHoliday", "ClinicalNote", "Clinic");
	}

	@Test
	void appointmentRoundTripsWithItsDefaultsAndVersion() throws Exception {
		Clinic clinic = newClinic();
		UUID patient = UUID.fromString(createPatient(clinic, "Maria Souza"));
		UUID id = UUID.randomUUID();
		Instant start = Instant.parse("2026-11-03T13:00:00Z");

		asRole(clinic, Role.RECEPTIONIST, () -> appointments
				.saveAndFlush(new Appointment(id, patient, start, start.plusSeconds(2700), "Retorno", userOf(clinic, Role.RECEPTIONIST))));

		Appointment saved = asRole(clinic, Role.DENTIST_ADMIN, () -> appointments.findById(id).orElseThrow());
		assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
		assertThat(saved.getStartsAt()).isEqualTo(start);
		assertThat(saved.getEndsAt()).isEqualTo(start.plusSeconds(2700));
		assertThat(saved.getNotes()).isEqualTo("Retorno");
		assertThat(saved.getVersion()).isZero();
		assertThat(saved.getClinicId()).isEqualTo(clinic.id());
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.toString()).doesNotContain("Retorno");
	}

	@Test
	void businessHourAndClinicHolidayRoundTrip() {
		Clinic clinic = newClinic();
		UUID dentist = userOf(clinic, Role.DENTIST_ADMIN);

		UUID hourId = UUID.randomUUID();
		asRole(clinic, Role.DENTIST_ADMIN, () -> {
			BusinessHour monday = new BusinessHour(hourId, DayOfWeek.MONDAY, dentist);
			monday.setOpen(true);
			monday.setOpensAt(LocalTime.of(8, 0));
			monday.setClosesAt(LocalTime.of(18, 0));
			monday.setLunchStartsAt(LocalTime.of(12, 0));
			monday.setLunchEndsAt(LocalTime.of(13, 0));
			return businessHours.saveAndFlush(monday);
		});
		UUID holidayId = UUID.randomUUID();
		asRole(clinic, Role.DENTIST_ADMIN, () -> clinicHolidays
				.saveAndFlush(new ClinicHoliday(holidayId, LocalDate.of(2026, 12, 24), "Recesso", dentist)));

		BusinessHour monday = asRole(clinic, Role.RECEPTIONIST, () -> businessHours.findById(hourId).orElseThrow());
		assertThat(monday.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
		assertThat(monday.isOpen()).isTrue();
		assertThat(monday.getOpensAt()).isEqualTo(LocalTime.of(8, 0));
		assertThat(monday.getLunchEndsAt()).isEqualTo(LocalTime.of(13, 0));
		ClinicHoliday holiday = asRole(clinic, Role.RECEPTIONIST, () -> clinicHolidays.findById(holidayId).orElseThrow());
		assertThat(holiday.getDate()).isEqualTo(LocalDate.of(2026, 12, 24));
		assertThat(holiday.getName()).isEqualTo("Recesso");
	}

	@Test
	void nationalHolidaysAreReadableAndTheSeedHasTheMovableGoodFridays() {
		Clinic clinic = newClinic();

		List<LocalDate> dates = asRole(clinic, Role.RECEPTIONIST, () -> nationalHolidays
				.findByDateBetweenOrderByDate(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).stream()
				.map(h -> h.getDate()).toList());

		assertThat(dates).hasSize(10).contains(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 3), LocalDate.of(2026, 12, 25))
				.isSorted();
	}

	// ---- fuso da clínica ----

	@Test
	void clinicTimezoneDefaultsToSaoPauloAndZonesReadsIt() throws Exception {
		Clinic clinic = newClinic();

		mvc.perform(get("/api/v1/clinic").with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isOk())
				.andExpect(jsonPath("$.timezone").value("America/Sao_Paulo"));
		assertThat(asRole(clinic, Role.RECEPTIONIST, zones::zone).getId()).isEqualTo("America/Sao_Paulo");
	}

	@Test
	void dentistChangesTheTimezoneAndTheNewZoneIsUsed() throws Exception {
		Clinic clinic = newClinic();
		JsonNode current = read(mvc.perform(get("/api/v1/clinic").with(as(clinic, Role.DENTIST_ADMIN))).andReturn());

		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Clínica Nova\",\"timezone\":\"America/Manaus\",\"version\":%d}"
				.formatted(current.get("version").asLong())).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.timezone").value("America/Manaus"));

		assertThat(asRole(clinic, Role.RECEPTIONIST, zones::zone).getId()).isEqualTo("America/Manaus");
	}

	@Test
	void editingTheClinicWithoutTimezoneKeepsTheCurrentOne() throws Exception {
		Clinic clinic = newClinic();
		setTimezoneAsDentist(clinic, "UTC");
		JsonNode current = read(mvc.perform(get("/api/v1/clinic").with(as(clinic, Role.DENTIST_ADMIN))).andReturn());

		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Clínica Nova\",\"version\":%d}".formatted(current.get("version").asLong()))
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk()).andExpect(jsonPath("$.timezone").value("UTC"));
	}

	@Test
	void unknownTimezoneIs400AndReceptionistIs403() throws Exception {
		Clinic clinic = newClinic();

		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Clínica\",\"timezone\":\"Mars/Phobos\",\"version\":0}")
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='timezone')]").isNotEmpty());
		mvc.perform(json(put("/api/v1/clinic"), "{\"name\":\"Clínica\",\"timezone\":\"UTC\",\"version\":0}")
				.with(as(clinic, Role.RECEPTIONIST))).andExpect(status().isForbidden());
	}

	@Test
	void theDatabaseStillRejectsAZoneThatTheBackendLetThrough() {
		Clinic clinic = newClinic();

		assertThatThrownBy(() -> setTimezoneAsDentist(clinic, "Mars/Phobos")).hasMessageContaining("fuso");
	}

	// ---- PatientLookup ----

	@Test
	void requireSchedulableAcceptsActiveAndRejectsInactiveAnonymizedAndForeign() throws Exception {
		Clinic clinic = newClinic();
		Clinic other = newClinic();
		String active = createPatient(clinic, "Ana Ativa");
		String inactive = createPatient(clinic, "Ivo Inativo");
		mvc.perform(json(patch("/api/v1/patients/" + inactive + "/active"), "{\"active\":false}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());
		String anonymized = createPatient(clinic, "Aline Anonima");
		PatientFixtures.anonymize(clinic, anonymized);
		String foreign = createPatient(other, "Outra Clinica");

		asRole(clinic, Role.RECEPTIONIST, () -> {
			patients.requireSchedulable(UUID.fromString(active));
			return null;
		});
		assertBusiness(clinic, inactive, ProblemTypes.CONFLICT, "Paciente inativo.");
		assertBusiness(clinic, anonymized, ProblemTypes.CONFLICT, "Paciente anonimizado.");
		assertBusiness(clinic, foreign, ProblemTypes.NOT_FOUND, "Paciente não encontrado.");
		assertBusiness(clinic, UUID.randomUUID().toString(), ProblemTypes.NOT_FOUND, "Paciente não encontrado.");
	}

	private void assertBusiness(Clinic clinic, String patientId, ProblemTypes type, String message) {
		assertThatThrownBy(() -> asRole(clinic, Role.RECEPTIONIST, () -> {
			patients.requireSchedulable(UUID.fromString(patientId));
			return null;
		})).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(type);
			assertThat(ex.getMessage()).isEqualTo(message);
		});
	}

	@Test
	void briefsReturnsNameAndPhoneInBatchAndNeverForeignPatients() throws Exception {
		Clinic clinic = newClinic();
		Clinic other = newClinic();
		UUID maria = UUID.fromString(createPatient(clinic, "Maria Souza"));
		UUID joao = UUID.fromString(createPatient(clinic, "Joao Lima"));
		UUID foreign = UUID.fromString(createPatient(other, "Outra Clinica"));

		Map<UUID, PatientBrief> briefs = asRole(clinic, Role.RECEPTIONIST, () -> patients.briefs(List.of(maria, joao, foreign)));

		assertThat(briefs).containsOnlyKeys(maria, joao);
		assertThat(briefs.get(maria).name()).isEqualTo("Maria Souza");
		assertThat(briefs.get(maria).phone()).isEqualTo("11987654321");
	}

	// ---- auditoria ----

	@Test
	void auditLogAcceptsAppointmentsAndStillRejectsUnknownTables() throws Exception {
		Clinic clinic = newClinic();
		UUID patient = UUID.fromString(createPatient(clinic, "Maria Souza"));
		UUID id = UUID.randomUUID();
		Instant start = Instant.parse("2026-11-03T13:00:00Z");
		asRole(clinic, Role.DENTIST_ADMIN, () -> appointments
				.saveAndFlush(new Appointment(id, patient, start, start.plusSeconds(1800), null, userOf(clinic, Role.DENTIST_ADMIN))));

		mvc.perform(get("/api/v1/audit-log").param("tableName", "appointments").param("recordId", id.toString())
				.with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].tableName").value("appointments"))
				.andExpect(jsonPath("$.content[0].action").value("INSERT"))
				.andExpect(header().string("Cache-Control", "no-store"));
		mvc.perform(get("/api/v1/audit-log").param("tableName", "business_hours").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/audit-log").param("tableName", "appointments").with(as(clinic, Role.RECEPTIONIST)))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"));
	}
}
