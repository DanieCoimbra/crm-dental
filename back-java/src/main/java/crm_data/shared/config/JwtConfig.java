package crm_data.shared.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * JWT HS256 com o segredo de {@code CRM_JWT_SECRET} (mínimo de 32 bytes). Segredo ausente ou curto impede o
 * startup, sem imprimir o valor. O decoder aceita somente HS256 (qualquer outro algoritmo, inclusive {@code none},
 * é rejeitado), exige {@code iss=crm-clinica} e não tolera folga de relógio na expiração.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

	public static final String ISSUER = "crm-clinica";
	static final int MIN_SECRET_BYTES = 32;

	@Bean
	public SecretKey jwtSecretKey(@Value("${crm.jwt.secret:}") String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (secret.isBlank()) {
			throw new IllegalStateException("CRM_JWT_SECRET ausente");
		}
		if (bytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("CRM_JWT_SECRET deve ter pelo menos " + MIN_SECRET_BYTES + " bytes");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	@Bean
	public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
				new JwtIssuerValidator(ISSUER)));
		return decoder;
	}
}
