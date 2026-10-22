package crm_data.shared.error;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê de uma exceção de banco só o que é seguro expor ou logar: {@code SQLState}, nome da constraint e a data de
 * liberação da retenção ({@code CR002}). A mensagem do driver nunca sai daqui, pois repete valores (por exemplo o
 * CPF de uma violação de unicidade). Usa apenas {@link SQLException}: o driver tem escopo {@code runtime}.
 */
public final class DatabaseErrors {

	private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([A-Za-z0-9_]+)\"");
	private static final Pattern RELEASE_DATE = Pattern.compile("liberado em (\\d{4}-\\d{2}-\\d{2})");

	private DatabaseErrors() {
	}

	public static Optional<String> sqlState(Throwable error) {
		return sqlException(error).map(SQLException::getSQLState).filter(state -> state != null && !state.isBlank());
	}

	/** A cadeia de causas tem uma {@link SQLException}? Então a mensagem e a stack podem repetir valores do banco. */
	public static boolean hasSqlCause(Throwable error) {
		return sqlException(error).isPresent();
	}

	public static Optional<String> constraintName(Throwable error) {
		return find(error, CONSTRAINT);
	}

	public static Optional<LocalDate> retentionReleaseDate(Throwable error) {
		return find(error, RELEASE_DATE).flatMap(text -> {
			try {
				return Optional.of(LocalDate.parse(text));
			}
			catch (DateTimeParseException ex) {
				return Optional.empty();
			}
		});
	}

	private static Optional<SQLException> sqlException(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof SQLException sql) {
				return Optional.of(sql);
			}
		}
		return Optional.empty();
	}

	/** Primeiro grupo do padrão na mensagem de qualquer {@link SQLException} da cadeia (inclusive a próxima). */
	private static Optional<String> find(Throwable error, Pattern pattern) {
		Optional<SQLException> sql = sqlException(error);
		for (SQLException e = sql.orElse(null); e != null; e = e.getNextException()) {
			if (e.getMessage() != null) {
				Matcher matcher = pattern.matcher(e.getMessage());
				if (matcher.find()) {
					return Optional.of(matcher.group(1));
				}
			}
		}
		return Optional.empty();
	}
}
