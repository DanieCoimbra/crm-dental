package crm_data.auth.service;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import crm_data.auth.domain.Clinic;
import crm_data.auth.domain.User;
import crm_data.auth.repository.ClinicRepository;
import crm_data.auth.repository.UserRepository;
import crm_data.auth.service.AuthEvents.ClinicRegistered;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ConstraintViolations;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantTransactions;
import lombok.RequiredArgsConstructor;

/**
 * Registro de clínica + dentista em UMA transação sob o tenant recém-gerado: se qualquer passo falhar (inclusive a
 * gravação do refresh token), nada é persistido. Não existe plano, trial nem cobrança.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

	private final ClinicRepository clinics;
	private final UserRepository users;
	private final RefreshTokenService refreshTokens;
	private final TenantTransactions tenantTransactions;
	private final AuthSessionFactory sessions;
	private final PasswordEncoder passwordEncoder;
	private final ApplicationEventPublisher events;

	/**
	 * @param email já normalizado (minúsculas, sem espaços nas pontas)
	 * @throws BusinessException {@code conflict} (409) se o e-mail já estiver cadastrado
	 */
	public AuthSession register(String clinicName, String name, String email, String password) {
		String passwordHash = passwordEncoder.encode(password); // custo alto: fora da transação
		UUID clinicId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();

		AuthSession session;
		try {
			session = tenantTransactions.execute(clinicId, () -> {
				clinics.save(new Clinic(clinicId, clinicName));
				User dentist = users.saveAndFlush(new User(userId, name, email, passwordHash, Role.DENTIST_ADMIN));
				String refreshToken = refreshTokens.issue(userId, UUID.randomUUID());
				return sessions.create(dentist, refreshToken);
			});
		}
		catch (DataIntegrityViolationException ex) {
			if (ConstraintViolations.violates(ex, ConstraintViolations.USER_EMAIL)) {
				throw new BusinessException(ProblemTypes.CONFLICT, "E-mail já cadastrado.");
			}
			throw ex;
		}
		events.publishEvent(new ClinicRegistered(clinicId, userId));
		return session;
	}
}
