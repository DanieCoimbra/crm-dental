package crm_data.clinic.service;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crm_data.auth.domain.Clinic;
import crm_data.auth.repository.ClinicRepository;
import crm_data.shared.error.BusinessException;
import crm_data.shared.error.ProblemTypes;
import crm_data.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/** Leitura e edição da clínica DO TENANT (o id vem do token, nunca do cliente). */
@Service
@RequiredArgsConstructor
public class ClinicService {

	/**
	 * Campos editáveis (opcionais já normalizados: vazio vira {@code null}). {@code timezone} ausente mantém o fuso
	 * atual.
	 */
	public record ClinicData(String name, String document, String phone, String email, String address,
			String timezone) {
	}

	private final ClinicRepository clinics;
	private final ApplicationEventPublisher events;

	@Transactional(readOnly = true)
	public Clinic get() {
		return load();
	}

	/**
	 * @param expectedVersion versão que o cliente leu; diferente da atual significa edição concorrente
	 * @throws BusinessException {@code conflict} (409) se a versão estiver desatualizada
	 */
	@PreAuthorize("hasRole('DENTIST_ADMIN')")
	@Transactional
	public Clinic update(ClinicData data, long expectedVersion) {
		Clinic clinic = load();
		if (!Objects.equals(clinic.getVersion(), expectedVersion)) {
			throw new BusinessException(ProblemTypes.CONFLICT,
					"Os dados da clínica foram alterados por outra pessoa. Recarregue e tente novamente.");
		}
		clinic.setName(data.name());
		clinic.setDocument(data.document());
		clinic.setPhone(data.phone());
		clinic.setEmail(data.email());
		clinic.setAddress(data.address());
		if (data.timezone() != null) {
			clinic.setTimezone(data.timezone());
		}
		Clinic saved = clinics.saveAndFlush(clinic); // @Version também protege a corrida entre dois PUT com a mesma versão
		events.publishEvent(new ClinicEvents.Updated(saved.getId()));
		return saved;
	}

	private Clinic load() {
		return clinics.findById(TenantContext.clinicId())
				.orElseThrow(() -> new BusinessException(ProblemTypes.NOT_FOUND, "Clínica não encontrada."));
	}
}
