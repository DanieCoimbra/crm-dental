package crm_data.commercial.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crm_data.commercial.domain.BudgetDocument;

public interface BudgetDocumentRepository extends JpaRepository<BudgetDocument, UUID> {

	/** Revisões ativas (não removidas), a maior primeiro ({@code ix_bdoc_budget_active}). */
	@Query("select d from BudgetDocument d where d.budgetId = :budgetId and d.deletedAt is null order by d.revision desc")
	List<BudgetDocument> findActiveByBudgetId(@Param("budgetId") UUID budgetId);

	/** Documento ativo de um orçamento; removido, de outro orçamento ou de outra clínica não volta. */
	Optional<BudgetDocument> findByIdAndBudgetIdAndDeletedAtIsNull(UUID id, UUID budgetId);

	/** A linha existe para esta chave? (conferência da compensação quando o resultado do commit é incerto). */
	boolean existsByStorageKey(String storageKey);

	/** Revisão e instante atribuídos pelo banco a uma linha recém-gravada (valores escalares: sempre vêm do banco). */
	interface IssuedRow {

		Short getRevision();

		Instant getGeneratedAt();
	}

	/**
	 * Lê a revisão e o {@code generated_at} que o banco atribuiu. Não usa {@code refresh}: a entidade é {@code @Immutable}
	 * e o Hibernate recusa o lock do refresh numa entidade somente leitura ({@code UnsupportedLockAttemptException}).
	 */
	@Query("select d.revision as revision, d.generatedAt as generatedAt from BudgetDocument d where d.id = :id")
	Optional<IssuedRow> findIssuedById(@Param("id") UUID id);

	/** Maior revisão já usada, <em>inclusive</em> das removidas (o número não volta); 0 se não há documento. */
	/** Todas as chaves de objeto dos PDFs do paciente, inclusive de documentos já marcados como removidos. */
	@Query("select d.storageKey from BudgetDocument d where d.patientId = :patientId")
	List<String> findStorageKeysByPatientId(@Param("patientId") UUID patientId);

	@Query("select coalesce(max(cast(d.revision as integer)), 0) from BudgetDocument d where d.budgetId = :budgetId")
	int maxRevision(@Param("budgetId") UUID budgetId);
}
