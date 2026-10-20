package crm_data.auth.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.domain.User;
import crm_data.auth.repository.UserRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConstraintViolations;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.TokenPrincipal;
import lombok.RequiredArgsConstructor;

/**
 * Perfil do PRÓPRIO usuário do token: o id vem do {@link TokenPrincipal}, nunca de corpo, query ou path. Papel e clínica
 * não são editáveis por aqui.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokens;
	private final LoginRateLimiter rateLimiter;

	@Transactional(readOnly = true)
	public User me() {
		return current();
	}

	/**
	 * @param email já normalizado (minúsculas, sem espaços nas pontas)
	 * @throws BusinessException {@code conflict} (409) se o e-mail já pertencer a outro usuário
	 */
	@Transactional
	public User updateProfile(String name, String email) {
		User user = current();
		user.setName(name);
		user.setEmail(email);
		return saveOrConflict(user);
	}

	/**
	 * Troca a senha e revoga TODOS os refresh tokens do usuário.
	 *
	 * @throws BusinessException {@code unauthorized} (401) se a senha atual estiver errada; {@code business-rule}
	 *         (422) se a nova for igual à atual
	 * @throws crm_data.shared.error.RateLimitedException 429 após 5 senhas atuais erradas em 1 minuto
	 */
	@Transactional
	public void changePassword(String currentPassword, String newPassword) {
		User user = current();
		rateLimiter.checkPasswordChangeAllowed(user.getId()); // 429 antes de gastar CPU com BCrypt
		if (!user.passwordMatches(passwordEncoder, currentPassword)) {
			rateLimiter.recordPasswordChangeFailure(user.getId());
			throw new BusinessException(ProblemTypes.UNAUTHORIZED, "Senha atual incorreta.");
		}
		rateLimiter.resetPasswordChangeFailures(user.getId());
		if (user.passwordMatches(passwordEncoder, newPassword)) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE, "A nova senha deve ser diferente da atual.");
		}
		user.setPasswordHash(passwordEncoder.encode(newPassword));
		users.saveAndFlush(user);
		refreshTokens.revokeAllOfUser(user.getId());
	}

	private User current() {
		return users.findById(TokenPrincipal.current().userId())
				.orElseThrow(() -> new BusinessException(ProblemTypes.UNAUTHORIZED, "Autenticação necessária."));
	}

	private User saveOrConflict(User user) {
		try {
			return users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			if (ConstraintViolations.violates(ex, ConstraintViolations.USER_EMAIL)) {
				throw new BusinessException(ProblemTypes.CONFLICT, "E-mail já cadastrado.");
			}
			throw ex;
		}
	}
}
