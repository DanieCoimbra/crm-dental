package crm_data.clinic.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.domain.Clinic;
import crm_data.auth.repository.ClinicRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/** Lê a clínica do token a cada chamada (sem cache: o dentista pode editar os dados da clínica). */
@Service
@RequiredArgsConstructor
class ClinicIdentityService implements ClinicIdentity {

	private final ClinicRepository clinics;

	@Override
	@Transactional(readOnly = true)
	public ClinicInfo current() {
		Clinic clinic = clinics.findById(TenantContext.clinicId())
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Clínica não encontrada."));
		return new ClinicInfo(clinic.getName(), clinic.getAddress(), clinic.getPhone());
	}
}
