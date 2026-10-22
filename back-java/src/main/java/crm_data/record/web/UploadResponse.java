package crm_data.record.web;

import java.util.UUID;

/** Resposta do upload: só o id (a recepção não pode ver o que anexou). */
public record UploadResponse(UUID id) {
}
