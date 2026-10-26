package crm_data.commercial.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crm_data.commercial.domain.BudgetItem;

public interface BudgetItemRepository extends JpaRepository<BudgetItem, UUID> {

	/** Itens do orçamento em ordem de posição ({@code ux_items_budget_position}). */
	List<BudgetItem> findByBudgetIdOrderByPositionAsc(UUID budgetId);

	/** Itens de vários orçamentos de uma vez (sem N+1). */
	@Query("select i from BudgetItem i where i.budgetId in :ids order by i.budgetId, i.position")
	List<BudgetItem> findByBudgetIds(@Param("ids") Collection<UUID> ids);
}
