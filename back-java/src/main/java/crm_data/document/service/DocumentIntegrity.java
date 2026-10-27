package crm_data.document.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import crm_data.document.domain.ClinicalDocument;
import crm_data.document.domain.DocumentType;
import crm_data.shared.error.IntegrityException;

/**
 * Selo de integridade do documento: {@code sha256} (hex minúsculo) de
 * {@code v1|TYPE|patient_id|issued_on|rest_days|target_specialty|content}, com {@code content} = o texto CIFRADO gravado
 * (Base64) e campos nulos = vazio. Regra pura, sem Spring nem banco.
 */
public final class DocumentIntegrity {

	private static final String VERSION = "v1";

	private DocumentIntegrity() {
	}

	public static String compute(DocumentType type, UUID patientId, LocalDate issuedOn, Short restDays, String targetSpecialty,
			String cipherText) {
		String canonical = String.join("|", VERSION, type.name(), patientId.toString().toLowerCase(Locale.ROOT),
				issuedOn.toString(), restDays == null ? "" : restDays.toString(), targetSpecialty == null ? "" : targetSpecialty,
				cipherText == null ? "" : cipherText);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Confere o documento lido contra o selo gravado, em tempo constante. Documento anonimizado não tem conteúdo: não há o que
	 * conferir.
	 *
	 * @throws IntegrityException se divergir (a mensagem leva só o id do documento)
	 */
	public static void verify(ClinicalDocument doc) {
		if (doc.getAnonymizedAt() != null) {
			return;
		}
		String expected = compute(doc.getType(), doc.getPatientId(), doc.getIssuedOn(), doc.getRestDays(),
				doc.getTargetSpecialty(), doc.getContent());
		boolean same = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				String.valueOf(doc.getSha256()).getBytes(StandardCharsets.UTF_8));
		if (!same) {
			throw new IntegrityException("documento id=" + doc.getId());
		}
	}
}
