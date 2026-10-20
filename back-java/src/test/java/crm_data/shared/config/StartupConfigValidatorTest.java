package crm_data.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class StartupConfigValidatorTest {

	private static MockEnvironment validEnvironment() {
		return new MockEnvironment()
				.withProperty("DB_URL", "jdbc:postgresql://db.example:6543/postgres?sslmode=require&prepareThreshold=0")
				.withProperty("DB_APP_USER", "crm_app.proj")
				.withProperty("DB_APP_PASSWORD", "senha-do-banco-super-secreta")
				.withProperty("CRM_JWT_SECRET", "segredo-jwt-super-secreto-com-32-bytes!")
				.withProperty("CRM_AES_KEYS", "1:chave-aes-super-secreta")
				.withProperty("CRM_AES_ACTIVE_VERSION", "1")
				.withProperty("CRM_CORS_ORIGINS", "https://app.example,https://www.example");
	}

	@Test
	void acceptsCompleteConfiguration() {
		assertThat(StartupConfigValidator.problems(validEnvironment())).isEmpty();
	}

	@Test
	void listsEveryMissingVariableByName() {
		MockEnvironment env = new MockEnvironment()
				.withProperty("DB_URL", "jdbc:postgresql://db.example/postgres?sslmode=require");

		List<String> problems = StartupConfigValidator.problems(env);

		assertThat(problems).hasSize(1);
		assertThat(problems.get(0)).contains("DB_APP_USER", "DB_APP_PASSWORD", "CRM_JWT_SECRET", "CRM_AES_KEYS",
				"CRM_AES_ACTIVE_VERSION", "CRM_CORS_ORIGINS").doesNotContain("DB_URL");
	}

	@Test
	void treatsBlankValueAsMissing() {
		MockEnvironment env = validEnvironment().withProperty("CRM_JWT_SECRET", "   ");
		assertThat(StartupConfigValidator.problems(env)).singleElement().asString().contains("CRM_JWT_SECRET");
	}

	@Test
	void requiresTlsInDatabaseUrl() {
		MockEnvironment env = validEnvironment().withProperty("DB_URL", "jdbc:postgresql://db.example/postgres");
		assertThat(StartupConfigValidator.problems(env)).singleElement().asString().contains("DB_URL", "TLS");

		env.withProperty("DB_URL", "jdbc:postgresql://db.example/postgres?sslmode=disable");
		assertThat(StartupConfigValidator.problems(env)).hasSize(1);

		env.withProperty("DB_URL", "jdbc:postgresql://db.example/postgres?sslmode=verify-full");
		assertThat(StartupConfigValidator.problems(env)).isEmpty();
	}

	@Test
	void rejectsWildcardAndNonHttpsCorsOrigins() {
		for (String origins : List.of("*", "http://app.example", "https://ok.example,http://nao.example")) {
			MockEnvironment env = validEnvironment().withProperty("CRM_CORS_ORIGINS", origins);
			assertThat(StartupConfigValidator.problems(env)).as(origins).singleElement().asString()
					.contains("CRM_CORS_ORIGINS");
		}
	}

	@Test
	void rejectsInvalidPort() {
		for (String port : List.of("0", "65536", "abc", "-1")) {
			MockEnvironment env = validEnvironment().withProperty("PORT", port);
			assertThat(StartupConfigValidator.problems(env)).as(port).singleElement().asString().contains("PORT");
		}
		assertThat(StartupConfigValidator.problems(validEnvironment().withProperty("PORT", "10000"))).isEmpty();
	}

	@Test
	void problemMessagesNeverContainValues() {
		MockEnvironment env = validEnvironment().withProperty("DB_URL", "jdbc:postgresql://db.example/postgres")
				.withProperty("CRM_CORS_ORIGINS", "http://segredo.example");
		String all = String.join(" ", StartupConfigValidator.problems(env));
		assertThat(all).doesNotContain("db.example", "segredo.example", "senha-do-banco", "segredo-jwt");
	}

	@Test
	void prodProfileAbortsStartupNamingTheMissingVariable() {
		new ApplicationContextRunner()
				.withUserConfiguration(StartupConfigValidator.class)
				.withPropertyValues("spring.profiles.active=prod", "DB_URL=jdbc:postgresql://db/postgres?sslmode=require",
						"DB_APP_USER=u", "DB_APP_PASSWORD=p", "CRM_AES_KEYS=k", "CRM_AES_ACTIVE_VERSION=1",
						"CRM_CORS_ORIGINS=https://app.example")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(rootMessages(context.getStartupFailure())).contains("CRM_JWT_SECRET");
				});
	}

	@Test
	void nonProdProfileDoesNotValidate() {
		new ApplicationContextRunner()
				.withUserConfiguration(StartupConfigValidator.class)
				.withPropertyValues("spring.profiles.active=dev")
				.run(context -> assertThat(context).hasNotFailed());
	}

	private static String rootMessages(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable t = failure; t != null; t = t.getCause()) {
			messages.append(t.getMessage()).append('\n');
		}
		return messages.toString();
	}
}
