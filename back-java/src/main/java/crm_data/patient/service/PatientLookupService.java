package crm_data.patient.service;

import java.util.List;
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

	private boolean anonymized(UUID patientId) {
		List<Boolean> flag = patients.anonymizedFlag(patientId);
		if (flag.isEmpty()) {
			throw new BusinessException(ProblemTypes.NOT_FOUND, "Paciente não encontrado.");
		}
		return Boolean.TRUE.equals(flag.get(0));
	}
}
