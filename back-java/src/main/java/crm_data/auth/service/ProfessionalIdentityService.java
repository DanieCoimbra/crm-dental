package crm_data.auth.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.domain.User;
import crm_data.auth.repository.UserRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.security.TokenPrincipal;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class ProfessionalIdentityService implements ProfessionalIdentity {

	private final UserRepository users;

	@Override
	@Transactional(readOnly = true)
	public Identity current() {
		return of(TokenPrincipal.current().userId())
				.orElseThrow(() -> new BusinessException(ProblemTypes.UNAUTHORIZED, "Autenticação necessária."));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Identity> of(UUID userId) {
		return users.findOwnById(userId).map(ProfessionalIdentityService::identity);
	}

	private static Identity identity(User user) {
		return new Identity(user.getName(), user.getCroNumber(), user.getCroState());
	}
}
