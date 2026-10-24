package crm_data.phase2;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import crm_data.clinic.web.ClinicRequests;
import crm_data.support.tenant.TenantEntityRules;

/**
 * Regras de arquitetura da Fase 2, verificadas sobre o código-fonte como as da Fase 1: o módulo {@code scheduling}
 * não cria ciclo, conversa com os vizinhos só pelas interfaces públicas e as entidades da agenda seguem as
 * convenções (sem Lombok perigoso, ligadas ao tenant).
 */
class Phase2ArchitectureTest {

	private static final Path MAIN = Path.of("src", "main", "java", "crm_data");

	private record Source(String module, String layer, Path file, List<String> imports, String text) {
	}

	private static List<Source> sources() throws IOException {
		try (Stream<Path> walk = Files.walk(MAIN)) {
			return walk.filter(p -> p.toString().endsWith(".java")).map(p -> {
				Path relative = MAIN.relativize(p);
				String text;
				try {
					text = Files.readString(p);
				}
				catch (IOException ex) {
					throw new IllegalStateException(ex);
				}
				List<String> imports = text.lines().filter(l -> l.startsWith("import crm_data."))
						.map(l -> l.substring("import ".length(), l.length() - 1)).toList();
				String layer = relative.getNameCount() > 2 ? relative.getName(1).toString() : "";
				return new Source(relative.getName(0).toString(), layer, p, imports, text);
			}).collect(Collectors.toList());
		}
	}

	private static List<String> importsOf(List<Source> sources, String module) {
		return sources.stream().filter(s -> s.module().equals(module)).flatMap(s -> s.imports().stream()).distinct().sorted().toList();
	}

	@Test
	void theSchedulingModuleExistsSoTheRulesAreNotVacuous() throws IOException {
		List<Source> sources = sources();

		assertThat(sources.stream().map(Source::module).collect(Collectors.toSet())).contains("scheduling");
		assertThat(sources.stream().filter(s -> s.module().equals("scheduling")).map(Source::layer).collect(Collectors.toSet()))
				.contains("domain", "repository");
	}

	@Test
	void noOtherModuleDependsOnSchedulingExceptRecordThroughItsPublicLookup() throws IOException {
		List<Source> sources = sources();

		for (String module : List.of("shared", "auth", "clinic", "patient")) {
			assertThat(importsOf(sources, module).stream().filter(i -> i.startsWith("crm_data.scheduling.")))
					.as("%s não depende de scheduling", module).isEmpty();
		}
		assertThat(importsOf(sources, "record").stream().filter(i -> i.startsWith("crm_data.scheduling.")))
				.as("record só conhece scheduling pela interface pública de leitura")
				.isSubsetOf("crm_data.scheduling.service.AppointmentLookup");
	}

	@Test
	void schedulingTalksToItsNeighboursOnlyThroughTheirPublicInterfaces() throws IOException {
		List<String> imports = importsOf(sources(), "scheduling");

		assertThat(imports.stream().filter(i -> i.startsWith("crm_data.patient.")))
				.isSubsetOf("crm_data.patient.service.PatientLookup", "crm_data.patient.service.PatientBrief",
						"crm_data.patient.service.PatientEvents");
		assertThat(imports.stream().filter(i -> i.startsWith("crm_data.clinic."))).isSubsetOf("crm_data.clinic.service.ClinicZones");
		assertThat(imports.stream().filter(i -> i.startsWith("crm_data.auth."))).as("scheduling não importa nada de auth").isEmpty();
		assertThat(imports.stream().filter(i -> i.startsWith("crm_data.record."))).as("scheduling não importa nada de record").isEmpty();
	}

	@Test
	void noModuleReachesIntoAnotherModulesRepositoryOrDomainIncludingScheduling() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Source source : sources()) {
			// clinic fica de fora: por desenho ele administra Clinic e User, entidades que vivem em auth
			if (!Set.of("scheduling", "patient", "record").contains(source.module())) {
				continue;
			}
			for (String imported : source.imports()) {
				boolean foreign = !imported.startsWith("crm_data." + source.module() + ".") && !imported.startsWith("crm_data.shared.");
				if (foreign && (imported.contains(".repository.") || imported.contains(".domain."))) {
					violations.add(source.file() + " -> " + imported);
				}
			}
		}

		assertThat(violations).isEmpty();
	}

	@Test
	void theWebLayerOfSchedulingNeverUsesRepositories() throws IOException {
		List<String> violations = sources().stream().filter(s -> s.module().equals("scheduling") && s.layer().equals("web"))
				.flatMap(s -> s.imports().stream().filter(i -> i.contains(".repository.")).map(i -> s.file() + " -> " + i)).toList();

		assertThat(violations).isEmpty();
	}

	@Test
	void schedulingEntitiesAvoidTheLombokAnnotationsThatLeakDataOrBreakIdentity() throws IOException {
		List<String> violations = sources().stream().filter(s -> s.module().equals("scheduling") && s.layer().equals("domain"))
				.filter(s -> s.text().lines().map(String::strip).filter(l -> !l.startsWith("*") && !l.startsWith("/*") && !l.startsWith("//"))
						.anyMatch(l -> l.startsWith("@Data") || l.startsWith("@ToString") || l.startsWith("@EqualsAndHashCode")))
				.map(s -> s.file().getFileName().toString()).toList();

		assertThat(violations).isEmpty();
	}

	@Test
	void everyTenantEntityOfTheAgendaExtendsTenantAwareEntityAndOnlyHolidaysAreGlobal() {
		List<String> names = TenantEntityRules.allEntities().stream().map(Class::getSimpleName).toList();

		assertThat(names).contains("Appointment", "BusinessHour", "ClinicHoliday", "NationalHoliday");
		assertThat(TenantEntityRules.violations(TenantEntityRules.allEntities())).isEmpty();
	}

	@Test
	void theClinicRequestNeverDeclaresServerOwnedFields() {
		Set<String> serverOwned = Set.of("id", "clinicId", "createdBy", "role");

		assertThat(java.util.Arrays.stream(ClinicRequests.UpdateClinicRequest.class.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName).filter(serverOwned::contains)).isEmpty();
	}
}
