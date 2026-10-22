package crm_data.phase1;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import crm_data.patient.web.PatientRequests;
import crm_data.record.web.RecordRequests;
import crm_data.support.tenant.TenantEntityRules;

/**
 * Regras de arquitetura da Fase 1, verificadas sobre o código-fonte (sem dependência de ferramenta externa): as
 * fronteiras entre módulos, o ponto único que escreve as variáveis de sessão do banco, a camada web sem repositório e
 * as entidades sempre ligadas ao tenant.
 */
class Phase1ArchitectureTest {

	private static final Path MAIN = Path.of("src", "main", "java", "crm_data");

	private record Source(String module, String layer, Path file, List<String> imports, List<String> lines) {
	}

	private static List<Source> sources() throws IOException {
		try (Stream<Path> walk = Files.walk(MAIN)) {
			return walk.filter(p -> p.toString().endsWith(".java")).map(p -> {
				Path relative = MAIN.relativize(p);
				List<String> lines;
				try {
					lines = Files.readAllLines(p);
				}
				catch (IOException ex) {
					throw new IllegalStateException(ex);
				}
				List<String> imports = lines.stream().filter(l -> l.startsWith("import crm_data."))
						.map(l -> l.substring("import ".length(), l.length() - 1)).toList();
				String layer = relative.getNameCount() > 2 ? relative.getName(1).toString() : "";
				return new Source(relative.getName(0).toString(), layer, p, imports, lines);
			}).collect(Collectors.toList());
		}
	}

	private static List<String> importsOf(List<Source> sources, String module) {
		return sources.stream().filter(s -> s.module().equals(module)).flatMap(s -> s.imports().stream()).distinct().sorted().toList();
	}

	@Test
	void sourcesAreFoundSoTheRulesAreNotVacuous() throws IOException {
		List<Source> sources = sources();

		assertThat(sources.stream().map(Source::module).collect(Collectors.toSet())).contains("shared", "auth", "clinic", "patient", "record");
		assertThat(sources.size()).isGreaterThan(80);
	}

	@Test
	void recordTalksToPatientOnlyThroughPatientLookupAndToClinicOnlyThroughUserNames() throws IOException {
		List<String> imports = importsOf(sources(), "record");

		assertThat(imports.stream().filter(i -> i.startsWith("crm_data.patient."))).containsExactly("crm_data.patient.service.PatientLookup");
		assertThat(imports.stream().filter(i -> i.startsWith("crm_data.clinic."))).containsExactly("crm_data.clinic.service.UserNames");
		assertThat(imports.stream().filter(i -> i.startsWith("crm_data.auth."))).as("record não importa nada de auth").isEmpty();
	}

	@Test
	void patientTalksToRecordOnlyThroughPatientFileService() throws IOException {
		List<String> imports = importsOf(sources(), "patient");

		assertThat(imports.stream().filter(i -> i.startsWith("crm_data.record."))).containsExactly("crm_data.record.service.PatientFileService");
		assertThat(imports.stream().filter(i -> i.startsWith("crm_data.auth.") || i.startsWith("crm_data.clinic."))).isEmpty();
	}

	@Test
	void noPhaseOneModuleReachesIntoAnotherModulesRepositoryOrDomain() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Source source : sources()) {
			if (!source.module().equals("patient") && !source.module().equals("record")) {
				continue;
			}
			for (String imported : source.imports()) {
				boolean foreign = imported.startsWith("crm_data.") && !imported.startsWith("crm_data." + source.module() + ".")
						&& !imported.startsWith("crm_data.shared.");
				boolean internals = imported.contains(".repository.") || imported.contains(".domain.");
				if (foreign && internals) {
					violations.add(source.file() + " -> " + imported);
				}
			}
		}

		assertThat(violations).isEmpty();
	}

	@Test
	void sharedNeverDependsOnABusinessModule() throws IOException {
		assertThat(importsOf(sources(), "shared").stream()
				.filter(i -> Set.of("auth", "clinic", "patient", "record").contains(i.split("\\.")[1]))).isEmpty();
	}

	@Test
	void theWebLayerNeverUsesRepositories() throws IOException {
		List<String> violations = sources().stream().filter(s -> s.layer().equals("web"))
				.flatMap(s -> s.imports().stream().filter(i -> i.contains(".repository.")).map(i -> s.file() + " -> " + i)).toList();

		assertThat(violations).isEmpty();
	}

	@Test
	void onlyTheTransactionDialectWritesTheSessionVariables() throws IOException {
		Set<String> writers = new java.util.TreeSet<>();
		for (Source source : sources()) {
			for (String line : source.lines()) {
				String code = line.strip();
				if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
					continue; // comentários podem citar as variáveis
				}
				if (code.contains("set_config(") || code.matches(".*\"[^\"]*app\\.(clinic_id|role|user_id)[^\"]*\".*")) {
					writers.add(source.file().getFileName().toString());
				}
			}
		}

		// controle positivo: a regra enxerga o dialeto, e ele é o único
		assertThat(writers).as("apenas o TenantHibernateJpaDialect escreve app.clinic_id, app.role e app.user_id")
				.containsExactly("TenantHibernateJpaDialect.java");
	}

	@Test
	void everyEntityExtendsTenantAwareEntityAndThePhaseEntitiesAreAmongThem() {
		List<String> names = TenantEntityRules.allEntities().stream().map(Class::getSimpleName).toList();

		assertThat(names).contains("Patient", "MedicalHistory", "ClinicalNote", "PatientFile", "AuditLog");
		assertThat(TenantEntityRules.violations(TenantEntityRules.allEntities())).isEmpty();
	}

	@Test
	void requestBodiesNeverDeclareFieldsTheServerOwns() {
		Set<String> serverOwned = Set.of("id", "clinicId", "createdBy", "role", "patientId", "storageKey", "anonymizedAt", "active", "createdAt",
				"updatedAt", "deletedAt");
		List<String> violations = new ArrayList<>();
		for (Class<?> container : List.of(PatientRequests.class, RecordRequests.class)) {
			for (Class<?> request : container.getDeclaredClasses()) {
				if (request.isRecord()) {
					for (RecordComponent component : request.getRecordComponents()) {
						// SetActiveRequest.active é o único campo "active" que o cliente envia, no PATCH próprio
						if (serverOwned.contains(component.getName()) && !request.getSimpleName().equals("SetActiveRequest")) {
							violations.add(request.getSimpleName() + "." + component.getName());
						}
					}
				}
			}
		}

		assertThat(violations).isEmpty();
	}
}
