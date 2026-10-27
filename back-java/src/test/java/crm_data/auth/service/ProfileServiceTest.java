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
	private final ProfileService service = new ProfileService(users, encoder, refreshTokens, rateLimiter,
			new HashingGate(4, java.time.Duration.ofSeconds(1)), mock(org.springframework.transaction.PlatformTransactionManager.class));

	private final UUID userId = UUID.randomUUID();
	private User user;

	@BeforeEach
	void setUp() {
		user = new User(userId, "Ana", "ana@clinica.com", encoder.encode(CURRENT), Role.DENTIST_ADMIN);
		ReflectionTestUtils.setField(user, "clinicId", UUID.randomUUID());
		TenantContext.set(UUID.randomUUID(), userId, Role.DENTIST_ADMIN);
		when(users.findOwnById(userId)).thenReturn(Optional.of(user));
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
		verify(users).findOwnById(userId);
	}

	@Test
	void updatesOnlyNameAndEmail() {
		User updated = service.updateProfile("Ana Souza", "ana.souza@clinica.com", CURRENT);

		assertThat(updated.getName()).isEqualTo("Ana Souza");
		assertThat(updated.getEmail()).isEqualTo("ana.souza@clinica.com");
		assertThat(updated.getRole()).isEqualTo(Role.DENTIST_ADMIN);
		assertThat(updated.isActive()).isTrue();
	}

	@Test
	void duplicateEmailIsConflict() {
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
				"duplicate key value violates unique constraint \"ux_users_email\""));

		assertRule(() -> service.updateProfile("Ana", "outra@clinica.com", CURRENT), ProblemTypes.CONFLICT);
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

	// ---- troca de e-mail: exige a senha atual e revoga as sessões ----

	@Test
	void changingTheEmailWithoutTheCurrentPasswordIs400AndNothingChanges() {
		assertRule(() -> service.updateProfile("Ana", "novo@clinica.com", null), ProblemTypes.VALIDATION);
		assertRule(() -> service.updateProfile("Ana", "novo@clinica.com", "  "), ProblemTypes.VALIDATION);

		assertThat(user.getEmail()).isEqualTo("ana@clinica.com");
		verify(users, never()).saveAndFlush(any(User.class));
		verify(refreshTokens, never()).revokeAllOfUser(any());
	}

	@Test
	void changingTheEmailWithAWrongPasswordIs401AndCountsAsAFailedGuess() {
		assertRule(() -> service.updateProfile("Ana", "novo@clinica.com", "senhaErrada999"), ProblemTypes.UNAUTHORIZED);

		assertThat(user.getEmail()).isEqualTo("ana@clinica.com");
		verify(rateLimiter).recordPasswordChangeFailure(userId);
		verify(refreshTokens, never()).revokeAllOfUser(any());
	}

	@Test
	void changingTheEmailWithTheRightPasswordSavesItAndRevokesAllSessions() {
		User updated = service.updateProfile("Ana", "novo@clinica.com", CURRENT);

		assertThat(updated.getEmail()).isEqualTo("novo@clinica.com");
		verify(rateLimiter).resetPasswordChangeFailures(userId);
		verify(refreshTokens).revokeAllOfUser(userId);
	}

	@Test
	void changingOnlyTheNameNeedsNoPasswordAndKeepsTheSessions() {
		User updated = service.updateProfile("Ana Souza", "ana@clinica.com", null);

		assertThat(updated.getName()).isEqualTo("Ana Souza");
		verify(rateLimiter, never()).checkPasswordChangeAllowed(any());
		verify(refreshTokens, never()).revokeAllOfUser(any());
	}

	@Test
	void emailChangeIsBlockedByTheSamePasswordFailureLimiter() {
		org.mockito.Mockito.doThrow(new crm_data.shared.error.RateLimitedException("Muitas tentativas.", 30))
				.when(rateLimiter).checkPasswordChangeAllowed(userId);

		assertThatThrownBy(() -> service.updateProfile("Ana", "novo@clinica.com", CURRENT))
				.isInstanceOf(crm_data.shared.error.RateLimitedException.class);

		verify(users, never()).saveAndFlush(any(User.class));
	}

	// ---- BCrypt fora da transação e dentro do portão ----

	@Test
	void passwordWorkRunsBeforeTheWriteTransactionOpens() {
		org.springframework.transaction.PlatformTransactionManager tm = mock(org.springframework.transaction.PlatformTransactionManager.class);
		PasswordEncoder spying = org.mockito.Mockito.spy(encoder);
		ProfileService ordered = new ProfileService(users, spying, refreshTokens, rateLimiter,
				new HashingGate(4, java.time.Duration.ofSeconds(1)), tm);

		ordered.changePassword(CURRENT, "NovaSenha2026");

		org.mockito.InOrder order = org.mockito.Mockito.inOrder(spying, tm);
		order.verify(spying).encode("NovaSenha2026");
		order.verify(tm).getTransaction(org.mockito.ArgumentMatchers.argThat(def -> def != null && !def.isReadOnly())); // só a gravação
	}

	@Test
	void saturatedHashingGateAnswers429BeforeAnyWrite() {
		HashingGate full = new HashingGate(1, java.time.Duration.ofMillis(20));
		ProfileService busy = new ProfileService(users, encoder, refreshTokens, rateLimiter, full,
				mock(org.springframework.transaction.PlatformTransactionManager.class));

		full.run(() -> {
			assertThatThrownBy(() -> busy.changePassword(CURRENT, "NovaSenha2026"))
					.isInstanceOf(crm_data.shared.error.RateLimitedException.class);
			return null;
		});

		verify(users, never()).saveAndFlush(any(User.class));
		verify(rateLimiter, never()).recordPasswordChangeFailure(any());
	}

	@Test
	void userChangedBetweenReadAndWriteIsConflictAndNothingIsSaved() {
		User stale = new User(userId, "Ana", "ana@clinica.com", encoder.encode(CURRENT), Role.DENTIST_ADMIN);
		ReflectionTestUtils.setField(stale, "version", 1L);
		ReflectionTestUtils.setField(user, "version", 2L);
		when(users.findOwnById(userId)).thenReturn(Optional.of(stale)).thenReturn(Optional.of(user)); // leitura (v1), depois recarga (v2)

		assertRule(() -> service.changePassword(CURRENT, "NovaSenha2026"), ProblemTypes.CONFLICT);

		verify(users, never()).saveAndFlush(any(User.class));
		verify(refreshTokens, never()).revokeAllOfUser(any());
	}

	@Test
	void tokenUserMissingFromTheTenantIsUnauthorized() {
		when(users.findOwnById(userId)).thenReturn(Optional.empty());

		assertRule(service::me, ProblemTypes.UNAUTHORIZED);
	}

	// ---- Fase 4: CRO ----

	private static void assertFieldErrors(Runnable action, String... fields) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(crm_data.shared.error.FieldValidationException.class,
				ex -> assertThat(ex.errors()).extracting(crm_data.shared.error.ProblemDetails.FieldError::field)
						.containsExactlyInAnyOrder(fields));
	}

	@Test
	void registersAndClearsTheProfessionalRegistryOfTheTokenUser() {
		ReflectionTestUtils.setField(user, "version", 3L);

		User saved = service.registerProfessional("12345", "SP", 3L);
		assertThat(saved.getCroNumber()).isEqualTo("12345");
		assertThat(saved.getCroState()).isEqualTo("SP");

		User cleared = service.registerProfessional(null, null, 3L);
		assertThat(cleared.getCroNumber()).isNull();
		assertThat(cleared.getCroState()).isNull();
		verify(users, org.mockito.Mockito.times(2)).saveAndFlush(any(User.class));
	}

	@Test
	void registryRequiresBothFieldsOrNone() {
		assertFieldErrors(() -> service.registerProfessional("12345", null, 0L), "croState");
		assertFieldErrors(() -> service.registerProfessional(null, "SP", 0L), "cro");
		assertFieldErrors(() -> service.registerProfessional("", "SP", 0L), "cro");
		verify(users, never()).saveAndFlush(any(User.class));
	}

	@Test
	void registryValidatesDigitsAndState() {
		assertFieldErrors(() -> service.registerProfessional("12A45", "SP", 0L), "cro");
		assertFieldErrors(() -> service.registerProfessional("1234567890123456", "SP", 0L), "cro");
		assertFieldErrors(() -> service.registerProfessional("12345", "sp", 0L), "croState");
		assertFieldErrors(() -> service.registerProfessional("12345", "XX", 0L), "croState");
		assertFieldErrors(() -> service.registerProfessional("12A45", "XX", 0L), "cro", "croState");
		ReflectionTestUtils.setField(user, "version", 0L);
		service.registerProfessional("123456789012345", "TO", 0L);
	}

	@Test
	void registryWithStaleVersionIsConflictAndNothingIsSaved() {
		ReflectionTestUtils.setField(user, "version", 5L);

		assertRule(() -> service.registerProfessional("12345", "SP", 4L), ProblemTypes.CONFLICT);

		verify(users, never()).saveAndFlush(any(User.class));
	}

	@Test
	void updatingTheProfileNeverTouchesTheRegistry() {
		user.registerProfessional("999", "RJ");

		User updated = service.updateProfile("Ana Souza", "ana@clinica.com", null);

		assertThat(updated.getCroNumber()).isEqualTo("999");
		assertThat(updated.getCroState()).isEqualTo("RJ");
	}
}
