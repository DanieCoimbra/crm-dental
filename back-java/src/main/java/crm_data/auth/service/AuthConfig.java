package crm_data.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt com custo 12 por padrão (decisão do spec global §7). O custo é configurável por
 * {@code crm.auth.bcrypt-strength}: reduzir para 11 só se o p95 do login passar de 300 ms no ambiente alvo, e
 * registrar a decisão. Testes usam 4 para não gastar ~250 ms por hash.
 */
@Configuration(proxyBeanMethods = false)
public class AuthConfig {

	@Bean
	PasswordEncoder passwordEncoder(@Value("${crm.auth.bcrypt-strength:12}") int strength) {
		if (strength < 4 || strength > 15) {
			throw new IllegalStateException("crm.auth.bcrypt-strength deve estar entre 4 e 15");
		}
		return new BCryptPasswordEncoder(strength);
	}
}
