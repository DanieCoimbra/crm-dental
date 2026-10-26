package crm_data.catalog.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crm_data.catalog.domain.ProcedureType;

public interface ProcedureTypeRepository extends JpaRepository<ProcedureType, UUID> {

	/** Ordem do catálogo: nome sem diferenciar caixa e id (estável). O filtro do tenant acrescenta {@code clinic_id}. */
	@Query("select p from ProcedureType p where p.active = :active order by lower(p.name), p.id")
	List<ProcedureType> findByActiveOrdered(@Param("active") boolean active);

	@Query("select p from ProcedureType p order by lower(p.name), p.id")
	List<ProcedureType> findAllOrdered();

	/** Em lote (resumo e preço): id de outra clínica não volta (filtro + RLS). */
	@Query("select p from ProcedureType p where p.id in :ids")
	List<ProcedureType> findAllByIds(@Param("ids") Collection<UUID> ids);
}
