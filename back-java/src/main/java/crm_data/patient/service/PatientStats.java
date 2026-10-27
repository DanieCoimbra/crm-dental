package crm_data.patient.service;

/** Interface pública do módulo {@code patient} para o dashboard: contagem agregada, sem expor repositório nem entidade. */
public interface PatientStats {

	/** Pacientes ativos da clínica do tenant (anonimizados são inativos). */
	long activeCount();
}
