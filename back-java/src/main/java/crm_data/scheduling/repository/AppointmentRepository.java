package crm_data.scheduling.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import crm_data.scheduling.domain.Appointment;
import crm_data.scheduling.domain.AppointmentStatus;

/** As demais consultas da agenda entram nas features que as usam (listagem, transições, histórico, lote). */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

	/**
	 * Agendamentos em aberto (agendado ou confirmado) que ainda não terminaram. {@code lower} é {@code agora − 12 h}
	 * e só existe para o índice {@code ix_appt_clinic_start}: como nenhum atendimento dura mais de 12 h, quem termina
	 * depois de agora começou depois de {@code lower}.
	 */
	@Query("""
			select a from Appointment a
			where a.startsAt >= :lower and a.endsAt > :now
			  and (a.status = crm_data.scheduling.domain.AppointmentStatus.SCHEDULED
			    or a.status = crm_data.scheduling.domain.AppointmentStatus.CONFIRMED)
			order by a.startsAt, a.id""")
	List<Appointment> findOpenFuture(@Param("now") Instant now, @Param("lower") Instant lower);

	/**
	 * Agenda da janela {@code [from, to)}: quem intersecta a janela, inclusive o que começou antes dela. {@code lower} é
	 * {@code from − 12 h} e só serve ao índice {@code ix_appt_clinic_start} (nenhum atendimento dura mais de 12 h).
	 */
	/** O paciente tem atendimento em andamento? (bloqueia a inativação) */
	boolean existsByPatientIdAndStatus(UUID patientId, AppointmentStatus status);

	/** Agendamentos futuros em aberto do paciente: o que a inativação cancela. */
	@Query("""
			select a from Appointment a
			where a.patientId = :patientId and a.endsAt > :now
			  and (a.status = crm_data.scheduling.domain.AppointmentStatus.SCHEDULED
			    or a.status = crm_data.scheduling.domain.AppointmentStatus.CONFIRMED)
			order by a.startsAt, a.id""")
	List<Appointment> findOpenFutureByPatient(@Param("patientId") UUID patientId, @Param("now") Instant now);

	/** Histórico do paciente; a ordem ({@code startsAt DESC, id DESC}) vem do {@link Pageable}. */
	Page<Appointment> findByPatientId(UUID patientId, Pageable pageable);

	/** Lê o agendamento travando a linha até o fim da transação (serializa as notas do mesmo atendimento). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Appointment a where a.id = :id")
	Optional<Appointment> findForUpdateById(@Param("id") UUID id);

	/** Há outro atendimento em andamento na clínica? (no máximo um {@code IN_PROGRESS}, regra do backend). */
	boolean existsByStatusAndIdNot(AppointmentStatus status, UUID id);

	@Query("""
			select a from Appointment a
			where a.startsAt >= :lower and a.startsAt < :to and a.endsAt > :from
			order by a.startsAt, a.id""")
	List<Appointment> findWindow(@Param("from") Instant from, @Param("to") Instant to, @Param("lower") Instant lower);

	@Query("""
			select a from Appointment a
			where a.startsAt >= :lower and a.startsAt < :to and a.endsAt > :from and a.status in :statuses
			order by a.startsAt, a.id""")
	List<Appointment> findWindowByStatus(@Param("from") Instant from, @Param("to") Instant to, @Param("lower") Instant lower,
			@Param("statuses") Collection<AppointmentStatus> statuses);
	/**
	 * Como {@code findById}, mas por JPQL: o filtro Hibernate do tenant se aplica ({@code find} por id não o aplica) e a RLS segue
	 * como barreira final. Id de outra clínica não volta.
	 */
	@Query("select a from Appointment a where a.id = :id")
	Optional<Appointment> findOwnById(@Param("id") UUID id);
}
