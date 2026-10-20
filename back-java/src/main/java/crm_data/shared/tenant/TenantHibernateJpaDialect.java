package crm_data.shared.tenant;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import org.hibernate.Session;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * Logo depois de abrir cada transação JPA, na mesma conexão: (1) fixa {@code app.clinic_id} no banco com
 * {@code set_config(..., true)} (local à transação, expira no commit/rollback e não vaza pelo pool) e (2) habilita o
 * filtro Hibernate do tenant na sessão.
 */
public class TenantHibernateJpaDialect extends HibernateJpaDialect {

	private static final String SET_TENANT = "SELECT set_config('app.clinic_id', ?, true)";

	@Override
	public Object beginTransaction(EntityManager entityManager, TransactionDefinition definition)
			throws PersistenceException, SQLException, TransactionException {
		Object transactionData = super.beginTransaction(entityManager, definition);

		UUID clinicId = TenantIdProvider.currentClinicId();
		Session session = entityManager.unwrap(Session.class);
		session.doWork(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
				statement.setString(1, clinicId.toString());
				statement.execute();
			}
		});
		session.enableFilter(TenantAwareEntity.FILTER).setParameter(TenantAwareEntity.PARAM, clinicId);
		return transactionData;
	}
}
