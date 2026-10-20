package crm_data.shared.tenant;

import org.springframework.orm.jpa.JpaDialect;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import jakarta.persistence.EntityManagerFactory;

/**
 * Gerenciador de transações que torna o tenant obrigatório: sem {@link TenantContext}, a transação nem começa
 * (401 {@code unauthorized}) e nada chega ao banco. Com tenant, o {@link TenantHibernateJpaDialect} fixa
 * {@code app.clinic_id} e habilita o filtro. Vale para qualquer transação, inclusive as dos repositórios Spring Data.
 */
public class TenantJpaTransactionManager extends JpaTransactionManager {

	private final JpaDialect tenantDialect = new TenantHibernateJpaDialect();

	public TenantJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
		super(entityManagerFactory);
	}

	/** No Spring 7 o dialeto da fábrica prevalece sobre o configurado; por isso o getter é sobrescrito. */
	@Override
	public JpaDialect getJpaDialect() {
		return tenantDialect != null ? tenantDialect : super.getJpaDialect(); // null só durante a construção da superclasse
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		TenantContext.clinicId(); // lança 401 antes de abrir EntityManager ou conexão
		super.doBegin(transaction, definition);
	}
}
