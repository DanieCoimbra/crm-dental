package crm_data.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import crm_data.auth.domain.User;
import crm_data.auth.repository.AuthLookupRepository;
import crm_data.auth.repository.AuthLookupRepository.UserLookup;
import crm_data.auth.repository.UserRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantTransactions;

/**
 * Login com resposta idêntica para e-mail inexistente, senha errada e usuário inativo, e com o mesmo custo de CPU
 * nos três casos (sempre há uma comparação BCrypt, contra um hash fictício quando o e-mail não existe).
 */
@Service
public class LoginService {

	private static final Logger log = LoggerFactory.getLogger(LoginService.class);
	private static final int BCRYPT_MAX_BYTES = 72;

	private final AuthLookupRepository lookup;
	private final UserRepository users;
	private final RefreshTokenService refreshTokens;
	private final TenantTransactions tenantTransactions;
	private final AuthSessionFactory sessions;
	private final PasswordEncoder passwordEncoder;
	private final LoginRateLimiter rateLimiter;
	private final HashingGate hashingGate;
	private final String dummyHash;

	public LoginService(AuthLookupRepository lookup, UserRepository users, RefreshTokenService refreshTokens,
			TenantTransactions tenantTransactions, AuthSessionFactory sessions, PasswordEncoder passwordEncoder,
			LoginRateLimiter rateLimiter, HashingGate hashingGate) {
		this.lookup = lookup;
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.tenantTransactions = tenantTransactions;
		this.sessions = sessions;
		this.passwordEncoder = passwordEncoder;
		this.rateLimiter = rateLimiter;
		this.hashingGate = hashingGate;
		this.dummyHash = passwordEncoder.encode("senha-ficticia-" + UUID.randomUUID()); // mesmo custo dos hashes reais
	}

	/**
	 * @param email já normalizado (minúsculas, sem espaços nas pontas)
	 * @throws BusinessException 401 genérico em qualquer falha de credencial
	 * @throws crm_data.shared.error.RateLimitedException 429 após 5 falhas por minuto do mesmo IP+e-mail, 20 falhas em
	 *         15 min do mesmo e-mail (qualquer IP) ou com todas as vagas de hash ocupadas
	 */
	public AuthSession login(String email, String password, String clientIp) {
		rateLimiter.checkLoginAllowed(clientIp, email);

		Optional<UserLookup> found = lookup.findUserForLogin(email);
		String hash = found.map(UserLookup::passwordHash).orElse(dummyHash);
		boolean passwordMatches = hashingGate.run(() -> matches(password, hash)); // 429 se a CPU já está saturada de hashes
		if (found.isEmpty() || !found.get().active() || !passwordMatches) {
			rateLimiter.recordLoginFailure(clientIp, email);
			log.warn("Falha de login: email={} ip={}", Masking.email(email), clientIp);
			throw new BusinessException(ProblemTypes.UNAUTHORIZED, "Credenciais inválidas.");
		}

		UserLookup account = found.get();
		rateLimiter.resetLoginFailures(clientIp, email);
		return tenantTransactions.execute(account.clinicId(), () -> {
			User user = users.findById(account.id()).orElseThrow(
					() -> new BusinessException(ProblemTypes.UNAUTHORIZED, "Credenciais inválidas."));
			return sessions.create(user, refreshTokens.issue(user.getId(), UUID.randomUUID()));
		});
	}

	/** Compara sem lançar exceção para senhas acima do limite do BCrypt (72 bytes): simplesmente não confere. */
	private boolean matches(String password, String hash) {
		if (password == null || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			passwordEncoder.matches("x", hash); // mantém o custo de CPU
			return false;
		}
		return passwordEncoder.matches(password, hash);
	}
}
