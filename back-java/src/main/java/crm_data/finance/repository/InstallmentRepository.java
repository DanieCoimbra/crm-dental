package crm_data.finance.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crm_data.finance.domain.Installment;

public interface InstallmentRepository extends JpaRepository<Installment, UUID> {

	String ROW = "select new crm_data.finance.repository.InstallmentRow(i.id, i.patientId, i.budgetId, i.number, i.status, i.amount, i.dueDate, i.version) from Installment i ";
	String OPEN = "i.status = crm_data.finance.domain.InstallmentStatus.OPEN";

	/** Parcelas não canceladas do orçamento, na ordem do plano. */
	@Query("select i from Installment i where i.budgetId = :budgetId and i.status <> crm_data.finance.domain.InstallmentStatus.CANCELED order by i.number asc, i.id asc")
	List<Installment> findActiveByBudgetId(@Param("budgetId") UUID budgetId);

	/** Todas as parcelas do orçamento (inclusive canceladas), por número e id. */
	List<Installment> findAllByBudgetIdOrderByNumberAscIdAsc(UUID budgetId);

	@Query("select i from Installment i where i.id = :id")
	Optional<Installment> findProjectionById(@Param("id") UUID id);

	// ---- projeções de leitura (pagamentos e relatórios) ----

	@Query(ROW + "where i.id = :id")
	Optional<InstallmentRow> findRowById(@Param("id") UUID id);

	/** A receber: abertas com vencimento no intervalo, por vencimento e id (índice parcial {@code ix_inst_open_due}). */
	@Query(ROW + "where " + OPEN + " and i.dueDate >= :from and i.dueDate <= :to order by i.dueDate, i.id")
	List<InstallmentRow> receivables(@Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

	/** Quantidade e soma do mesmo filtro de {@link #receivables} (uma só consulta para o total da página e o resumo). */
	@Query("select count(i) as count, coalesce(sum(i.amount), 0) as amount from Installment i where " + OPEN
			+ " and i.dueDate >= :from and i.dueDate <= :to")
	Totals receivablesTotals(@Param("from") LocalDate from, @Param("to") LocalDate to);

	/** Vencidas: abertas com vencimento anterior a hoje na clínica, as mais antigas primeiro. */
	@Query(ROW + "where " + OPEN + " and i.dueDate < :today order by i.dueDate, i.id")
	List<InstallmentRow> overdue(@Param("today") LocalDate today, Pageable pageable);

	@Query("select count(i) as count, coalesce(sum(i.amount), 0) as amount from Installment i where " + OPEN + " and i.dueDate < :today")
	Totals overdueTotals(@Param("today") LocalDate today);

	interface Totals {
		long getCount();

		BigDecimal getAmount();
	}

	// ---- ficha do paciente (ix_inst_patient) ----

	@Query(ROW + "where i.patientId = :patientId order by i.dueDate, i.id")
	List<InstallmentRow> ofPatient(@Param("patientId") UUID patientId, Pageable pageable);

	@Query("select coalesce(sum(i.amount), 0) from Installment i where i.patientId = :patientId and i.status <> crm_data.finance.domain.InstallmentStatus.CANCELED")
	BigDecimal activeAmountOfPatient(@Param("patientId") UUID patientId);

	@Query("select coalesce(sum(i.amount), 0) from Installment i where i.patientId = :patientId and " + OPEN + " and i.dueDate < :today")
	BigDecimal overdueAmountOfPatient(@Param("patientId") UUID patientId, @Param("today") LocalDate today);
}
