package crm_data.clinic.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Interface pública do módulo de clínica e usuários para resolver nomes (por exemplo na trilha de auditoria), sem que
 * outro módulo acesse o repositório de usuários.
 */
public interface UserNames {

	/**
	 * Nomes dos usuários da clínica do tenant, numa só consulta. Ids que não existem (ou de outra clínica) ficam fora
	 * do mapa.
	 */
	Map<UUID, String> namesOf(Collection<UUID> userIds);
}
