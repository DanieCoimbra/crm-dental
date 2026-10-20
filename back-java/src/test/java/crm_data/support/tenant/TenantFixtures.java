package crm_data.support.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import crm_data.support.DatabaseContainer;

/** Monta clínicas e usuários direto no banco como administrador (ignora RLS), para os cenários de isolamento. */
public final class TenantFixtures {

	/** Clínica criada com os ids dos seus usuários. */
	public record Clinic(UUID id, List<UUID> userIds) {
	}

	private TenantFixtures() {
	}

	public static Clinic clinicWithUsers(int users) {
		JdbcTemplate admin = DatabaseContainer.admin();
		UUID clinicId = UUID.randomUUID();
		admin.update("INSERT INTO core.clinics (id, name) VALUES (?, ?)", clinicId, "Clínica " + clinicId);
		List<UUID> userIds = new ArrayList<>();
		for (int i = 0; i < users; i++) {
			UUID userId = UUID.randomUUID();
			admin.update("""
					INSERT INTO core.users (id, clinic_id, name, email, password_hash, role)
					VALUES (?, ?, ?, ?, 'hash-de-teste', 'RECEPTIONIST')""",
					userId, clinicId, "Usuário " + i, userId + "@teste.local");
			userIds.add(userId);
		}
		return new Clinic(clinicId, userIds);
	}
}
