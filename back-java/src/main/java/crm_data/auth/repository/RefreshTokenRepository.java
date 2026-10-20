package crm_data.auth.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crm_data.auth.domain.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	/**
	 * Marca o token como usado de forma atômica: só um chamador concorrente consegue (retorna 1); os demais
	 * recebem 0 e tratam o caso como reuso.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RefreshToken t set t.usedAt = :now where t.id = :id and t.usedAt is null and t.revokedAt is null")
	int markUsed(@Param("id") UUID id, @Param("now") Instant now);

	/** Revoga todos os tokens ainda ativos da família (cadeia de rotação). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
	int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

	/** Revoga todos os tokens ativos do usuário (troca de senha, desativação, reset). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
	int revokeAllOfUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
