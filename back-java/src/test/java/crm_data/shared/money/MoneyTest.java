package crm_data.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

	@ParameterizedTest
	@CsvSource({ "0,0.00", "10,10.00", "10.5,10.50", "10.05,10.05", "999999.99,999999.99", "0.01,0.01", "000001,1.00" })
	void parsesTheAcceptedFormatsWithScaleTwo(String text, String expected) {
		BigDecimal value = Money.parse(text);

		assertThat(value).isEqualByComparingTo(expected);
		assertThat(value.scale()).isEqualTo(2);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "1e3", "1E3", "-1", "+1", "1,5", "1.234", " 1", "1 ", "1.", ".5", "abc", "1000000.00",
			"1000000", "9999999", "NaN", "0x10", "١٢٣" })
	void rejectsAnythingOutsideTheClosedFormat(String text) {
		assertThatThrownBy(() -> Money.parse(text)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void parsePositiveRequiresAtLeastOneCent() {
		assertThat(Money.parsePositive("0.01")).isEqualByComparingTo("0.01");
		assertThatThrownBy(() -> Money.parsePositive("0")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Money.parsePositive("0.00")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void formatIsAPlainStringWithTwoDecimals() {
		assertThat(Money.format(new BigDecimal("1234.5"))).isEqualTo("1234.50");
		assertThat(Money.format(new BigDecimal("0"))).isEqualTo("0.00");
		assertThat(Money.format(new BigDecimal("1E+3"))).isEqualTo("1000.00");
		assertThat(Money.format(Money.CAP)).isEqualTo("999999.99");
	}

	@Test
	void splitGivesTheRemainderToTheLastPart() {
		List<BigDecimal> parts = Money.splitDown(new BigDecimal("100.00"), 3);

		assertThat(parts).containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
	}

	@Test
	void splitInOneIsTheTotalAndExactDivisionIsEven() {
		assertThat(Money.splitDown(new BigDecimal("800.00"), 1)).containsExactly(new BigDecimal("800.00"));
		assertThat(Money.splitDown(new BigDecimal("800.00"), 4)).containsOnly(new BigDecimal("200.00"));
	}

	@Test
	void splitNeverRoundsUpTheRegularParts() {
		List<BigDecimal> parts = Money.splitDown(new BigDecimal("0.10"), 3);

		assertThat(parts).containsExactly(new BigDecimal("0.03"), new BigDecimal("0.03"), new BigDecimal("0.04"));
	}

	@Test
	void splitRejectsInvalidArguments() {
		assertThatThrownBy(() -> Money.splitDown(BigDecimal.TEN, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Money.splitDown(new BigDecimal("-1.00"), 2)).isInstanceOf(IllegalArgumentException.class);
	}

	/** Propriedade: a soma das partes é sempre o total, para totais e contagens aleatórios (semente fixa). */
	@Test
	void splitAlwaysSumsToTheTotal() {
		Random random = new Random(20261026L);
		for (int i = 0; i < 5_000; i++) {
			BigDecimal total = BigDecimal.valueOf(random.nextLong(0, 99_999_999L), 2);
			int n = random.nextInt(1, 61);

			List<BigDecimal> parts = Money.splitDown(total, n);

			assertThat(parts).hasSize(n);
			assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
			assertThat(parts.stream().limit(n - 1L)).allMatch(part -> part.equals(parts.get(0)));
			assertThat(parts.get(n - 1)).isGreaterThanOrEqualTo(parts.get(0));
			assertThat(parts).allSatisfy(part -> assertThat(part.scale()).isEqualTo(2));
		}
	}

	@Test
	void theCeilingMatchesTheDatabaseCheck() {
		assertThat(Money.CAP).isEqualByComparingTo("999999.99");
		assertThatCode(() -> Money.parse("999999.99")).doesNotThrowAnyException();
	}
}
