package crm_data.commercial.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crm_data.commercial.domain.Budget;
import crm_data.commercial.domain.BudgetStatus;
import jakarta.persistence.LockModeType;

public interface BudgetRepository extends JpaRepository<Budget, UUID>, JpaSpecificationExecutor<Budget> {

	/**
	 * Carrega o orçamento <strong>travando a linha</strong> ({@code FOR UPDATE}) até o fim da transação: serializa
	 * edição de itens, mudança de status, geração de PDF e plano de parcelas. Consulta JPQL: o filtro do tenant se aplica
	 * (id de outra clínica não volta) e a RLS é a barreira final.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from Budget b where b.id = :id")
	Optional<Budget> findForUpdateById(@Param("id") UUID id);

	/** Id, número e paciente em lote (para {@code BudgetLookup}); id de outra clínica não volta. */
	@Query("select b from Budget b where b.id in :ids")
	List<Budget> findAllByIds(@Param("ids") Collection<UUID> ids);

	/** Aprovados no intervalo {@code [ini, fim)} por {@code decidedAt} (índice parcial {@code ix_budgets_decided}). */
	@Query("select b.id as id, b.number as number, b.patientId as patientId, b.totalAmount as totalAmount, b.decidedAt as decidedAt from Budget b where b.status = crm_data.commercial.domain.BudgetStatus.APPROVED and b.decidedAt >= :ini and b.decidedAt < :fim order by b.decidedAt, b.id")
	List<ApprovedRow> findApprovedBetween(@Param("ini") java.time.Instant ini, @Param("fim") java.time.Instant fim,
			org.springframework.data.domain.Pageable page);

	@Query("select count(b) from Budget b where b.status = crm_data.commercial.domain.BudgetStatus.APPROVED and b.decidedAt >= :ini and b.decidedAt < :fim")
	long countApprovedBetween(@Param("ini") java.time.Instant ini, @Param("fim") java.time.Instant fim);

	@Query("select coalesce(sum(b.totalAmount), 0) from Budget b where b.status = crm_data.commercial.domain.BudgetStatus.APPROVED and b.decidedAt >= :ini and b.decidedAt < :fim")
	java.math.BigDecimal sumApprovedBetween(@Param("ini") java.time.Instant ini, @Param("fim") java.time.Instant fim);

	@Query("select b.id as id, b.number as number, b.patientId as patientId, b.totalAmount as totalAmount, b.decidedAt as decidedAt from Budget b where b.status = crm_data.commercial.domain.BudgetStatus.APPROVED and b.patientId = :patientId order by b.decidedAt desc, b.id desc")
	List<ApprovedRow> findApprovedOfPatient(@Param("patientId") UUID patientId, org.springframework.data.domain.Pageable page);

	@Query("select coalesce(sum(b.totalAmount), 0) from Budget b where b.status = crm_data.commercial.domain.BudgetStatus.APPROVED and b.patientId = :patientId")
	java.math.BigDecimal sumApprovedOfPatient(@Param("patientId") UUID patientId);

	/** Orçamentos em aberto (DRAFT ou SENT) do paciente; OR de igualdades, não IN (o índice sobrevive à RLS). */
	@Query("select count(b) from Budget b where b.patientId = :patientId and (b.status = crm_data.commercial.domain.BudgetStatus.DRAFT or b.status = crm_data.commercial.domain.BudgetStatus.SENT)")
	long countOpenByPatientId(@Param("patientId") UUID patientId);

	@Query("select count(b) from Budget b where b.patientId = :patientId and b.status in :statuses")
	long countByPatientAndStatuses(@Param("patientId") UUID patientId, @Param("statuses") Collection<BudgetStatus> statuses);
}
