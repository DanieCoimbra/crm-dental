package crm_data.scheduling.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.scheduling.domain.AppointmentStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Agregações da agenda para o dashboard. JPQL sobre {@code Appointment}: o filtro de tenant do Hibernate vale e a RLS do
 * banco segue como barreira final. Só contagens e instantes: nenhum dado pessoal sai daqui.
 */
@Service
@RequiredArgsConstructor
class AppointmentStatsService implements AppointmentStats {

	private final EntityManager entityManager;

	@Override
	@Transactional(readOnly = true)
	public Map<String, Long> countByStatus(Instant ini, Instant fim) {
		List<Object[]> rows = entityManager.createQuery("""
				select a.status, count(a) from Appointment a
				where a.startsAt >= :ini and a.startsAt < :fim
				group by a.status""", Object[].class).setParameter("ini", ini).setParameter("fim", fim).getResultList();
		Map<String, Long> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.put(((AppointmentStatus) row[0]).name(), (Long) row[1]);
		}
		return counts;
	}

	@Override
	@Transactional(readOnly = true)
	public ReturnStats returnStats(Instant mesIni, Instant mesFim, Instant anoIni) {
		long attended = entityManager.createQuery("""
				select count(distinct a.patientId) from Appointment a
				where a.status = :completed and a.startsAt >= :ini and a.startsAt < :fim""", Long.class)
				.setParameter("completed", AppointmentStatus.COMPLETED).setParameter("ini", mesIni).setParameter("fim", mesFim)
				.getSingleResult();
		long returning = entityManager.createQuery("""
				select count(distinct a.patientId) from Appointment a
				where a.status = :completed and a.startsAt >= :ini and a.startsAt < :fim
				  and exists (select 1 from Appointment p
				              where p.patientId = a.patientId and p.status = :completed
				                and p.startsAt >= :anoIni and p.startsAt < :ini)""", Long.class)
				.setParameter("completed", AppointmentStatus.COMPLETED).setParameter("ini", mesIni).setParameter("fim", mesFim)
				.setParameter("anoIni", anoIni).getSingleResult();
		return new ReturnStats(attended, returning);
	}

	@Override
	@Transactional(readOnly = true)
	public List<StartAndStatus> startsAndStatus(Instant ini, Instant fim) {
		return entityManager.createQuery("""
				select a.startsAt, a.status from Appointment a
				where a.startsAt >= :ini and a.startsAt < :fim""", Object[].class).setParameter("ini", ini)
				.setParameter("fim", fim).getResultList().stream()
				.map(row -> new StartAndStatus((Instant) row[0], ((AppointmentStatus) row[1]).name())).toList();
	}
}
