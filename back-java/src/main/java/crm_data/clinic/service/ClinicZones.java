package crm_data.clinic.service;

import java.time.ZoneId;

/**
 * Interface pública do módulo {@code clinic} para a agenda: o fuso da clínica do token. É a única fonte de "dia",
 * "semana", expediente e "iniciar no dia"; nenhuma regra da agenda usa uma constante de fuso.
 */
public interface ClinicZones {

	/**
	 * @return o fuso da clínica da transação
	 * @throws IllegalStateException se o valor gravado não for um fuso válido (defeito de dados: vira 500)
	 */
	ZoneId zone();
}
