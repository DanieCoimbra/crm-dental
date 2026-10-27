package crm_data.record.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

import crm_data.record.repository.AuditLogRepository;

/** A trilha é somente leitura por construção: entidade imutável e repositório sem nenhum método de escrita. */
class AuditTrailReadOnlyTest {

	@Test
	void entityIsImmutableWithoutSettersOrPublicConstructors() {
		assertThat(AuditLog.class.isAnnotationPresent(Immutable.class)).isTrue();
		assertThat(Arrays.stream(AuditLog.class.getDeclaredMethods()).map(Method::getName)).noneMatch(name -> name.startsWith("set"));
		assertThat(Arrays.stream(AuditLog.class.getConstructors())).isEmpty();
		assertThat(Arrays.stream(AuditLog.class.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers())))
				.allSatisfy(field -> assertThat(field.getType()).isNotEqualTo(Object.class));
	}

	@Test
	void repositoryExposesOnlyFinders() {
		assertThat(Repository.class.isAssignableFrom(AuditLogRepository.class)).isTrue();
		assertThat(CrudRepository.class.isAssignableFrom(AuditLogRepository.class)).as("sem save/delete herdados").isFalse();
		assertThat(AuditLogRepository.class.getInterfaces()).containsExactly(Repository.class);
		assertThat(Arrays.stream(AuditLogRepository.class.getMethods()).map(Method::getName)).isNotEmpty()
				.allMatch(name -> name.startsWith("find"));
	}

	@Test
	void actionsMatchTheDatabaseConstraint() {
		assertThat(AuditAction.values()).extracting(Enum::name).containsExactly("INSERT", "UPDATE", "DELETE", "ANONYMIZE", "VOID", "DOWNLOAD");
	}
}
