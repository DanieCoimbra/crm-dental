package crm_data.support.tenant;

import java.util.UUID;

import crm_data.shared.crypto.EncryptedStringConverter;
import crm_data.shared.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Entidade só de teste com um campo cifrado (tabela {@code core.crypto_probe}, criada pelo DatabaseContainer). */
@Entity
@Table(name = "crypto_probe", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CryptoProbe extends TenantAwareEntity {

	@Id
	private UUID id;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(columnDefinition = "text")
	private String secret;

	public CryptoProbe(UUID id, String secret) {
		this.id = id;
		this.secret = secret;
	}
}
