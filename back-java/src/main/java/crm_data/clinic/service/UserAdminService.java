package crm_data.clinic.service;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.auth.domain.User;
import crm_data.auth.repository.UserRepository;
import crm_data.auth.service.HashingGate;
import crm_data.auth.service.RefreshTokenService;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConstraintViolations;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;

/**
 * Gestão da equipe pelo dentista. Toda busca por id acontece DENTRO do tenant (RLS + filtro): id de outra clínica é
 * 404. Regras de papel validadas aqui, no servidor, além da regra de rota do {@code SecurityConfig}. O BCrypt (custo
 * alto) roda antes da transação e dentro do {@link HashingGate}, para não prender conexão do pool durante o hash.
 */
@Service
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class UserAdminService {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokens;
	private final ApplicationEventPublisher events;
	private final HashingGate hashingGate;
	private final TransactionTemplate writeTx;

	public UserAdminService(UserRepository users, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens,
			ApplicationEventPublisher events, HashingGate hashingGate, PlatformTransactionManager transactionManager) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokens = refreshTokens;
		this.events = events;
		this.hashingGate = hashingGate;
		this.writeTx = new TransactionTemplate(transactionManager);
	}

	/** Página ordenada por nome (e id, para paginação estável), somente da clínica do tenant. */
	@Transactional(readOnly = true)
	public Page<User> list(int page, int size) {
		return users.findAllByOrderByNameAscIdAsc(PageRequest.of(page, size));
	}

	/**
	 * Cria SEMPRE um {@code RECEPTIONIST} na clínica do tenant, com {@code created_by} = dentista logado.
	 *
	 * @param email já normalizado (minúsculas, sem espaços nas pontas)
	 * @throws BusinessException {@code conflict} (409) se o e-mail já estiver cadastrado
	 * @throws crm_data.shared.error.RateLimitedException 429 se não houver vaga de hash
	 */
	public User createReceptionist(String name, String email, String password) {
		String passwordHash = hashingGate.run(() -> passwordEncoder.encode(password));
		return writeTx.execute(status -> {
			User user = new User(UUID.randomUUID(), name, email, passwordHash, Role.RECEPTIONIST);
			user.setCreatedBy(TenantContext.userId());
			try {
				user = users.saveAndFlush(user);
			}
			catch (DataIntegrityViolationException ex) {
				if (ConstraintViolations.violates(ex, ConstraintViolations.USER_EMAIL)) {
					throw new BusinessException(ProblemTypes.CONFLICT, "E-mail já cadastrado.");
				}
				throw ex;
			}
			events.publishEvent(new ClinicEvents.UserCreated(user.getId(), user.getClinicId()));
			return user;
		});
	}

	/**
	 * Ativa ou desativa um usuário. Desativar revoga os refresh tokens do alvo.
	 *
	 * @throws BusinessException {@code not-found} (404) se o id não existir no tenant; {@code business-rule} (422) ao
	 *         tentar desativar a si mesmo ou o dentista
	 */
	@Transactional
	public User setActive(UUID id, boolean active) {
		User target = find(id);
		if (target.getId().equals(TenantContext.userId()) || target.getRole() == Role.DENTIST_ADMIN) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE,
					"Não é possível desativar a si mesmo nem o dentista da clínica.");
		}
		target.setActive(active);
		User saved = users.saveAndFlush(target);
		if (!active) {
			refreshTokens.revokeAllOfUser(saved.getId());
		}
		return saved;
	}

	/**
	 * Redefine a senha de um recepcionista (substitui a recuperação por e-mail) e revoga os refresh tokens do alvo.
	 *
	 * @throws BusinessException {@code not-found} (404) fora do tenant; {@code business-rule} (422) se o alvo não for
	 *         recepcionista
	 * @throws crm_data.shared.error.RateLimitedException 429 se não houver vaga de hash
	 */
	public void resetPassword(UUID id, String newPassword) {
		String passwordHash = hashingGate.run(() -> passwordEncoder.encode(newPassword));
		writeTx.executeWithoutResult(status -> {
			User target = find(id);
			if (target.getRole() != Role.RECEPTIONIST) {
				throw new BusinessException(ProblemTypes.BUSINESS_RULE, "A senha do dentista não pode ser redefinida por aqui.");
			}
			target.setPasswordHash(passwordHash);
			users.saveAndFlush(target);
			refreshTokens.revokeAllOfUser(target.getId());
		});
	}

	private User find(UUID id) {
		return users.findOwnById(id).orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Usuário não encontrado."));
	}
}
