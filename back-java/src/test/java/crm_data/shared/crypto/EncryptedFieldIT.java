package crm_data.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crm_data.shared.crypto.CryptoException.Reason;
import crm_data.shared.tenant.TenantContext;
import crm_data.shared.tenant.TenantRunner;
import crm_data.support.DatabaseContainer;
import crm_data.support.tenant.CryptoProbe;
import crm_data.support.tenant.TenantFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** O conversor JPA com PostgreSQL real: o que vai para o banco é cifrado, e {@code null} continua {@code null}. */
@SpringBootTest
@ActiveProfiles("test")
class EncryptedFieldIT {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		DatabaseContainer.register(registry);
	}

	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	AesGcmCipher cipher;
	@Autowired
	KeyRing keyRing;
	@PersistenceContext
	EntityManager entityManager;

	final JdbcTemplate admin = DatabaseContainer.admin();
	TransactionTemplate tx;
	UUID clinicId;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		clinicId = TenantFixtures.clinicWithUsers(0).id();
	}

	@AfterEach
	void cleanUp() {
		TenantContext.clear();
	}

	private <T> T inTx(Supplier<T> block) {
		return TenantRunner.call(clinicId, () -> tx.execute(status -> block.get()));
	}

	private String rawColumn(UUID id) {
		return admin.queryForObject("SELECT secret FROM core.crypto_probe WHERE id = ?", String.class, id);
	}

	private UUID save(String secret) {
		UUID id = UUID.randomUUID();
		inTx(() -> {
			entityManager.persist(new CryptoProbe(id, secret));
			return null;
		});
		return id;
	}

	private String load(UUID id) {
		return inTx(() -> {
			entityManager.clear();
			return entityManager.find(CryptoProbe.class, id).getSecret();
		});
	}

	@Test
	void keyRingComesFromTheEnvironmentWithTheActiveVersion() {
		assertThat(keyRing.activeVersion()).isEqualTo(2);
	}

	@Test
	void databaseStoresCiphertextAndJpaReturnsPlaintext() {
		String plain = "Paciente relata dor no 36; alergia a penicilina 😀";
		UUID id = save(plain);

		String raw = rawColumn(id);
		assertThat(raw).isNotEqualTo(plain).doesNotContain("penicilina");
		assertThat(Base64.getDecoder().decode(raw)[0]).isEqualTo((byte) 2);
		assertThat(load(id)).isEqualTo(plain);
	}

	@Test
	void nullIsStoredAsSqlNullAndReadBackAsNull() {
		UUID id = save(null);

		assertThat(rawColumn(id)).isNull();
		assertThat(load(id)).isNull();
	}

	@Test
	void emptyTextIsEncryptedAndReadBackEmpty() {
		UUID id = save("");

		assertThat(rawColumn(id)).isNotEmpty();
		assertThat(load(id)).isEmpty();
	}

	@Test
	void sameTextInTwoRowsIsStoredDifferently() {
		UUID a = save("igual");
		UUID b = save("igual");

		assertThat(rawColumn(a)).isNotEqualTo(rawColumn(b));
	}

	@Test
	void valueEncryptedWithOlderKeyVersionIsStillReadable() {
		UUID id = UUID.randomUUID();
		String v1Only = new AesGcmCipher(KeyRing.parse("1:" + DatabaseContainer.AES_KEY_V1, "1")).encrypt("antigo");
		admin.update("INSERT INTO core.crypto_probe (id, clinic_id, secret) VALUES (?, ?, ?)", id, clinicId, v1Only);

		assertThat(load(id)).isEqualTo("antigo");
		assertThat(Base64.getDecoder().decode(cipher.rotate(rawColumn(id)))[0]).isEqualTo((byte) 2);
	}

	@Test
	void tamperedValueInTheDatabaseFailsInsteadOfReturningWrongText() {
		UUID id = save("dado íntegro");
		byte[] bytes = Base64.getDecoder().decode(rawColumn(id));
		bytes[bytes.length - 1] ^= 1;
		admin.update("UPDATE core.crypto_probe SET secret = ? WHERE id = ?", Base64.getEncoder().encodeToString(bytes), id);

		assertThatThrownBy(() -> load(id))
				.hasRootCauseInstanceOf(CryptoException.class)
				.rootCause().isInstanceOfSatisfying(CryptoException.class, ex -> assertThat(ex.reason()).isEqualTo(Reason.TAMPERED));
	}

	@Test
	void plaintextNeverReachesTheSqlSentToTheDatabaseAsAParameterlessLiteral() {
		String plain = "texto-que-nao-pode-vazar-" + UUID.randomUUID();
		UUID id = save(plain);

		Long rows = admin.queryForObject("SELECT count(*) FROM core.crypto_probe WHERE secret LIKE ?", Long.class, "%" + plain + "%");
		assertThat(rows).isZero();
		assertThat(rawColumn(id)).doesNotContain(plain);
	}
}
