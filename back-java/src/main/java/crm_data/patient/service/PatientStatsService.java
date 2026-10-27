package crm_data.patient.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class PatientStatsService implements PatientStats {

	private final EntityManager entityManager;

	@Override
	@Transactional(readOnly = true)
	public long activeCount() {
		return entityManager.createQuery("select count(p) from Patient p where p.active = true", Long.class).getSingleResult();
	}
}
