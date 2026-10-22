package crm_data.shared.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Fail-fast do perfil {@code prod}: antes de qualquer bean ser criado, confere as variáveis obrigatórias e
 * aborta citando os NOMES ausentes (inclusive as três do Storage: em produção os exames só vão para o Supabase, nunca
 * para o disco local). Nunca imprime valores. Os formatos de {@code CRM_JWT_SECRET} e das chaves
 * AES são validados pelas features F04 e F03; aqui só se exige a presença.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class StartupConfigValidator {

	static final List<String> REQUIRED = List.of("DB_URL", "DB_APP_USER", "DB_APP_PASSWORD", "CRM_JWT_SECRET",
			"CRM_AES_KEYS", "CRM_AES_ACTIVE_VERSION", "CRM_CORS_ORIGINS", "CRM_STORAGE_URL",
			"CRM_STORAGE_SERVICE_KEY", "CRM_STORAGE_BUCKET");

	private static final List<String> STRICT_SSL_MODES = List.of("require", "verify-ca", "verify-full");

	@Bean
	static BeanFactoryPostProcessor startupConfigCheck(Environment environment) {
		return beanFactory -> {
			List<String> problems = problems(environment);
			if (!problems.isEmpty()) {
				throw new IllegalStateException(
						"Configuração inválida para o perfil prod: " + String.join("; ", problems));
			}
		};
	}

	/** Lista os problemas encontrados (vazia se tudo certo). Visível ao pacote para testes. */
	static List<String> problems(Environment env) {
		List<String> problems = new ArrayList<>();

		List<String> missing = REQUIRED.stream().filter(name -> !StringUtils.hasText(env.getProperty(name))).toList();
		if (!missing.isEmpty()) {
			problems.add("variáveis obrigatórias ausentes: " + String.join(", ", missing));
		}

		String dbUrl = env.getProperty("DB_URL");
		if (StringUtils.hasText(dbUrl) && !hasStrictSsl(dbUrl)) {
			problems.add("DB_URL deve exigir TLS (sslmode=require, verify-ca ou verify-full)");
		}

		String origins = env.getProperty("CRM_CORS_ORIGINS");
		if (StringUtils.hasText(origins)) {
			for (String origin : StringUtils.commaDelimitedListToStringArray(origins)) {
				if (!origin.trim().startsWith("https://")) {
					problems.add("CRM_CORS_ORIGINS deve listar apenas origens https:// (sem '*')");
					break;
				}
			}
		}

		String port = env.getProperty("PORT");
		if (StringUtils.hasText(port) && !validPort(port.trim())) {
			problems.add("PORT deve ser um inteiro entre 1 e 65535");
		}
		return problems;
	}

	private static boolean hasStrictSsl(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		return STRICT_SSL_MODES.stream().anyMatch(mode -> lower.contains("sslmode=" + mode));
	}

	private static boolean validPort(String port) {
		try {
			int value = Integer.parseInt(port);
			return value >= 1 && value <= 65535;
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}
}
