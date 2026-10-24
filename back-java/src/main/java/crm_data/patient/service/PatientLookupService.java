package crm_data.patient.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.patient.repository.PatientRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class PatientLookupService implements PatientLookup {

	private final PatientRepository patients;

	@Override
	@Transactional(readOnly = true)
	public void requireExists(UUID patientId) {
		anonymized(patientId);
	}

	@Override
	@Transactional(readOnly = true)
	public void requireWritable(UUID patientId) {
		if (anonymized(patientId)) {
			throw new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.");
		}
	}

	@Override
	@Transactional(readOnly = true)
	public void requireSchedulable(UUID patientId) {
		List<PatientRepository.SchedulingState> state = patients.schedulingState(patientId);
		if (state.isEmpty()) {
			throw new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado.");
		}
		if (state.get(0).getAnonymized()) {
			throw new BusinessException(ProblemTypes.CONFLICT, "Paciente anonimizado.");
		}
		if (!state.get(0).getActive()) {
			throw new BusinessException(ProblemTypes.CONFLICT, "Paciente inativo.");
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, PatientBrief> briefs(Collection<UUID> patientIds) {
		if (patientIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, PatientBrief> briefs = new HashMap<>();
		for (PatientRepository.BriefRow row : patients.briefRows(patientIds)) {
			briefs.put(row.getId(), new PatientBrief(row.getId(), row.getName(), row.getPhone()));
		}
		return briefs;
	}

	private boolean anonymized(UUID patientId) {
		List<Boolean> flag = patients.anonymizedFlag(patientId);
		if (flag.isEmpty()) {
			throw new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado.");
		}
		return Boolean.TRUE.equals(flag.get(0));
	}
}
