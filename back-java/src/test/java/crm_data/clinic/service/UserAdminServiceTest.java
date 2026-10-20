package crm_data.clinic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.auth.domain.User;
import crm_data.auth.repository.UserRepository;
import crm_data.auth.service.RefreshTokenService;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

/** Regras de papel da gestão de equipe, isoladas (a integração com banco e RLS está em ClinicUsersIT). */
class UserAdminServiceTest {

	private final UserRepository users = mock(UserRepository.class);
	private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
	private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final UserAdminService service = new UserAdminService(users, encoder, refreshTokens, events);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID dentistId = UUID.randomUUID();
	private User dentist;
	private User receptionist;

	private static User user(UUID id, UUID clinicId, Role role) {
		User user = new User(id, "Nome " + id, id + "@clinica.com", "hash", role);
		ReflectionTestUtils.setField(user, "clinicId", clinicId);
		return user;
	}

	@BeforeEach
	void setUp() {
		dentist = user(dentistId, clinicId, Role.DENTIST_ADMIN);
		receptionist = user(UUID.randomUUID(), clinicId, Role.RECEPTIONIST);
		TenantContext.set(clinicId, dentistId, Role.DENTIST_ADMIN);
		when(users.findById(dentistId)).thenReturn(Optional.of(dentist));
		when(users.findById(receptionist.getId())).thenReturn(Optional.of(receptionist));
		when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private static void assertRule(Runnable action, ProblemTypes expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(expected));
	}

	// ---- criar ----

	@Test
	void createsAlwaysAReceptionistWithHashedPasswordAndCreatedByTheDentist() {
		User created = service.createReceptionist("Bia", "bia@clinica.com", "SenhaForte2026");

		assertThat(created.getRole()).isEqualTo(Role.RECEPTIONIST);
		assertThat(created.isActive()).isTrue();
		assertThat(created.getCreatedBy()).isEqualTo(dentistId);
		assertThat(created.passwordMatches(encoder, "SenhaForte2026")).isTrue();
		assertThat(created.passwordMatches(encoder, "OutraSenha2026")).isFalse();
		verify(events).publishEvent(new ClinicEvents.UserCreated(created.getId(), created.getClinicId()));
	}

	@Test
	void createdUserIdIsGeneratedByTheServer() {
		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		service.createReceptionist("Bia", "bia@clinica.com", "SenhaForte2026");
		verify(users).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getId()).isNotNull();
	}

	@Test
	void duplicateEmailBecomesConflict() {
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
				"duplicate key value violates unique constraint \"ux_users_email\""));

		assertRule(() -> service.createReceptionist("Bia", "bia@clinica.com", "SenhaForte2026"), ProblemTypes.CONFLICT);
		verifyNoInteractions(events);
	}

	@Test
	void otherIntegrityViolationsAreNotDisguisedAsEmailConflict() {
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("violates foreign key constraint"));

		assertThatThrownBy(() -> service.createReceptionist("Bia", "bia@clinica.com", "SenhaForte2026"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- ativar / desativar ----

	@Test
	void deactivatingAReceptionistRevokesHerSessions() {
		User result = service.setActive(receptionist.getId(), false);

		assertThat(result.isActive()).isFalse();
		verify(refreshTokens).revokeAllOfUser(receptionist.getId());
	}

	@Test
	void reactivatingDoesNotRevokeAnything() {
		receptionist.setActive(false);

		User result = service.setActive(receptionist.getId(), true);

		assertThat(result.isActive()).isTrue();
		verify(refreshTokens, never()).revokeAllOfUser(any());
	}

	@Test
	void dentistCannotDeactivateHimself_422() {
		assertRule(() -> service.setActive(dentistId, false), ProblemTypes.BUSINESS_RULE);
		assertThat(dentist.isActive()).isTrue();
		verify(users, never()).saveAndFlush(any());
		verifyNoInteractions(refreshTokens);
	}

	@Test
	void anotherDentistCannotBeDeactivatedEither_422() {
		User otherDentist = user(UUID.randomUUID(), clinicId, Role.DENTIST_ADMIN);
		when(users.findById(otherDentist.getId())).thenReturn(Optional.of(otherDentist));

		assertRule(() -> service.setActive(otherDentist.getId(), false), ProblemTypes.BUSINESS_RULE);
	}

	@Test
	void unknownOrOtherClinicIdIsNotFound_404() {
		UUID foreign = UUID.randomUUID();
		when(users.findById(foreign)).thenReturn(Optional.empty()); // a RLS esconde usuários de outra clínica

		assertRule(() -> service.setActive(foreign, false), ProblemTypes.NOT_FOUND);
		assertRule(() -> service.resetPassword(foreign, "SenhaForte2026"), ProblemTypes.NOT_FOUND);
	}

	// ---- redefinir senha ----

	@Test
	void resetChangesTheHashAndRevokesTheTargetSessions() {
		service.resetPassword(receptionist.getId(), "NovaSenha2026");

		assertThat(receptionist.passwordMatches(encoder, "NovaSenha2026")).isTrue();
		verify(refreshTokens).revokeAllOfUser(receptionist.getId());
	}

	@Test
	void dentistPasswordCannotBeResetHere_422() {
		assertRule(() -> service.resetPassword(dentistId, "NovaSenha2026"), ProblemTypes.BUSINESS_RULE);
		verifyNoInteractions(refreshTokens);
		verify(users, never()).saveAndFlush(any());
	}

	// ---- listar ----

	@Test
	void listAsksForOneStablyOrderedPage() {
		Page<User> page = new PageImpl<>(List.of(dentist, receptionist), PageRequest.of(1, 50), 52);
		when(users.findAllByOrderByNameAscIdAsc(any(Pageable.class))).thenReturn(page);

		assertThat(service.list(1, 50)).isSameAs(page);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(users).findAllByOrderByNameAscIdAsc(pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
	}
}
