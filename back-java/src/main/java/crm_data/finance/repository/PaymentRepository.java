package crm_data.finance.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import crm_data.finance.domain.Payment;
import crm_data.finance.domain.PaymentMethod;

/**
 * Só leitura e inserção: estende {@link Repository} (e não {@code JpaRepository}) de propósito, para que
 * {@code delete*} e afins nem existam. O banco também nega {@code UPDATE} e {@code DELETE}.
 */
public interface PaymentRepository extends Repository<Payment, UUID> {

	String NET = "sum(case when p.kind = crm_data.finance.domain.PaymentKind.PAYMENT then p.amount else -p.amount end)";

	/** Insere (a entidade é {@code Persistable} e nova: {@code persist}, sem {@code SELECT}). */
	<S extends Payment> S save(S payment);

	Optional<Payment> findById(UUID id);

	List<Payment> findByInstallmentIdOrderByCreatedAtAscIdAsc(UUID installmentId);

	boolean existsByReversesPaymentId(UUID paymentId);

	/** Líquido pago (pagamentos − estornos) por parcela, em uma consulta. Parcela sem pagamento não aparece. */
	@Query("select p.installmentId as installmentId, " + NET + " as net from Payment p where p.installmentId in :ids group by p.installmentId")
	List<NetPaid> netPaidByInstallmentIds(@Param("ids") Collection<UUID> ids);

	/** Entradas líquidas por data de recebimento (regime de caixa). */
	@Query("select p.paidOn as key, " + NET + " as net from Payment p where p.paidOn >= :from and p.paidOn <= :to group by p.paidOn order by p.paidOn")
	List<DayNet> netByDay(@Param("from") LocalDate from, @Param("to") LocalDate to);

	@Query("select p.method as key, " + NET + " as net from Payment p where p.paidOn >= :from and p.paidOn <= :to group by p.method order by p.method")
	List<MethodNet> netByMethod(@Param("from") LocalDate from, @Param("to") LocalDate to);

	/** Líquido pago nas parcelas abertas com vencimento no intervalo (a parcela é conferida por {@code exists}). */
	@Query("select coalesce(" + NET + ", 0) from Payment p where exists (select 1 from Installment i where i.id = p.installmentId"
			+ " and i.status = crm_data.finance.domain.InstallmentStatus.OPEN and i.dueDate >= :from and i.dueDate <= :to)")
	BigDecimal netPaidOnOpenDueBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

	/** Líquido pago nas parcelas abertas vencidas antes de {@code today}. */
	@Query("select coalesce(" + NET + ", 0) from Payment p where exists (select 1 from Installment i where i.id = p.installmentId"
			+ " and i.status = crm_data.finance.domain.InstallmentStatus.OPEN and i.dueDate < :today)")
	BigDecimal netPaidOnOverdue(@Param("today") LocalDate today);

	/** Líquido pago de todas as parcelas do paciente. */
	@Query("select coalesce(" + NET + ", 0) from Payment p where exists (select 1 from Installment i where i.id = p.installmentId"
			+ " and i.patientId = :patientId)")
	BigDecimal netPaidOfPatient(@Param("patientId") UUID patientId);

	/** Líquido pago das parcelas abertas do paciente vencidas antes de {@code today}. */
	@Query("select coalesce(" + NET + ", 0) from Payment p where exists (select 1 from Installment i where i.id = p.installmentId"
			+ " and i.patientId = :patientId and i.status = crm_data.finance.domain.InstallmentStatus.OPEN and i.dueDate < :today)")
	BigDecimal netPaidOfPatientOverdue(@Param("patientId") UUID patientId, @Param("today") LocalDate today);

	interface NetPaid {
		UUID getInstallmentId();

		BigDecimal getNet();
	}

	interface DayNet {
		LocalDate getKey();

		BigDecimal getNet();
	}

	interface MethodNet {
		PaymentMethod getKey();

		BigDecimal getNet();
	}
	/**
	 * Como {@code findById}, mas por JPQL: o filtro Hibernate do tenant se aplica ({@code find} por id não o aplica) e a RLS segue
	 * como barreira final. Id de outra clínica não volta.
	 */
	@Query("select p from Payment p where p.id = :id")
	Optional<Payment> findOwnById(@Param("id") UUID id);
}
