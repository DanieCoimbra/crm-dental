package crm_data.patient.web;

import java.time.LocalDate;

import crm_data.patient.domain.Sex;
import crm_data.patient.service.PatientRules;
import crm_data.patient.service.PatientService.PatientDetails;
import crm_data.patient.web.PatientConstraints.ConsistentPatient;
import crm_data.patient.web.PatientConstraints.PatientFields;
import crm_data.patient.web.PatientConstraints.ValidBirthDate;
import crm_data.patient.web.PatientConstraints.ValidCpf;
import crm_data.shared.web.RequestText;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpos das rotas {@code /patients}. Nenhum aceita {@code id}, {@code clinicId}, {@code createdBy}, {@code active}
 * (fora do PATCH) nem {@code anonymizedAt}: extras do JSON são ignorados. A normalização acontece na construção
 * (CPF e telefones só com dígitos, e-mail em minúsculas, textos sem espaços nas pontas) e antes da validação.
 */
public final class PatientRequests {

	private static final String PHONE_MESSAGE = "deve ter de 10 a 13 dígitos";
	private static final String PHONE_REGEX = "^[0-9]{10,13}$";

	private PatientRequests() {
	}

	@ConsistentPatient
	public record CreatePatientRequest(
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres") String name,
			@ValidCpf String cpf,
			@NotNull(message = "obrigatório") @ValidBirthDate LocalDate birthDate,
			Sex sex,
			@Size(max = 100, message = "deve ter no máximo 100 caracteres") String profession,
			@NotBlank(message = "obrigatório") @Pattern(regexp = PHONE_REGEX, message = PHONE_MESSAGE) String phone,
			@Email(message = "e-mail inválido") @Size(max = 254, message = "deve ter no máximo 254 caracteres") String email,
			@Size(max = 255, message = "deve ter no máximo 255 caracteres") String address,
			@Size(max = 100, message = "deve ter no máximo 100 caracteres") String insuranceName,
			@Size(max = 40, message = "deve ter no máximo 40 caracteres") String insuranceNumber,
			@Size(max = 120, message = "deve ter no máximo 120 caracteres") String acquaintanceName,
			@Pattern(regexp = PHONE_REGEX, message = PHONE_MESSAGE) String acquaintancePhone)
			implements PatientFields {

		public CreatePatientRequest {
			name = RequestText.strip(name);
			cpf = PatientRules.normalizeCpf(RequestText.blankToNull(cpf));
			sex = sex != null ? sex : Sex.NOT_INFORMED;
			profession = RequestText.blankToNull(profession);
			phone = PatientRules.normalizePhone(RequestText.strip(phone));
			email = RequestText.blankToNull(email) == null ? null : RequestText.normalizeEmail(email);
			address = RequestText.blankToNull(address);
			insuranceName = RequestText.blankToNull(insuranceName);
			insuranceNumber = RequestText.blankToNull(insuranceNumber);
			acquaintanceName = RequestText.blankToNull(acquaintanceName);
			acquaintancePhone = PatientRules.normalizePhone(RequestText.blankToNull(acquaintancePhone));
		}

		public PatientDetails toDetails() {
			return new PatientDetails(name, cpf, birthDate, sex, profession, phone, email, address, insuranceName,
					insuranceNumber, acquaintanceName, acquaintancePhone);
		}

		/** Nunca imprime dados pessoais. */
		@Override
		public String toString() {
			return "CreatePatientRequest[]";
		}
	}

	/** Igual ao cadastro, mais a {@code version} lida pelo cliente (controle otimista). */
	@ConsistentPatient
	public record UpdatePatientRequest(
			@NotBlank(message = "obrigatório") @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres") String name,
			@ValidCpf String cpf,
			@NotNull(message = "obrigatório") @ValidBirthDate LocalDate birthDate,
			Sex sex,
			@Size(max = 100, message = "deve ter no máximo 100 caracteres") String profession,
			@NotBlank(message = "obrigatório") @Pattern(regexp = PHONE_REGEX, message = PHONE_MESSAGE) String phone,
			@Email(message = "e-mail inválido") @Size(max = 254, message = "deve ter no máximo 254 caracteres") String email,
			@Size(max = 255, message = "deve ter no máximo 255 caracteres") String address,
			@Size(max = 100, message = "deve ter no máximo 100 caracteres") String insuranceName,
			@Size(max = 40, message = "deve ter no máximo 40 caracteres") String insuranceNumber,
			@Size(max = 120, message = "deve ter no máximo 120 caracteres") String acquaintanceName,
			@Pattern(regexp = PHONE_REGEX, message = PHONE_MESSAGE) String acquaintancePhone,
			@NotNull(message = "obrigatório") @Min(value = 0, message = "deve ser maior ou igual a 0") Long version)
			implements PatientFields {

		public UpdatePatientRequest {
			name = RequestText.strip(name);
			cpf = PatientRules.normalizeCpf(RequestText.blankToNull(cpf));
			sex = sex != null ? sex : Sex.NOT_INFORMED;
			profession = RequestText.blankToNull(profession);
			phone = PatientRules.normalizePhone(RequestText.strip(phone));
			email = RequestText.blankToNull(email) == null ? null : RequestText.normalizeEmail(email);
			address = RequestText.blankToNull(address);
			insuranceName = RequestText.blankToNull(insuranceName);
			insuranceNumber = RequestText.blankToNull(insuranceNumber);
			acquaintanceName = RequestText.blankToNull(acquaintanceName);
			acquaintancePhone = PatientRules.normalizePhone(RequestText.blankToNull(acquaintancePhone));
		}

		public PatientDetails toDetails() {
			return new PatientDetails(name, cpf, birthDate, sex, profession, phone, email, address, insuranceName,
					insuranceNumber, acquaintanceName, acquaintancePhone);
		}

		/** Nunca imprime dados pessoais. */
		@Override
		public String toString() {
			return "UpdatePatientRequest[]";
		}
	}

	public record SetActiveRequest(@NotNull(message = "obrigatório") Boolean active) {
	}
}
