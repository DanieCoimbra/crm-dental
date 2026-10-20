package crm_data.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/** Registra o SQL que o Hibernate envia ao banco, para provar o que foi (ou não) executado. */
public class RecordingStatementInspector implements StatementInspector {

	private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

	@Override
	public String inspect(String sql) {
		STATEMENTS.add(sql);
		return sql;
	}

	public static void reset() {
		STATEMENTS.clear();
	}

	public static List<String> statements() {
		return List.copyOf(STATEMENTS);
	}
}
