package crm_data.support;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 16 descartável com o schema REAL da função banco: aplica {@code ../database/bootstrap} e
 * {@code ../database/migrations} com o mesmo script do docker-compose ({@code docker/db-init.sh}). Um único
 * contêiner por JVM, compartilhado por todos os testes de integração. A aplicação conecta como {@code crm_app}.
 */
public final class DatabaseContainer {

	private static final Path DATABASE_DIR = Path.of("..", "database").toAbsolutePath().normalize();
	private static final Path INIT_SCRIPT = Path.of("docker", "db-init.sh").toAbsolutePath().normalize();

	private static final String ADMIN_PASSWORD = randomSecret();
	private static final String OWNER_PASSWORD = randomSecret();
	private static final String APP_PASSWORD = randomSecret();
	private static final String BACKUP_PASSWORD = randomSecret();

	/** Chaves AES de teste (aleatórias por JVM): versões 1 e 2, a 2 ativa. */
	public static final String AES_KEY_V1 = randomKey();
	public static final String AES_KEY_V2 = randomKey();

	/** Segredo JWT de teste (aleatório por JVM, 48 caracteres hex = 48 bytes). */
	public static final String JWT_SECRET = randomSecret() + randomSecret().substring(0, 8);

	// vive até o fim da JVM; o Ryuk do Testcontainers o remove
	@SuppressWarnings("resource")
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16")
			.withDatabaseName("postgres")
			.withUsername("postgres")
			.withPassword(ADMIN_PASSWORD)
			.withCopyFileToContainer(MountableFile.forHostPath(DATABASE_DIR.resolve("bootstrap")),
					"/database/bootstrap")
			.withCopyFileToContainer(MountableFile.forHostPath(DATABASE_DIR.resolve("migrations")),
					"/database/migrations")
			.withCopyFileToContainer(MountableFile.forHostPath(DATABASE_DIR.resolve("scripts")),
					"/database/scripts")
			.withCopyFileToContainer(MountableFile.forHostPath(INIT_SCRIPT), "/db-init.sh");

	static {
		POSTGRES.start();
		applyDatabaseScripts();
		createTestOnlyTables();
	}

	private DatabaseContainer() {
	}

	/** Aponta o datasource da aplicação para o contêiner, com o papel {@code crm_app}. */
	public static void register(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", () -> "crm_app");
		registry.add("spring.datasource.password", () -> APP_PASSWORD);
		registry.add("CRM_AES_KEYS", () -> "1:" + AES_KEY_V1 + ",2:" + AES_KEY_V2);
		registry.add("CRM_AES_ACTIVE_VERSION", () -> "2");
		registry.add("CRM_JWT_SECRET", () -> JWT_SECRET);
		registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
				RecordingStatementInspector.class::getName);
	}

	/** Acesso administrativo (superusuário, ignora RLS): só para montar e conferir cenários nos testes. */
	public static JdbcTemplate admin() {
		return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", ADMIN_PASSWORD));
	}

	private static void applyDatabaseScripts() {
		String command = "PGHOST=localhost POSTGRES_PASSWORD=" + ADMIN_PASSWORD + " DB_OWNER_PASSWORD=" + OWNER_PASSWORD
				+ " DB_APP_PASSWORD=" + APP_PASSWORD + " DB_BACKUP_PASSWORD=" + BACKUP_PASSWORD
				+ " bash /db-init.sh";
		try {
			ExecResult result = POSTGRES.execInContainer("bash", "-c", command);
			if (result.getExitCode() != 0) {
				throw new IllegalStateException("Falha ao aplicar os scripts do banco (exit " + result.getExitCode()
						+ "): " + result.getStderr() + result.getStdout());
			}
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("Falha ao executar db-init.sh no contêiner", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrompido ao aplicar os scripts do banco", ex);
		}
	}

	/** Tabela só de teste (campo cifrado), fora das migrations reais; o app a usa como qualquer tabela de negócio. */
	private static void createTestOnlyTables() {
		JdbcTemplate admin = admin();
		admin.execute("""
				CREATE TABLE core.crypto_probe (
				  id        uuid PRIMARY KEY,
				  clinic_id uuid NOT NULL,
				  secret    text
				)""");
		admin.execute("GRANT SELECT, INSERT, UPDATE ON core.crypto_probe TO crm_app");
	}

	private static String randomKey() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return java.util.Base64.getEncoder().encodeToString(bytes);
	}

	private static String randomSecret() {
		byte[] bytes = new byte[20];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes); // 40 caracteres hex, seguro para shell
	}
}
