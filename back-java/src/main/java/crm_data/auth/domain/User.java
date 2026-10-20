package crm_data.auth.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;

import crm_data.shared.security.Role;
import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Usuário da clínica. O {@code clinic_id} vem do tenant (herdado de {@link TenantAwareEntity}), nunca de DTO. */
@Entity
@Table(name = "users", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends TenantAwareEntity {

	private static final int BCRYPT_MAX_BYTES = 72;

	@Id
	private UUID id;

	@Setter
	@Column(nullable = false, length = 120)
	private String name;

	/** Sempre em minúsculas (constraint {@code ck_users_email_lower} e índice único global). */
	@Setter
	@Column(nullable = false, length = 254)
	private String email;

	/** Sem getter: o hash só é lido pelo banco/BCrypt, nunca exposto pela entidade. */
	@Getter(AccessLevel.NONE)
	@Setter
	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role;

	@Setter
	@Column(nullable = false)
	private boolean active = true;

	@Setter
	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	/**
	 * Confere a senha sem expor o hash. Senha acima do limite do BCrypt (72 bytes) simplesmente não confere.
	 */
	public boolean passwordMatches(PasswordEncoder encoder, String rawPassword) {
		if (rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			return false;
		}
		return encoder.matches(rawPassword, passwordHash);
	}

	public User(UUID id, String name, String email, String passwordHash, Role role) {
		this.id = id;
		this.name = name;
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = role;
	}
}
