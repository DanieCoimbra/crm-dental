package crm_data.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Clínica (tenant). Não estende {@code TenantAwareEntity}: não tem {@code clinic_id}, o próprio {@code id} é o
 * tenant (a RLS compara {@code id = core.current_clinic_id()}). O {@code id} é gerado pela aplicação e igual ao
 * tenant da transação de registro.
 */
@Entity
@Table(name = "clinics", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Clinic {

	@Id
	private UUID id;

	@Setter
	@Column(nullable = false, length = 150)
	private String name;

	@Setter
	@Column(length = 20)
	private String document;

	@Setter
	@Column(length = 20)
	private String phone;

	@Setter
	@Column(length = 254)
	private String email;

	@Setter
	@Column(length = 255)
	private String address;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	public Clinic(UUID id, String name) {
		this.id = id;
		this.name = name;
	}
}
