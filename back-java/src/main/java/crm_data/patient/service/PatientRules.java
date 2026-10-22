package crm_data.patient.service;

import java.time.LocalDate;
import java.time.ZoneId;

/** Regras de formato de paciente que o Bean Validation e o service compartilham (CPF, telefone, nascimento). */
public final class PatientRules {

	/** Fuso da clínica: define o que é "hoje" para a data de nascimento. */
	public static final ZoneId CLINIC_ZONE = ZoneId.of("America/Sao_Paulo");
	public static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);

	private PatientRules() {
	}

	/** Remove a máscara do CPF ({@code .}, {@code -} e espaços); outros caracteres ficam e reprovam na validação. */
	public static String normalizeCpf(String cpf) {
		return cpf == null ? null : cpf.replaceAll("[.\\-\\s]", "");
	}

	/** Remove a máscara do telefone (espaços, parênteses, {@code .}, {@code -} e {@code +}). */
	public static String normalizePhone(String phone) {
		return phone == null ? null : phone.replaceAll("[\\s().+\\-]", "");
	}

	/** 11 dígitos, não todos iguais, com os dois dígitos verificadores corretos. */
	public static boolean isValidCpf(String cpf) {
		if (cpf == null || !cpf.matches("[0-9]{11}") || cpf.chars().distinct().count() == 1) {
			return false;
		}
		return checkDigit(cpf, 9) == cpf.charAt(9) - '0' && checkDigit(cpf, 10) == cpf.charAt(10) - '0';
	}

	private static int checkDigit(String cpf, int length) {
		int sum = 0;
		for (int i = 0; i < length; i++) {
			sum += (cpf.charAt(i) - '0') * (length + 1 - i);
		}
		int rest = (sum * 10) % 11;
		return rest == 10 ? 0 : rest;
	}

	/** Entre 1900-01-01 e hoje (no fuso da clínica). */
	public static boolean isValidBirthDate(LocalDate date, LocalDate today) {
		return date != null && !date.isBefore(MIN_BIRTH_DATE) && !date.isAfter(today);
	}

	public static LocalDate today() {
		return LocalDate.now(CLINIC_ZONE);
	}

	/** Texto de busca por CPF: só dígitos, {@code .}, {@code -} e espaços, com exatamente 11 dígitos. */
	public static boolean looksLikeCpfQuery(String q) {
		return q.matches("[0-9.\\-\\s]+") && normalizeCpf(q).length() == 11;
	}
}
