package crm_data.clinic.service;

import java.time.DateTimeException;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.repository.ClinicRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/** Lê {@code clinics.timezone} da clínica do token a cada chamada (sem cache: o dentista pode trocar o fuso). */
@Service
@RequiredArgsConstructor
class ClinicZonesService implements ClinicZones {

	private final ClinicRepository clinics;

	@Override
	@Transactional(readOnly = true)
	public ZoneId zone() {
		String timezone = clinics.findById(TenantContext.clinicId())
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Clínica não encontrada."))
				.getTimezone();
		try {
			return ZoneId.of(timezone);
		}
		catch (DateTimeException ex) {
			throw new IllegalStateException("Fuso da clínica inválido.");
		}
	}
}
