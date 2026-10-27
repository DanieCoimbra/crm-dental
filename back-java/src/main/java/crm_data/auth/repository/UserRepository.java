package crm_data.auth.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import crm_data.auth.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

	/** Página da clínica do tenant (RLS + filtro), por nome e id (desempate estável). */
	Page<User> findAllByOrderByNameAscIdAsc(Pageable pageable);
	/**
	 * Como {@code findById}, mas por JPQL: o filtro Hibernate do tenant se aplica ({@code find} por id não o aplica) e a RLS segue
	 * como barreira final. Id de outra clínica não volta.
	 */
	@Query("select u from User u where u.id = :id")
	Optional<User> findOwnById(@Param("id") UUID id);
}
