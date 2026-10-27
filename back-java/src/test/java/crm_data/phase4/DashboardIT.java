package crm_data.phase4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import crm_data.shared.security.Role;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.TenantFixtures.Clinic;
import tools.jackson.databind.JsonNode;

/** F05: dashboard contra o schema real, com massa semeada e conferida contra contagens independentes. */
class DashboardIT extends Phase4ItSupport {

	private ZoneId zoneOf(Clinic clinic) {
		return ZoneId.of(DatabaseContainer.admin().queryForObject("SELECT timezone FROM core.clinics WHERE id = ?", String.class, clinic.id()));
	}

	private static Instant at(LocalDate day, int hour, int minute, ZoneId zone) {
		return day.atTime(hour, minute).atZone(zone).toInstant();
	}

	/** Insere um agendamento direto no banco (a API valida expediente e transições; aqui o objetivo é a massa). */
	private void appointment(Clinic clinic, String patient, Instant start, int minutes, String status) {
		adminSql(clinic, ("INSERT INTO core.appointments (id, clinic_id, patient_id, starts_at, ends_at, status, created_by) "
				+ "VALUES ('%s', '%s', '%s', '%s', '%s', '%s', '%s')").formatted(UUID.randomUUID(), clinic.id(), patient, start,
				start.plusSeconds(minutes * 60L), status, userOf(clinic, Role.DENTIST_ADMIN)));
	}

	private JsonNode dashboard(Clinic clinic, Role role, String query) throws Exception {
		MvcResult result = mvc.perform(get("/api/v1/dashboard" + query).with(as(clinic, role))).andExpect(status().isOk()).andReturn();
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
		return read(result);
	}

	@Test
	void emptyClinicHasZerosNullPercentSevenDaysAndSixStatuses() throws Exception {
		Setup setup = openClinic();
		Clinic other = openClinic().clinic(); // dados de outra clínica nunca entram

		JsonNode d = dashboard(setup.clinic(), Role.DENTIST_ADMIN, "");

		assertThat(d.get("totalPatients").asInt()).isEqualTo(1);
		assertThat(d.get("appointmentsToday").get("total").asInt()).isZero();
		assertThat(d.get("appointmentsToday").get("byStatus").size()).isEqualTo(6);
		assertThat(d.get("returnRate").get("percent").isNull()).isTrue();
		assertThat(d.get("returnRate").get("attended").asInt()).isZero();
		assertThat(d.get("monthlyRevenue").get("totalAmount").asString()).isEqualTo("0.00");
		assertThat(d.get("weekly").get("days").size()).isEqualTo(7);
		assertThat(d.get("weekly").get("weekStart").asString()).isNotEmpty();
		assertThat(d.get("timezone").asString()).isEqualTo(zoneOf(setup.clinic()).getId());
		assertThat(other).isNotNull();
	}

	@Test
	void allIndicatorsMatchIndependentCountsOnSeededData() throws Exception {
		Kit kit = kit();
		Clinic clinic = kit.setup().clinic();
		ZoneId zone = zoneOf(clinic);
		LocalDate today = LocalDate.now(zone);
		String p1 = kit.setup().patient();
		String p2 = createPatient(clinic, "Paciente Dois");
		String p3 = createPatient(clinic, "Paciente Três");
		String p4 = createPatient(clinic, "Paciente Quatro");
		String inactive = createPatient(clinic, "Paciente Inativo");
		mvc.perform(json(patch("/api/v1/patients/" + inactive + "/active"), "{\"active\":false}").with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isOk());

		// hoje: 1 concluída (p1), 1 confirmada (p2), 1 cancelada, 1 falta, 1 agendada, 1 concluída (p4) => total 4
		appointment(clinic, p1, at(today, 8, 0, zone), 30, "COMPLETED");
		appointment(clinic, p2, at(today, 9, 0, zone), 30, "CONFIRMED");
		appointment(clinic, p2, at(today, 10, 0, zone), 30, "CANCELED");
		appointment(clinic, p3, at(today, 11, 0, zone), 30, "NO_SHOW");
		appointment(clinic, p3, at(today, 12, 0, zone), 30, "SCHEDULED");
		appointment(clinic, p4, at(today, 13, 0, zone), 30, "COMPLETED");
		// p1 já tinha atendimento concluído antes do início do mês (retorno); p4 não
		appointment(clinic, p1, at(today.withDayOfMonth(1).minusMonths(2).withDayOfMonth(10), 9, 0, zone), 30, "COMPLETED");
		// concluída há mais de 12 meses antes do mês não conta como retorno para p4
		appointment(clinic, p4, at(today.withDayOfMonth(1).minusMonths(14).withDayOfMonth(5), 9, 0, zone), 30, "COMPLETED");
		String budget = approvedBudgetOf(kit);

		JsonNode d = dashboard(clinic, Role.DENTIST_ADMIN, "");

		assertThat(d.get("totalPatients").asInt()).as("5 pacientes, 1 inativo").isEqualTo(4);
		JsonNode todayNode = d.get("appointmentsToday");
		assertThat(todayNode.get("total").asInt()).isEqualTo(4);
		assertThat(todayNode.get("byStatus").get("COMPLETED").asInt()).isEqualTo(2);
		assertThat(todayNode.get("byStatus").get("CANCELED").asInt()).isEqualTo(1);
		assertThat(todayNode.get("byStatus").get("NO_SHOW").asInt()).isEqualTo(1);
		assertThat(todayNode.get("byStatus").get("CONFIRMED").asInt()).isEqualTo(1);
		assertThat(todayNode.get("byStatus").get("SCHEDULED").asInt()).isEqualTo(1);
		assertThat(todayNode.get("byStatus").get("IN_PROGRESS").asInt()).isZero();
		JsonNode ret = d.get("returnRate");
		assertThat(ret.get("attended").asInt()).as("p1 e p4").isEqualTo(2);
		assertThat(ret.get("returning").asInt()).as("só p1").isEqualTo(1);
		assertThat(ret.get("percent").asString()).isEqualTo("50.0");
		assertThat(d.get("monthlyRevenue").get("totalAmount").asString()).isEqualTo("800.00");
		assertThat(d.get("monthlyRevenue").get("approvedCount").asInt()).isEqualTo(1);
		assertThat(budget).isNotEmpty();

		// a semana de hoje tem exatamente os 6 agendamentos de hoje no dia certo
		JsonNode days = d.get("weekly").get("days");
		JsonNode todayDay = null;
		for (JsonNode day : days) {
			if (day.get("date").asString().equals(today.toString())) {
				todayDay = day;
			}
		}
		assertThat(todayDay).isNotNull();
		assertThat(todayDay.get("total").asInt()).isEqualTo(4);
		assertThat(todayDay.get("byStatus").get("NO_SHOW").asInt()).isEqualTo(1);
		long weekTotal = 0;
		for (JsonNode day : days) {
			weekTotal += day.get("total").asLong();
		}
		assertThat(weekTotal).isEqualTo(4);
	}

