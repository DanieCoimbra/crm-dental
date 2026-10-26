package crm_data.phase3;

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

import crm_data.support.tenant.TenantEntityRules;

/**
 * Regras de arquitetura da Fase 3, verificadas sobre o código-fonte (como as das Fases 1 e 2). Os módulos novos
 * ({@code catalog}, {@code commercial}, {@code finance}) podem ainda não existir quando uma feature anterior é entregue:
 * as regras de dependência valem sobre o que existir, e o teste de "módulo existe" é feito à parte, só para a F08.
 *
 * <p>Direção permitida: {@code finance → commercial → catalog, patient, scheduling, clinic}; {@code scheduling → catalog};
 * {@code patient} nunca importa {@code commercial} nem {@code finance} (a anonimização usa a SPI
 * {@code AnonymizationContributor}). Nenhum módulo usa o repositório ou o domínio de outro.
 */
class Phase3ArchitectureTest {

	private static final Path MAIN = Path.of("src", "main", "java", "crm_data");

	private static final Set<String> NEW_MODULES = Set.of("catalog", "commercial", "finance");

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

	private static boolean codeLine(String line) {
		String stripped = line.strip();
		return !stripped.startsWith("*") && !stripped.startsWith("/*") && !stripped.startsWith("//");
	}

	@Test
	void patientNeverImportsCommercialNorFinance() throws IOException {
		List<Source> sources = sources();

		for (String module : List.of("patient", "shared", "auth", "clinic", "record", "scheduling")) {
			assertThat(importsOf(sources, module).stream()
					.filter(i -> i.startsWith("crm_data.commercial.") || i.startsWith("crm_data.finance.")))
							.as("%s não depende de commercial nem de finance", module).isEmpty();
		}
	}

	@Test
	void onlySchedulingAndCommercialMayUseTheCatalog() throws IOException {
		List<Source> sources = sources();

		for (String module : List.of("patient", "shared", "auth", "clinic", "record", "finance")) {
			assertThat(importsOf(sources, module).stream().filter(i -> i.startsWith("crm_data.catalog.")))
					.as("%s não depende de catalog", module).isEmpty();
		}
		assertThat(importsOf(sources, "scheduling").stream().filter(i -> i.startsWith("crm_data.catalog.")))
				.as("scheduling só conhece o catálogo pela interface pública e seus valores")
				.isSubsetOf("crm_data.catalog.service.ProcedureTypeLookup", "crm_data.catalog.service.ProcedureBrief");
	}

	@Test
	void financeTalksToCommercialOnlyThroughTheBudgetLookup() throws IOException {
		assertThat(importsOf(sources(), "finance").stream().filter(i -> i.startsWith("crm_data.commercial.")))
				.isSubsetOf("crm_data.commercial.service.BudgetLookup", "crm_data.commercial.service.BudgetLookup.BudgetBrief",
						"crm_data.commercial.service.BudgetBrief", "crm_data.commercial.service.ApprovedBudget");
	}

	@Test
	void catalogDependsOnNobodyButShared() throws IOException {
		assertThat(importsOf(sources(), "catalog").stream().filter(i -> !i.startsWith("crm_data.catalog.") && !i.startsWith("crm_data.shared.")))
				.isEmpty();
	}

	@Test
	void theNewModulesTalkToTheirNeighboursOnlyThroughPublicInterfaces() throws IOException {
		List<Source> sources = sources();
		List<String> violations = new ArrayList<>();
		for (Source source : sources) {
			if (!NEW_MODULES.contains(source.module())) {
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
	void noModuleOutsideTheNewOnesReachesIntoTheirRepositoryOrDomain() throws IOException {
		List<String> violations = sources().stream().filter(s -> !NEW_MODULES.contains(s.module()))
				.flatMap(s -> s.imports().stream()
						.filter(i -> NEW_MODULES.stream()
								.anyMatch(m -> i.startsWith("crm_data." + m + ".repository.") || i.startsWith("crm_data." + m + ".domain.")))
						.map(i -> s.file() + " -> " + i))
				.toList();

		assertThat(violations).isEmpty();
	}

	@Test
	void theWebLayerOfTheNewModulesNeverUsesRepositories() throws IOException {
		List<String> violations = sources().stream().filter(s -> NEW_MODULES.contains(s.module()) && s.layer().equals("web"))
				.flatMap(s -> s.imports().stream().filter(i -> i.contains(".repository.")).map(i -> s.file() + " -> " + i)).toList();

		assertThat(violations).isEmpty();
	}

	@Test
	void entitiesOfTheNewModulesAvoidTheLombokAnnotationsThatLeakDataOrBreakIdentity() throws IOException {
		List<String> violations = sources().stream().filter(s -> NEW_MODULES.contains(s.module()) && s.layer().equals("domain"))
				.filter(s -> s.text().lines().filter(Phase3ArchitectureTest::codeLine).map(String::strip)
						.anyMatch(l -> l.startsWith("@Data") || l.startsWith("@ToString") || l.startsWith("@EqualsAndHashCode")))
				.map(s -> s.file().getFileName().toString()).toList();

		assertThat(violations).isEmpty();
	}

	@Test
	void pureRulesHaveNoSpringNorPersistenceImports() throws IOException {
		List<String> pure = List.of("shared/money/Money.java", "commercial/service/BudgetPdfRenderer.java",
				"commercial/service/BudgetTransitions.java", "commercial/service/BudgetDraftValidator.java",
				"finance/service/InstallmentPlanner.java");
		List<String> violations = new ArrayList<>();
		for (String name : pure) {
			Path file = MAIN.resolve(name);
			if (!Files.exists(file)) {
				continue; // a classe nasce na sua feature; a regra vale a partir daí
			}
			for (String line : Files.readAllLines(file)) {
				if (line.startsWith("import org.springframework.") || line.startsWith("import jakarta.persistence.")) {
					violations.add(name + " -> " + line);
				}
			}
		}

		assertThat(violations).isEmpty();
	}

	@Test
	void everyTenantEntityExtendsTenantAwareEntity() {
		assertThat(TenantEntityRules.violations(TenantEntityRules.allEntities())).isEmpty();
	}
}
