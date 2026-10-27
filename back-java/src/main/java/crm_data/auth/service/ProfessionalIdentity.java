package crm_data.auth.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Interface pública do módulo de autenticação para ler a identidade profissional (nome, CRO e UF) de um usuário da clínica,
 * sem que outro módulo acesse o repositório de usuários. {@code cro} e {@code croState} são nulos quando não há registro.
 */
public interface ProfessionalIdentity {

	/** Identidade do usuário do token. */
	Identity current();

	/** Identidade de um usuário da clínica do tenant; vazio se não existe ou é de outra clínica. */
	Optional<Identity> of(UUID userId);

	record Identity(String name, String cro, String croState) {

		public boolean hasRegistry() {
			return cro != null && !cro.isBlank() && croState != null && !croState.isBlank();
		}

		/** Nunca imprime o CRO. */
		@Override
		public String toString() {
			return "Identity[]";
		}
	}
}
