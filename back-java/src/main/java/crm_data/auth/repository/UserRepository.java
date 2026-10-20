package crm_data.auth.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import crm_data.auth.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

	/** Página da clínica do tenant (RLS + filtro), por nome e id (desempate estável). */
	Page<User> findAllByOrderByNameAscIdAsc(Pageable pageable);
}
