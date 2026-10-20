package crm_data.shared.config;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import crm_data.shared.error.ProblemDetails;
import crm_data.shared.error.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;

/**
 * Escreve {@code application/problem+json} direto na resposta, para erros produzidos em filtros
 * (401/403 do Spring Security e rejeição de CORS), onde o {@code @RestControllerAdvice} não alcança.
 */
@Component
@RequiredArgsConstructor
public class ProblemResponseWriter {

	private final JsonMapper jsonMapper;

	public void write(HttpServletRequest request, HttpServletResponse response, ProblemTypes type, String detail)
			throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", type.typeUri().toString());
		body.put("title", type.title());
		body.put("status", type.status().value());
		body.put("detail", detail);
		URI instance = ProblemDetails.instanceOf(request);
		body.put("instance", instance != null ? instance.toString() : null);

		response.setStatus(type.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(jsonMapper.writeValueAsString(body));
	}
}
