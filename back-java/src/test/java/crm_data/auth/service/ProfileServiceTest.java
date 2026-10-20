package crm_data.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import crm_data.auth.domain.User;
import crm_data.auth.repository.UserRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

class ProfileServiceTest {

	private static final String CURRENT = "SenhaAtual2026";

	private final UserRepository users = mock(UserRepository.class);
	private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
	private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
	private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
	private final ProfileService service = new ProfileService(users, encoder, refreshTokens, rateLimiter);

	private final UUID userId = UUID.randomUUID();
	private User user;

	@BeforeEach
	void setUp() {
		user = new User(userId, "Ana", "ana@clinica.com", encoder.encode(CURRENT), Role.DENTIST_ADMIN);
		ReflectionTestUtils.setField(user, "clinicId", UUID.randomUUID());
		TenantContext.set(UUID.randomUUID(), userId, Role.DENTIST_ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(user));
		when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private static void assertRule(Runnable action, ProblemTypes expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.type()).isEqualTo(expected));
	}

	@Test
	void meIsAlwaysTheUserOfTheToken() {
		assertThat(service.me()).isSameAs(user);
		verify(users).findById(userId);
	}

	@Test
	void updatesOnlyNameAndEmail() {
		User updated = service.updateProfile("Ana Souza", "ana.souza@clinica.com");

		assertThat(updated.getName()).isEqualTo("Ana Souza");
		assertThat(updated.getEmail()).isEqualTo("ana.souza@clinica.com");
		assertThat(updated.getRole()).isEqualTo(Role.DENTIST_ADMIN);
		assertThat(updated.isActive()).isTrue();
	}

	@Test
	void duplicateEmailIsConflict() {
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
				"duplicate key value violates unique constraint \"ux_users_email\""));

		assertRule(() -> service.updateProfile("Ana", "outra@clinica.com"), ProblemTypes.CONFLICT);
	}

	@Test
	void wrongCurrentPasswordIs401AndNothingChanges() {
		assertRule(() -> service.changePassword("senhaErrada999", "NovaSenha2026"), ProblemTypes.UNAUTHORIZED);

		assertThat(user.passwordMatches(encoder, CURRENT)).isTrue();
		verify(refreshTokens, never()).revokeAllOfUser(any());
	}

	@Test
	void newPasswordEqualToTheCurrentIs422() {
		assertRule(() -> service.changePassword(CURRENT, CURRENT), ProblemTypes.BUSINESS_RULE);

		verify(refreshTokens, never()).revokeAllOfUser(any());
	}

	@Test
	void passwordAbove72BytesNeverReachesTheEncoderAndCountsAsWrong() {
		assertRule(() -> service.changePassword("a1".repeat(40), "NovaSenha2026"), ProblemTypes.UNAUTHORIZED);
	}

	@Test
	void changePasswordStoresANewHashAndRevokesAllSessions() {
		service.changePassword(CURRENT, "NovaSenha2026");

		assertThat(user.passwordMatches(encoder, "NovaSenha2026")).isTrue();
		assertThat(user.passwordMatches(encoder, CURRENT)).isFalse();
		verify(refreshTokens).revokeAllOfUser(userId);
	}

	@Test
	void wrongCurrentPasswordIsCountedAndTheRightOneClearsTheCounter() {
		assertRule(() -> service.changePassword("senhaErrada999", "NovaSenha2026"), ProblemTypes.UNAUTHORIZED);
		verify(rateLimiter).recordPasswordChangeFailure(userId);
		verify(rateLimiter, never()).resetPasswordChangeFailures(any());

		service.changePassword(CURRENT, "NovaSenha2026");
		verify(rateLimiter).resetPasswordChangeFailures(userId);
	}

	@Test
	void blockedUserNeverReachesTheBcryptComparison() {
		org.mockito.Mockito.doThrow(new crm_data.shared.error.RateLimitedException("Muitas tentativas.", 30))
				.when(rateLimiter).checkPasswordChangeAllowed(userId);

		assertThatThrownBy(() -> service.changePassword(CURRENT, "NovaSenha2026"))
				.isInstanceOf(crm_data.shared.error.RateLimitedException.class);

		assertThat(user.passwordMatches(encoder, CURRENT)).isTrue();
		verify(rateLimiter, never()).recordPasswordChangeFailure(any());
		verify(refreshTokens, never()).revokeAllOfUser(any());
	}

	@Test
	void sameAsCurrentIsARuleViolationNotAFailedGuess() {
		assertRule(() -> service.changePassword(CURRENT, CURRENT), ProblemTypes.BUSINESS_RULE);

		verify(rateLimiter, never()).recordPasswordChangeFailure(any());
	}

	@Test
	void tokenUserMissingFromTheTenantIsUnauthorized() {
		when(users.findById(userId)).thenReturn(Optional.empty());

		assertRule(service::me, ProblemTypes.UNAUTHORIZED);
	}
}
