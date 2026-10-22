package crm_data.support;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registra todo SQL preparado via JDBC, inclusive o que o Hibernate não enxerga (o {@code set_config} da sessão sai
 * por {@code doWork}). Só para testes que contam instruções.
 */
@TestConfiguration
public class SqlRecordingDataSourceConfig {

	private static final List<String> PREPARED = new CopyOnWriteArrayList<>();

	public static void reset() {
		PREPARED.clear();
	}

	public static List<String> prepared() {
		return List.copyOf(PREPARED);
	}

	@Bean
	static BeanPostProcessor sqlRecordingPostProcessor() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (!(bean instanceof DataSource dataSource)) {
					return bean;
				}
				return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { DataSource.class },
						(proxy, method, args) -> {
							Object result = invoke(dataSource, method, args);
							return result instanceof Connection connection ? recording(connection) : result;
						});
			}
		};
	}

	private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		}
		catch (java.lang.reflect.InvocationTargetException ex) {
			throw ex.getCause();
		}
	}

	private static Connection recording(Connection connection) {
		return (Connection) Proxy.newProxyInstance(SqlRecordingDataSourceConfig.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
						PREPARED.add(sql);
					}
					return invoke(connection, method, args);
				});
	}
}
