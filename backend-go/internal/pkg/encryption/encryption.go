package encryption

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
)

type Payload struct {
	IV    string `json:"iv"`
	Value string `json:"value"`
	MAC   string `json:"mac"`
}

var appKey []byte

// Init initializes the encryption package with the APP_KEY from env
func Init() error {
	keyStr := os.Getenv("APP_KEY")
	if keyStr == "" {
		return nil // No key, maybe tests or disabled
	}

	if strings.HasPrefix(keyStr, "base64:") {
		keyStr = strings.TrimPrefix(keyStr, "base64:")
	}

	decoded, err := base64.StdEncoding.DecodeString(keyStr)
	if err != nil {
		return fmt.Errorf("invalid base64 APP_KEY: %v", err)
	}

	if len(decoded) != 32 {
		return fmt.Errorf("invalid key length for AES-256: got %d, want 32", len(decoded))
	}

	appKey = decoded
	return nil
}

// Encrypt encrypts a string (compatible with Laravel Encrypter)
func Encrypt(value string) (string, error) {
	if len(appKey) == 0 {
		return value, nil
	}

	serialized := serializeString(value)

	iv := make([]byte, 16)
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return "", err
	}

	block, err := aes.NewCipher(appKey)
	if err != nil {
		return "", err
	}

	padded := pkcs7Pad([]byte(serialized), aes.BlockSize)
	ciphertext := make([]byte, len(padded))
	mode := cipher.NewCBCEncrypter(block, iv)
	mode.CryptBlocks(ciphertext, padded)

	ivBase64 := base64.StdEncoding.EncodeToString(iv)
	valueBase64 := base64.StdEncoding.EncodeToString(ciphertext)

	mac := generateMAC(ivBase64, valueBase64, appKey)

	payload := Payload{
		IV:    ivBase64,
		Value: valueBase64,
		MAC:   mac,
	}

	jsonPayload, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}

	return base64.StdEncoding.EncodeToString(jsonPayload), nil
}

// Decrypt decrypts a Laravel-encrypted string
func Decrypt(payloadStr string) (string, error) {
	if len(appKey) == 0 || payloadStr == "" {
		return payloadStr, nil
	}

	if !isBase64(payloadStr) {
		return payloadStr, nil
	}

	jsonBytes, err := base64.StdEncoding.DecodeString(payloadStr)
	if err != nil {
		return payloadStr, nil
	}

	var payload Payload
	if err := json.Unmarshal(jsonBytes, &payload); err != nil {
		return payloadStr, nil
	}

	if payload.IV == "" || payload.Value == "" || payload.MAC == "" {
		return payloadStr, nil
	}

	expectedMAC := generateMAC(payload.IV, payload.Value, appKey)
	if !hmac.Equal([]byte(payload.MAC), []byte(expectedMAC)) {
		return "", errors.New("mac verification failed")
	}

	iv, err := base64.StdEncoding.DecodeString(payload.IV)
	if err != nil {
		return "", err
	}
	ciphertext, err := base64.StdEncoding.DecodeString(payload.Value)
	if err != nil {
		return "", err
	}

	block, err := aes.NewCipher(appKey)
	if err != nil {
		return "", err
	}

	if len(ciphertext)%aes.BlockSize != 0 {
		return "", errors.New("ciphertext is not a multiple of the block size")
	}

	mode := cipher.NewCBCDecrypter(block, iv)
	plaintextPadded := make([]byte, len(ciphertext))
	mode.CryptBlocks(plaintextPadded, ciphertext)

	plaintext, err := pkcs7Unpad(plaintextPadded, aes.BlockSize)
	if err != nil {
		return "", err
	}

	return unserializeString(string(plaintext)), nil
}

func generateMAC(iv, value string, key []byte) string {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(iv + value))
	return hex.EncodeToString(mac.Sum(nil))
}

func pkcs7Pad(data []byte, blockSize int) []byte {
	padding := blockSize - len(data)%blockSize
	padtext := bytes.Repeat([]byte{byte(padding)}, padding)
	return append(data, padtext...)
}

func pkcs7Unpad(data []byte, blockSize int) ([]byte, error) {
	length := len(data)
	if length == 0 || length%blockSize != 0 {
		return nil, errors.New("invalid padding size")
	}
	padding := int(data[length-1])
	if padding == 0 || padding > blockSize {
		return nil, errors.New("invalid padding byte")
	}
	for i := 0; i < padding; i++ {
		if int(data[length-1-i]) != padding {
			return nil, errors.New("invalid padding")
		}
	}
	return data[:length-padding], nil
}

func serializeString(s string) string {
	return fmt.Sprintf("s:%d:\"%s\";", len(s), s)
}

func unserializeString(s string) string {
	if !strings.HasPrefix(s, "s:") {
		return s
	}

	parts := strings.SplitN(s, ":", 3)
	if len(parts) < 3 {
		return s
	}

	val := parts[2]
	if len(val) >= 2 && val[0] == '"' && val[len(val)-2:] == "\";" {
		return val[1 : len(val)-2]
	}

	return s
}

func isBase64(s string) bool {
	_, err := base64.StdEncoding.DecodeString(s)
	return err == nil
}
