package crm_data.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import crm_data.support.negative.NotTenantEntity;
import crm_data.support.tenant.TenantEntityRules;
import crm_data.support.tenant.TestUser;

class TenantEntityArchitectureTest {

	@Test
	void everyBusinessEntityExtendsTenantAwareEntity() {
		List<Class<?>> entities = TenantEntityRules.allEntities();

		assertThat(entities).as("a varredura precisa enxergar ao menos a entidade de teste").contains(TestUser.class);
		assertThat(TenantEntityRules.violations(entities)).isEmpty();
	}

	@Test
	void ruleFlagsAnEntityThatDoesNotExtendTenantAwareEntity() {
		assertThat(TenantEntityRules.violations(List.of(NotTenantEntity.class, TestUser.class)))
				.singleElement().asString().contains("NotTenantEntity");
	}
}
