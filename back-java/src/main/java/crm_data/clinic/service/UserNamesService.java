package crm_data.clinic.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class UserNamesService implements UserNames {

	private final UserRepository users;

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> namesOf(Collection<UUID> userIds) {
		Map<UUID, String> names = new HashMap<>();
		if (userIds.isEmpty()) {
			return names;
		}
		users.findAllById(userIds).forEach(user -> names.put(user.getId(), user.getName()));
		return names;
	}
}
