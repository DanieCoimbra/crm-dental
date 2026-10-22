package crm_data.record.web;

/** URL assinada de leitura e validade em segundos. */
public record SignedUrlResponse(String url, long expiresIn) {

	/** Nunca imprime a URL. */
	@Override
	public String toString() {
		return "SignedUrlResponse[expiresIn=" + expiresIn + "]";
	}
}
