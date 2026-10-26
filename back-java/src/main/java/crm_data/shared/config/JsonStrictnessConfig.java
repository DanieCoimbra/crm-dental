package crm_data.shared.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Um campo de texto da API não aceita número nem booleano do JSON. Por padrão o Jackson 3 coage {@code 1234.5} para a
 * {@code String "1234.5"}, o que deixaria um cliente enviar dinheiro como número de ponto flutuante (o contrato exige
 * texto decimal: {@code "1234.50"}) e passar pela validação de formato. Com a coerção em {@code Fail}, o corpo falha na
 * leitura e a resposta é o 400 {@code validation} de sempre. Vale para todos os DTOs; os campos numéricos de verdade
 * ({@code quantity}, {@code count}, {@code version}) continuam sendo números.
 */
@Configuration(proxyBeanMethods = false)
public class JsonStrictnessConfig {

	/**
	 * Também não deixa um número decimal virar inteiro: por padrão {@code 30.5} em um campo inteiro é truncado em
	 * silêncio para {@code 30} (perda de dado; vale para duração, quantidade, versão...). Texto numérico em campo inteiro
	 * ({@code "30"}) segue aceito.
	 */
	@Bean
	JsonMapperBuilderCustomizer strictTextualCoercion() {
		return builder -> builder
				.withCoercionConfig(LogicalType.Textual, config -> config
						.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
				.withCoercionConfig(LogicalType.Integer,
						config -> config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
	}
}
