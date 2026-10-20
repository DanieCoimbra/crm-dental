package crm_data.clinic.service;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.domain.User;
import crm_data.auth.repository.UserRepository;
import crm_data.auth.service.RefreshTokenService;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConstraintViolations;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * Gestão da equipe pelo dentista. Toda busca por id acontece DENTRO do tenant (RLS + filtro): id de outra clínica é
 * 404. Regras de papel validadas aqui, no servidor, além da regra de rota do {@code SecurityConfig}.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('DENTIST_ADMIN')")
public class UserAdminService {

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokens;
	private final ApplicationEventPublisher events;

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
	 */
	@Transactional
	public User createReceptionist(String name, String email, String password) {
		User user = new User(UUID.randomUUID(), name, email, passwordEncoder.encode(password), Role.RECEPTIONIST);
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
	 */
	@Transactional
	public void resetPassword(UUID id, String newPassword) {
		User target = find(id);
		if (target.getRole() != Role.RECEPTIONIST) {
			throw new BusinessException(ProblemTypes.BUSINESS_RULE, "A senha do dentista não pode ser redefinida por aqui.");
		}
		target.setPasswordHash(passwordEncoder.encode(newPassword));
		users.saveAndFlush(target);
		refreshTokens.revokeAllOfUser(target.getId());
	}

	private User find(UUID id) {
		return users.findById(id).orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Usuário não encontrado."));
	}
}
