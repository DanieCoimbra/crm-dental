package crm_data.clinic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.auth.domain.User;
import crm_data.auth.repository.UserRepository;
import crm_data.shared.security.Role;

@ExtendWith(MockitoExtension.class)
class UserNamesServiceTest {

	@Mock
	UserRepository users;
	@InjectMocks
	UserNamesService names;

	private static User user(UUID id, String name) {
		User user = new User(id, name, name.toLowerCase().replace(" ", ".") + "@clinica.com", "hash", Role.RECEPTIONIST);
		ReflectionTestUtils.setField(user, "clinicId", UUID.randomUUID());
		return user;
	}

	@Test
	void mapsEachFoundIdToItsNameInASingleQuery() {
		UUID ana = UUID.randomUUID();
		UUID bia = UUID.randomUUID();
		UUID unknown = UUID.randomUUID();
		when(users.findAllById(List.of(ana, bia, unknown))).thenReturn(List.of(user(ana, "Dra Ana"), user(bia, "Bia")));

		var result = names.namesOf(List.of(ana, bia, unknown));

		assertThat(result).containsEntry(ana, "Dra Ana").containsEntry(bia, "Bia").doesNotContainKey(unknown);
		verify(users).findAllById(List.of(ana, bia, unknown));
	}

	@Test
	void anEmptyRequestDoesNotQuery() {
		assertThat(names.namesOf(List.of())).isEmpty();

		verifyNoInteractions(users);
	}
}
