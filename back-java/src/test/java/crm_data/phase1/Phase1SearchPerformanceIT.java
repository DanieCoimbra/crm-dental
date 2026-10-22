package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

import crm_data.shared.security.Role;
import crm_data.support.tenant.PatientFixtures;
import crm_data.support.tenant.TenantFixtures.Clinic;

/**
 * Metas de desempenho da fase com 4 requisições concorrentes: busca de pacientes por nome e por CPF numa clínica de 10
 * mil pacientes e linha do tempo com 500 notas, todas com p95 ≤ 100 ms (folga maior com a variável {@code CI}; {@code -Dperf.limit-ms} sobrescreve o limite).
 * O limite aplicado é impresso, para registrar a medida no relatório.
 */
class Phase1SearchPerformanceIT extends Phase1ItSupport {

	private static final int PATIENTS = 10_000;
	private static final int THREADS = 4;
	private static final int PER_THREAD = 25;

	@Test
	void searchByNameAndByCpfWithTenThousandPatientsStaysWithinTheLimit() throws Exception {
		Clinic clinic = newClinic();
		PatientFixtures.seedWithCpf(clinic, PATIENTS);
		var token = as(clinic, Role.RECEPTIONIST);
		Random random = new Random(42);

		long byName = p95(THREADS, PER_THREAD, i -> authenticatedGet("/api/v1/patients", token, "q",
				i % 2 == 0 ? "paciente " + (1 + random.nextInt(PATIENTS)) : "silva"));
		long byCpf = p95(THREADS, PER_THREAD, i -> authenticatedGet("/api/v1/patients", token, "q",
				String.format("%011d", 1 + random.nextInt(PATIENTS))));
		long byMaskedCpf = p95(THREADS, PER_THREAD, i -> {
			String digits = String.format("%011d", 1 + random.nextInt(PATIENTS));
			return authenticatedGet("/api/v1/patients", token, "q",
					digits.substring(0, 3) + "." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-" + digits.substring(9));
		});

		System.out.printf("F06 busca (%d pacientes, %d concorrentes): nome p95=%d ms, CPF p95=%d ms, CPF com máscara p95=%d ms (limite %d ms)%n",
				PATIENTS, THREADS, byName, byCpf, byMaskedCpf, LIMIT_MS);
		assertThat(byName).as("busca por nome").isLessThanOrEqualTo(LIMIT_MS);
		assertThat(byCpf).as("busca por CPF").isLessThanOrEqualTo(LIMIT_MS);
		assertThat(byMaskedCpf).as("busca por CPF com máscara").isLessThanOrEqualTo(LIMIT_MS);
	}

	@Test
	void theTimelineWithFiveHundredNotesStaysWithinTheLimit() throws Exception {
		Clinic clinic = newClinic();
		String patient = createPatient(clinic, "Maria Souza", null);
		String seed = addNote(clinic, patient, "Restauração do dente 36");
		PatientFixtures.cloneNote(clinic, seed, 499);
		var token = as(clinic, Role.DENTIST_ADMIN);

		long p95 = p95(THREADS, PER_THREAD, i -> authenticatedGet("/api/v1/patients/" + patient + "/notes", token, "size", "50",
				"page", String.valueOf(i % 10)));

		System.out.printf("F06 linha do tempo (500 notas, %d concorrentes): p95=%d ms (limite %d ms)%n", THREADS, p95, LIMIT_MS);
		assertThat(p95).as("linha do tempo").isLessThanOrEqualTo(LIMIT_MS);
	}
}
