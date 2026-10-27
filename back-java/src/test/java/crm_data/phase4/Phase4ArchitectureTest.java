package crm_data.phase4;

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
 * Regras de arquitetura da Fase 4, verificadas sobre o código-fonte (como as das fases anteriores). Os módulos
 * {@code document} e {@code dashboard} podem ainda não existir quando uma feature anterior é entregue: as regras valem
 * sobre o que existir.
 *
 * <p>Direção permitida: {@code document} e {@code dashboard} dependem de outros módulos só por interfaces públicas
 * ({@code service}), nunca por {@code repository} ou {@code domain}; nenhum módulo antigo depende deles.
 */
class Phase4ArchitectureTest {

	private static final Path MAIN = Path.of("src", "main", "java", "crm_data");

	private static final Set<String> NEW_MODULES = Set.of("document", "dashboard");

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

	private static boolean codeLine(String line) {
		String stripped = line.strip();
		return !stripped.startsWith("*") && !stripped.startsWith("/*") && !stripped.startsWith("//");
	}

	@Test
	void theNewModulesReachOtherModulesOnlyThroughPublicInterfaces() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Source source : sources()) {
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
	void noOtherModuleDependsOnDocumentOrDashboard() throws IOException {
		List<String> violations = sources().stream().filter(s -> !NEW_MODULES.contains(s.module()))
				.flatMap(s -> s.imports().stream()
						.filter(i -> NEW_MODULES.stream().anyMatch(m -> i.startsWith("crm_data." + m + ".")))
						.map(i -> s.file() + " -> " + i))
				.toList();

		assertThat(violations).isEmpty();
	}

	@Test
	void documentAndDashboardDoNotDependOnEachOther() throws IOException {
		List<String> violations = sources().stream().filter(s -> NEW_MODULES.contains(s.module()))
				.flatMap(s -> s.imports().stream()
						.filter(i -> NEW_MODULES.stream().anyMatch(m -> !m.equals(s.module()) && i.startsWith("crm_data." + m + ".")))
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
				.filter(s -> s.text().lines().filter(Phase4ArchitectureTest::codeLine).map(String::strip)
						.anyMatch(l -> l.startsWith("@Data") || l.startsWith("@ToString") || l.startsWith("@EqualsAndHashCode")))
				.map(s -> s.file().getFileName().toString()).toList();

		assertThat(violations).isEmpty();
	}

	@Test
	void pureRulesHaveNoSpringNorPersistenceImports() throws IOException {
		List<String> pure = List.of("document/service/DocumentPdfRenderer.java", "document/service/DocumentIntegrity.java",
				"document/service/DocumentRules.java", "dashboard/service/DashboardWindows.java");
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
	void sharedErrorsDoNotDependOnDocument() throws IOException {
		assertThat(sources().stream().filter(s -> s.module().equals("shared"))
				.flatMap(s -> s.imports().stream().filter(i -> i.startsWith("crm_data.document.")).map(i -> s.file() + " -> " + i))
				.toList()).isEmpty();
	}

	@Test
	void everyTenantEntityExtendsTenantAwareEntity() {
		assertThat(TenantEntityRules.violations(TenantEntityRules.allEntities())).isEmpty();
	}
}
