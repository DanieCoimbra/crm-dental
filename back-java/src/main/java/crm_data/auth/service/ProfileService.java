package crm_data.auth.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.auth.domain.User;
import crm_data.auth.repository.UserRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConflictMessages;
import crm_data.shared.error.ConstraintViolations;
import crm_data.shared.error.FieldValidationException;
import crm_data.shared.error.ProblemDetails;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.TokenPrincipal;

/**
 * Perfil do PRÓPRIO usuário do token: o id vem do {@link TokenPrincipal}, nunca de corpo, query ou path. Papel e clínica
 * não são editáveis por aqui. O BCrypt (custo alto) nunca roda dentro de transação nem fora do {@link HashingGate}: o
 * usuário é lido numa transação curta, as senhas são conferidas sem conexão presa e a gravação é outra transação curta,
 * protegida pela {@code version} do usuário contra alteração no intervalo.
 */
@Service
public class ProfileService {

	private static final Pattern CRO_DIGITS = Pattern.compile("^\\d{1,15}$");
	private static final Set<String> BRAZILIAN_STATES = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA",
			"MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokens;
	private final LoginRateLimiter rateLimiter;
	private final HashingGate hashingGate;
	private final TransactionTemplate readTx;
	private final TransactionTemplate writeTx;

	public ProfileService(UserRepository users, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens,
			LoginRateLimiter rateLimiter, HashingGate hashingGate, PlatformTransactionManager transactionManager) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokens = refreshTokens;
		this.rateLimiter = rateLimiter;
		this.hashingGate = hashingGate;
		this.readTx = new TransactionTemplate(transactionManager);
		this.readTx.setReadOnly(true);
		this.writeTx = new TransactionTemplate(transactionManager);
	}

	public User me() {
		return readTx.execute(status -> current());
	}

	/**
	 * Altera nome e e-mail. Mudar o e-mail (identificador de login) exige a senha atual e revoga todas as sessões.
	 *
	 * @param email já normalizado (minúsculas, sem espaços nas pontas)
	 * @param currentPassword só obrigatória quando o e-mail muda
	 * @throws BusinessException {@code validation} (400) se o e-mail muda sem a senha atual; {@code unauthorized} (401)
	 *         se a senha atual estiver errada; {@code conflict} (409) se o e-mail já pertencer a outro usuário ou o
	 *         usuário mudou durante a operação
	 * @throws crm_data.shared.error.RateLimitedException 429 após 5 senhas atuais erradas em 1 minuto
	 */
	public User updateProfile(String name, String email, String currentPassword) {
		User before = readTx.execute(status -> current());
		boolean emailChanges = !before.getEmail().equals(email);
		if (emailChanges) {
			if (currentPassword == null || currentPassword.isBlank()) {
				throw new BusinessException(ProblemTypes.VALIDATION, "Informe a senha atual para alterar o e-mail.");
			}
			rateLimiter.checkPasswordChangeAllowed(before.getId());
			boolean matches = hashingGate.run(() -> before.passwordMatches(passwordEncoder, currentPassword));
			if (!matches) {
				rateLimiter.recordPasswordChangeFailure(before.getId());
				throw new BusinessException(ProblemTypes.UNAUTHORIZED, "Senha atual incorreta.");
			}
			rateLimiter.resetPasswordChangeFailures(before.getId());
		}
		return writeTx.execute(status -> {
			User user = unchangedSince(before);
			user.setName(name);
			user.setEmail(email);
			User saved = saveOrConflict(user);
			if (emailChanges) {
				refreshTokens.revokeAllOfUser(saved.getId());
			}
			return saved;
		});
	}

	/**
	 * Grava ou apaga o CRO do PRÓPRIO dentista. Os dois campos preenchidos (CRO de 1 a 15 dígitos e UF em maiúsculas) ou os
	 * dois nulos; um sem o outro é 400. Controle otimista pela {@code version} do usuário.
	 *
	 * @throws FieldValidationException 400 com {@code errors[]}
	 * @throws BusinessException {@code conflict} (409) se a versão estiver defasada
	 */
	public User registerProfessional(String cro, String state, long version) {
		List<ProblemDetails.FieldError> errors = new ArrayList<>();
		boolean hasCro = cro != null && !cro.isEmpty();
		boolean hasState = state != null && !state.isEmpty();
		if (hasCro != hasState) {
			errors.add(new ProblemDetails.FieldError(hasCro ? "croState" : "cro", "informe o CRO e a UF juntos, ou nenhum"));
		}
		if (hasCro && !CRO_DIGITS.matcher(cro).matches()) {
			errors.add(new ProblemDetails.FieldError("cro", "deve ter de 1 a 15 dígitos"));
		}
		if (hasState && !BRAZILIAN_STATES.contains(state)) {
			errors.add(new ProblemDetails.FieldError("croState", "UF inválida"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException(errors);
		}
		return writeTx.execute(status -> {
			User user = current();
			if (!Objects.equals(user.getVersion(), version)) {
				throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
			}
			user.registerProfessional(hasCro ? cro : null, hasState ? state : null);
			return users.saveAndFlush(user);
		});
	}

	/**
	 * Troca a senha e revoga TODOS os refresh tokens do usuário.
	 *
	 * @throws BusinessException {@code unauthorized} (401) se a senha atual estiver errada; {@code business-rule}
	 *         (422) se a nova for igual à atual; {@code conflict} (409) se o usuário mudou durante a operação
	 * @throws crm_data.shared.error.RateLimitedException 429 após 5 senhas atuais erradas em 1 minuto ou sem vaga de hash
	 */
	public void changePassword(String currentPassword, String newPassword) {
		rateLimiter.checkPasswordChangeAllowed(TokenPrincipal.current().userId()); // 429 antes de gastar CPU com BCrypt
		User before = readTx.execute(status -> current());

		// Uma vaga do portão para as três operações de BCrypt; sai cedo se a senha atual estiver errada
		Verification verification = hashingGate.run(() -> {
			if (!before.passwordMatches(passwordEncoder, currentPassword)) {
				return new Verification(false, false, null);
			}
			if (before.passwordMatches(passwordEncoder, newPassword)) {
				return new Verification(true, true, null);
			}
			return new Verification(true, false, passwordEncoder.encode(newPassword));
		});
		if (!verification.currentMatches()) {
			rateLimiter.recordPasswordChangeFailure(before.getId());
			throw new BusinessException(ProblemTypes.UNAUTHORIZED, "Senha atual incorreta.");
		}
		rateLimiter.resetPasswordChangeFailures(before.getId());
		if (verification.sameAsCurrent()) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE, "A nova senha deve ser diferente da atual.");
		}
		writeTx.executeWithoutResult(status -> {
			User user = unchangedSince(before);
			user.setPasswordHash(verification.newHash());
			users.saveAndFlush(user);
			refreshTokens.revokeAllOfUser(user.getId());
		});
	}

	/** Resultado das conferências de senha feitas dentro do portão de hash. */
	private record Verification(boolean currentMatches, boolean sameAsCurrent, String newHash) {

		/** Nunca imprime o hash. */
		@Override
		public String toString() {
			return "Verification[]";
		}
	}

	/** Recarrega o usuário na transação de gravação e recusa se mudou desde a leitura (as senhas foram conferidas contra a versão antiga). */
	private User unchangedSince(User before) {
		User user = current();
		if (!Objects.equals(user.getVersion(), before.getVersion())) {
			throw new BusinessException(ProblemTypes.CONFLICT, ConflictMessages.STALE_VERSION);
		}
		return user;
	}

	private User current() {
		return users.findOwnById(TokenPrincipal.current().userId())
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
