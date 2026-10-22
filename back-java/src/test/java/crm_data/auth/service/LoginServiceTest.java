package crm_data.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import crm_data.auth.domain.User;
import crm_data.auth.repository.AuthLookupRepository;
import crm_data.auth.repository.AuthLookupRepository.UserLookup;
import crm_data.auth.repository.UserRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.error.RateLimitedException;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantTransactions;

@ExtendWith(OutputCaptureExtension.class)
class LoginServiceTest {

	private static final String IP = "203.0.113.7";
	private static final String EMAIL = "joao@clinica.com";
	private static final String PASSWORD = "senhaCorreta123";

	private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
	private final AuthLookupRepository lookup = mock(AuthLookupRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
	private final TenantTransactions tenantTransactions = mock(TenantTransactions.class);
	private final AuthSessionFactory sessions = mock(AuthSessionFactory.class);
	private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);

	private final UUID clinicId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final User user = new User(userId, "João", EMAIL, "hash", Role.RECEPTIONIST);
	private final AuthSession session = new AuthSession("a", "r", "Bearer", 900, null);
	private LoginService service;
	private String realHash;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		service = new LoginService(lookup, users, refreshTokens, tenantTransactions, sessions, encoder, rateLimiter,
				new HashingGate(4, java.time.Duration.ofSeconds(1)));
		realHash = encoder.encode(PASSWORD);
		when(tenantTransactions.execute(any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
		when(users.findById(userId)).thenReturn(Optional.of(user));
		when(refreshTokens.issue(any(), any())).thenReturn("refresh-novo");
		when(sessions.create(user, "refresh-novo")).thenReturn(session);
	}

	private void account(boolean active) {
		when(lookup.findUserForLogin(EMAIL)).thenReturn(Optional.of(new UserLookup(userId, clinicId, realHash, Role.RECEPTIONIST, active)));
	}

	private void assertGenericUnauthorized(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemTypes.UNAUTHORIZED);
			assertThat(ex.getMessage()).isEqualTo("Credenciais inválidas.");
		});
	}

	@Test
	void validCredentialsIssueASessionInTheUsersTenantAndClearTheFailures() {
		account(true);

		assertThat(service.login(EMAIL, PASSWORD, IP)).isSameAs(session);

		verify(tenantTransactions).execute(eq(clinicId), any());
		verify(rateLimiter).resetLoginFailures(IP, EMAIL);
		verify(rateLimiter, never()).recordLoginFailure(anyString(), anyString());
	}

	@Test
	void unknownEmailWrongPasswordAndInactiveUserAreIndistinguishable() {
		when(lookup.findUserForLogin("ninguem@clinica.com")).thenReturn(Optional.empty());
		account(true);
		AuthLookupRepository.UserLookup inactive = new UserLookup(userId, clinicId, realHash, Role.RECEPTIONIST, false);

		assertGenericUnauthorized(() -> service.login("ninguem@clinica.com", PASSWORD, IP));
		assertGenericUnauthorized(() -> service.login(EMAIL, "senhaErrada999", IP));
		when(lookup.findUserForLogin(EMAIL)).thenReturn(Optional.of(inactive));
		assertGenericUnauthorized(() -> service.login(EMAIL, PASSWORD, IP)); // senha certa, usuário inativo

		verify(refreshTokens, never()).issue(any(), any());
	}

	@Test
	void everyBranchRunsExactlyOneBcryptComparison_equalizingTheTime() {
		when(lookup.findUserForLogin("ninguem@clinica.com")).thenReturn(Optional.empty());
		account(true);

		assertGenericUnauthorized(() -> service.login("ninguem@clinica.com", PASSWORD, IP));
		verify(encoder, times(1)).matches(eq(PASSWORD), anyString()); // contra o hash fictício

		assertGenericUnauthorized(() -> service.login(EMAIL, "senhaErrada999", IP));
		verify(encoder, times(1)).matches(eq("senhaErrada999"), eq(realHash));
	}

	@Test
	void unknownEmailIsComparedAgainstADummyHashOfTheSameCost() {
		when(lookup.findUserForLogin("ninguem@clinica.com")).thenReturn(Optional.empty());

		assertGenericUnauthorized(() -> service.login("ninguem@clinica.com", PASSWORD, IP));

		org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(encoder).matches(eq(PASSWORD), hash.capture());
		assertThat(hash.getValue()).startsWith("$2a$04$"); // mesmo custo do encoder configurado
		assertThat(hash.getValue()).isNotEqualTo(realHash);
	}

	@Test
	void failuresAreRecordedForTheIpAndEmailPair() {
		account(true);

		assertGenericUnauthorized(() -> service.login(EMAIL, "senhaErrada999", IP));

		verify(rateLimiter).recordLoginFailure(IP, EMAIL);
		verify(rateLimiter, never()).resetLoginFailures(anyString(), anyString());
	}

	@Test
	void rateLimitIsCheckedBeforeAnyLookupOrPasswordWork() {
		doThrow(new RateLimitedException("Muitas tentativas.", 30)).when(rateLimiter).checkLoginAllowed(IP, EMAIL);

		assertThatThrownBy(() -> service.login(EMAIL, PASSWORD, IP)).isInstanceOf(RateLimitedException.class);

		InOrder order = inOrder(rateLimiter);
		order.verify(rateLimiter).checkLoginAllowed(IP, EMAIL);
		org.mockito.Mockito.verifyNoInteractions(lookup);
		verify(encoder, never()).matches(anyString(), anyString());
	}

	@Test
	void saturatedHashingGateAnswers429WithoutCountingAFailureOrTouchingTheEncoder() {
		HashingGate full = new HashingGate(1, java.time.Duration.ofMillis(20));
		LoginService busy = new LoginService(lookup, users, refreshTokens, tenantTransactions, sessions, encoder, rateLimiter, full);
		account(true);
		org.mockito.Mockito.clearInvocations(encoder);

		full.run(() -> {
			assertThatThrownBy(() -> busy.login(EMAIL, PASSWORD, IP)).isInstanceOf(RateLimitedException.class);
			return null;
		});

		verify(encoder, never()).matches(anyString(), anyString());
		verify(rateLimiter, never()).recordLoginFailure(anyString(), anyString()); // falha de capacidade não é senha errada
	}

	@Test
	void passwordsAbove72BytesFailGracefullyInsteadOfBlowingUpTheEncoder() {
		account(true);

		assertGenericUnauthorized(() -> service.login(EMAIL, "a1".repeat(40), IP)); // 80 bytes
		assertGenericUnauthorized(() -> service.login(EMAIL, "😀".repeat(20), IP)); // 80 bytes em UTF-8
	}

	@Test
	void userVanishingBetweenLookupAndSessionIsUnauthorized() {
		account(true);
		when(users.findById(userId)).thenReturn(Optional.empty());

		assertGenericUnauthorized(() -> service.login(EMAIL, PASSWORD, IP));
	}

	@Test
	void failureLogsMaskTheEmailAndNeverContainPasswordOrHash(CapturedOutput output) {
		account(true);

		assertGenericUnauthorized(() -> service.login(EMAIL, "senhaErrada999", IP));

		assertThat(output.getAll()).contains("Falha de login").contains("j***@clinica.com").contains(IP)
				.doesNotContain(EMAIL).doesNotContain("senhaErrada999").doesNotContain(realHash);
	}
}
