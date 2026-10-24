package crm_data.support.tenant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Entity;

/** Regra de arquitetura: toda entidade de tabela de negócio herda {@link TenantAwareEntity}. */
public final class TenantEntityRules {

	/**
	 * Entidades que não têm {@code clinic_id}: a própria clínica é o tenant (coluna {@code id}) e os feriados
	 * nacionais são dado global, somente leitura.
	 */
	static final Set<String> TENANT_ROOTS = Set.of("Clinic", "NationalHoliday");

	private TenantEntityRules() {
	}

	public static List<Class<?>> allEntities() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		List<Class<?>> entities = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents("crm_data")) {
			try {
				entities.add(Class.forName(definition.getBeanClassName()));
			}
			catch (ClassNotFoundException ex) {
				throw new IllegalStateException(ex);
			}
		}
		return entities;
	}

	/** Entidades que violam a regra, com mensagem pronta para o relatório do teste. */
	public static List<String> violations(Collection<Class<?>> entities) {
		return entities.stream()
				.filter(entity -> !TENANT_ROOTS.contains(entity.getSimpleName()))
				.filter(entity -> !TenantAwareEntity.class.isAssignableFrom(entity))
				.map(entity -> entity.getName() + " deve estender TenantAwareEntity")
				.toList();
	}
}
