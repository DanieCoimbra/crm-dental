package crm_data.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.auth.domain.User;
import crm_data.auth.repository.UserRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class ProfessionalIdentityServiceTest {

	private final UserRepository users = mock(UserRepository.class);
	private final ProfessionalIdentityService service = new ProfessionalIdentityService(users);
	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		TenantContext.set(UUID.randomUUID(), userId, Role.DENTIST_ADMIN);
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private User dentist(String cro, String state) {
		User user = new User(userId, "Dra. Ana", "ana@clinica.com", "hash", Role.DENTIST_ADMIN);
		ReflectionTestUtils.setField(user, "clinicId", UUID.randomUUID());
		user.registerProfessional(cro, state);
		return user;
	}

	@Test
	void currentReturnsNameAndRegistryOfTheTokenUser() {
		when(users.findOwnById(userId)).thenReturn(Optional.of(dentist("12345", "SP")));

		ProfessionalIdentity.Identity identity = service.current();

		assertThat(identity.name()).isEqualTo("Dra. Ana");
		assertThat(identity.cro()).isEqualTo("12345");
		assertThat(identity.croState()).isEqualTo("SP");
		assertThat(identity.hasRegistry()).isTrue();
	}

	@Test
	void userWithoutRegistryHasNullFieldsAndNoRegistry() {
		when(users.findOwnById(userId)).thenReturn(Optional.of(dentist(null, null)));

		ProfessionalIdentity.Identity identity = service.current();

		assertThat(identity.cro()).isNull();
		assertThat(identity.croState()).isNull();
		assertThat(identity.hasRegistry()).isFalse();
	}

	@Test
	void ofReturnsEmptyForUserOfAnotherClinic() {
		UUID other = UUID.randomUUID();
		when(users.findOwnById(other)).thenReturn(Optional.empty()); // o filtro de tenant esconde a linha

		assertThat(service.of(other)).isEmpty();
	}

	@Test
	void currentWithoutUserIsUnauthorized() {
		when(users.findOwnById(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(service::current).isInstanceOf(BusinessException.class);
	}

	@Test
	void toStringNeverPrintsTheRegistry() {
		assertThat(new ProfessionalIdentity.Identity("Ana", "12345", "SP").toString()).doesNotContain("12345", "Ana");
	}
}
