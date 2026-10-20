package crm_data.support.tenant;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** Serviço só de teste: um caso de uso transacional qualquer sobre tabela de negócio. */
@Service
public class TenantProbeService {

	@PersistenceContext
	private EntityManager entityManager;

	@Transactional(readOnly = true)
	public List<UUID> visibleUserIds() {
		return entityManager.createQuery("select u.id from TestUser u", UUID.class).getResultList();
	}
}
