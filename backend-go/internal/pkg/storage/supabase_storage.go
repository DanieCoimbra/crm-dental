package storage

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

// UploadToSupabase envia os bytes/stream de um arquivo para o Supabase Storage
// e retorna a URL pública gerada.
func UploadToSupabase(bucket string, fileName string, content io.Reader, size int64, contentType string) (string, error) {
	supabaseUrl := os.Getenv("SUPABASE_URL")
	supabaseKey := os.Getenv("SUPABASE_KEY")

	if supabaseUrl == "" || supabaseKey == "" {
		return "", fmt.Errorf("variáveis SUPABASE_URL ou SUPABASE_KEY não configuradas")
	}

	uploadUrl := fmt.Sprintf("%s/storage/v1/object/%s/%s", supabaseUrl, bucket, fileName)

	req, err := http.NewRequest("POST", uploadUrl, content)
	if err != nil {
		return "", fmt.Errorf("erro ao criar requisição de upload: %w", err)
	}

	req.ContentLength = size
	req.Header.Set("Authorization", "Bearer "+supabaseKey)
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}

	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("erro na comunicação com Supabase Storage: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		bodyBytes, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("Supabase Storage retornou erro HTTP %d: %s", resp.StatusCode, string(bodyBytes))
	}

	publicUrl := fmt.Sprintf("%s/storage/v1/object/public/%s/%s", supabaseUrl, bucket, fileName)
	return publicUrl, nil
}