	@Test
	void receptionGetsEverythingButTheRevenue() throws Exception {
		Kit kit = kit();
		approvedBudgetOf(kit);

		JsonNode dentist = dashboard(kit.setup().clinic(), Role.DENTIST_ADMIN, "");
		JsonNode reception = dashboard(kit.setup().clinic(), Role.RECEPTIONIST, "");

		assertThat(dentist.has("monthlyRevenue")).isTrue();
		assertThat(reception.has("monthlyRevenue")).isFalse();
		assertThat(reception.get("totalPatients").asInt()).isEqualTo(dentist.get("totalPatients").asInt());
		assertThat(reception.get("weekly").get("days").size()).isEqualTo(7);
		mvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
	}

	@Test
	void weekOfSelectsThatWeekAndGroupsByTheClinicLocalDay() throws Exception {
		Setup setup = openClinic();
		Clinic clinic = setup.clinic();
		ZoneId zone = zoneOf(clinic);
		LocalDate monday = LocalDate.of(2026, 3, 2);
		LocalDate sunday = monday.plusDays(6);
		appointment(clinic, setup.patient(), at(sunday, 22, 0, zone), 30, "COMPLETED"); // domingo à noite (já segunda em UTC)
		appointment(clinic, setup.patient(), at(monday, 0, 30, zone), 30, "CONFIRMED"); // segunda de madrugada
		appointment(clinic, setup.patient(), at(sunday.plusDays(1), 0, 30, zone), 30, "CONFIRMED"); // próxima segunda: fora

		JsonNode weekly = dashboard(clinic, Role.DENTIST_ADMIN, "?weekOf=2026-03-04").get("weekly");

		assertThat(weekly.get("weekStart").asString()).isEqualTo("2026-03-02");
		assertThat(weekly.get("weekEnd").asString()).isEqualTo("2026-03-08");
		JsonNode days = weekly.get("days");
		assertThat(days.get(0).get("date").asString()).isEqualTo("2026-03-02");
		assertThat(days.get(0).get("total").asInt()).isEqualTo(1);
		assertThat(days.get(6).get("date").asString()).isEqualTo("2026-03-08");
		assertThat(days.get(6).get("total").asInt()).isEqualTo(1);
		assertThat(days.get(6).get("byStatus").get("COMPLETED").asInt()).isEqualTo(1);
		assertThat(days.get(3).get("total").asInt()).isZero();
	}

	@Test
	void invalidOrOutOfRangeWeekOfIsRejected() throws Exception {
		Setup setup = openClinic();
		Clinic clinic = setup.clinic();

		mvc.perform(get("/api/v1/dashboard?weekOf=abc").with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/dashboard?weekOf=1999-12-31").with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isUnprocessableContent());
		mvc.perform(get("/api/v1/dashboard?weekOf=" + LocalDate.now().plusDays(400)).with(as(clinic, Role.DENTIST_ADMIN)))
				.andExpect(status().isUnprocessableContent());
		mvc.perform(get("/api/v1/dashboard?weekOf=2000-01-01").with(as(clinic, Role.DENTIST_ADMIN))).andExpect(status().isOk());
	}

	@Test
	void anotherClinicDataNeverChangesTheNumbers() throws Exception {
		Setup mine = openClinic();
		Setup other = openClinic();
		ZoneId zone = zoneOf(other.clinic());
		LocalDate today = LocalDate.now(zone);
		appointment(other.clinic(), other.patient(), at(today, 9, 0, zone), 30, "CONFIRMED");
		approvedBudgetOf(kitOf(other.clinic(), other.patient(), createProcedure(other.clinic(), "Limpeza", "100.00", 30),
				createProcedure(other.clinic(), "Canal", "900.00", 90)));

		JsonNode d = dashboard(mine.clinic(), Role.DENTIST_ADMIN, "");

		assertThat(d.get("totalPatients").asInt()).isEqualTo(1);
		assertThat(d.get("appointmentsToday").get("total").asInt()).isZero();
		assertThat(d.get("monthlyRevenue").get("totalAmount").asString()).isEqualTo("0.00");
	}
}
