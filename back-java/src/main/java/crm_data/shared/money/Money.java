package crm_data.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Valores monetários da API (BRL, escala 2). Na API o dinheiro é sempre texto decimal ({@code "1234.50"}), nunca número
 * JSON, para o React não arredondar com ponto flutuante. Sem Spring: regra pura.
 *
 * <p>Entrada aceita só {@code ^\d{1,6}(\.\d{1,2})?$}: nada de notação científica, sinal, vírgula ou espaço. O teto é o
 * mesmo do banco ({@code 999999.99} por campo).
 */
public final class Money {

	/** Teto por campo (igual às {@code CHECK} do banco). */
	public static final BigDecimal CAP = new BigDecimal("999999.99");
	public static final BigDecimal CENT = new BigDecimal("0.01");
	public static final int SCALE = 2;

	/**
	 * Formato aceito na entrada, também para {@code @Pattern} dos DTOs (constante de compilação). Seis dígitos inteiros
	 * garantem o teto de 999999.99 no próprio formato.
	 */
	public static final String INPUT_PATTERN = "^\\d{1,6}(\\.\\d{1,2})?$";

	private static final Pattern FORMAT = Pattern.compile("\\d{1,6}(\\.\\d{1,2})?");

	private Money() {
	}

	/**
	 * @return o valor com escala 2, de 0 a {@link #CAP}
	 * @throws IllegalArgumentException se o texto não estiver no formato ou passar do teto
	 */
	public static BigDecimal parse(String text) {
		if (text == null || !FORMAT.matcher(text).matches()) {
			throw new IllegalArgumentException("valor monetário inválido");
		}
		BigDecimal value = new BigDecimal(text).setScale(SCALE);
		if (value.compareTo(CAP) > 0) {
			throw new IllegalArgumentException("valor acima do limite");
		}
		return value;
	}

	/** Como {@link #parse}, mas exige pelo menos R$ 0,01. */
	public static BigDecimal parsePositive(String text) {
		BigDecimal value = parse(text);
		if (value.compareTo(CENT) < 0) {
			throw new IllegalArgumentException("valor deve ser maior que zero");
		}
		return value;
	}

	/** Texto decimal da API: {@code toPlainString} com escala 2 (arredondamento só de segurança; a escala já é 2). */
	public static String format(BigDecimal value) {
		return value.setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString();
	}

	/**
	 * Divide o total em {@code n} partes de centavos inteiros: as {@code n − 1} primeiras iguais (arredondadas para
	 * baixo) e a última com a diferença. A soma é sempre o total.
	 *
	 * @throws IllegalArgumentException se {@code n < 1} ou o total for negativo
	 */
	public static List<BigDecimal> splitDown(BigDecimal total, int n) {
		if (n < 1 || total.signum() < 0) {
			throw new IllegalArgumentException("divisão inválida");
		}
		BigDecimal exact = total.setScale(SCALE, RoundingMode.UNNECESSARY);
		BigDecimal base = exact.divide(BigDecimal.valueOf(n), SCALE, RoundingMode.DOWN);
		List<BigDecimal> parts = new ArrayList<>(n);
		for (int i = 1; i < n; i++) {
			parts.add(base);
		}
		parts.add(exact.subtract(base.multiply(BigDecimal.valueOf(n - 1L))));
		return List.copyOf(parts);
	}
}
