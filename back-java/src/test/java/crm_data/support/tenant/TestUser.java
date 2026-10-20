package crm_data.support.tenant;

import java.util.UUID;

import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entidade só de teste sobre {@code core.users} (tabela de negócio real do banco). Além de exercitar o
 * isolamento, faz o {@code ddl-auto=validate} comparar o mapeamento com o schema entregue pela função banco.
 */
@Entity
@Table(name = "users", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestUser extends TenantAwareEntity {

	@Id
	private UUID id;

	@Setter
	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false)
	private String role;

	@Column(nullable = false)
	private boolean active = true;

	public TestUser(UUID id, String name, String email) {
		this.id = id;
		this.name = name;
		this.email = email;
		this.passwordHash = "$2a$12$hash-de-teste-nao-usado-para-login";
		this.role = "RECEPTIONIST";
	}

}
