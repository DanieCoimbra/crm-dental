package crm_data.shared.tenant;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import org.hibernate.Session;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

import crm_data.shared.security.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * Logo depois de abrir cada transação JPA, na mesma conexão: (1) fixa {@code app.clinic_id}, {@code app.role} e
 * {@code app.user_id} no banco com {@code set_config(..., true)}, numa única instrução (local à transação, expira
 * no commit/rollback e não vaza pelo pool) e (2) habilita o filtro Hibernate do tenant na sessão. É o único ponto
 * que escreve essas variáveis. Papel e usuário ausentes viram texto vazio, que o banco lê como {@code NULL}.
 */
public class TenantHibernateJpaDialect extends HibernateJpaDialect {

	private static final String SET_SESSION = "SELECT set_config('app.clinic_id', ?, true), "
			+ "set_config('app.role', ?, true), set_config('app.user_id', ?, true)";

	@Override
	public Object beginTransaction(EntityManager entityManager, TransactionDefinition definition)
			throws PersistenceException, SQLException, TransactionException {
		Object transactionData = super.beginTransaction(entityManager, definition);

		UUID clinicId = TenantIdProvider.currentClinicId();
		Role role = TenantIdProvider.currentRoleOrNull();
		UUID userId = TenantIdProvider.currentUserIdOrNull();
		Session session = entityManager.unwrap(Session.class);
		session.doWork(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(SET_SESSION)) {
				statement.setString(1, clinicId.toString());
				statement.setString(2, role != null ? role.name() : "");
				statement.setString(3, userId != null ? userId.toString() : "");
				statement.execute();
			}
		});
		session.enableFilter(TenantAwareEntity.FILTER).setParameter(TenantAwareEntity.PARAM, clinicId);
		return transactionData;
	}
}
