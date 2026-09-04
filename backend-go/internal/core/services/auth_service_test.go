package services_test

import (
	"os"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func TestJWTTokenGenerationAndValidation(t *testing.T) {
	secret := "test_secret_key_portfolio_2026"
	os.Setenv("JWT_SECRET", secret)

	userID := uint(10)
	clinicID := uint(5)
	roleName := "owner"

	// Criar token JWT com claims esperados
	claims := jwt.MapClaims{
		"sub":       userID,
		"clinic_id": clinicID,
		"role":      roleName,
		"exp":       time.Now().Add(time.Hour * 1).Unix(),
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, err := token.SignedString([]byte(secret))
	if err != nil {
		t.Fatalf("Erro ao assinar token: %v", err)
	}

	// Validar e parsear token
	parsedToken, err := jwt.Parse(tokenString, func(t *jwt.Token) (interface{}, error) {
		return []byte(secret), nil
	})

	if err != nil || !parsedToken.Valid {
		t.Fatalf("Token esperado como válido, erro: %v", err)
	}

	parsedClaims, ok := parsedToken.Claims.(jwt.MapClaims)
	if !ok {
		t.Fatalf("Claims inválidos")
	}

	if uint(parsedClaims["sub"].(float64)) != userID {
		t.Errorf("Esperado sub=%d, obtido=%v", userID, parsedClaims["sub"])
	}
	if uint(parsedClaims["clinic_id"].(float64)) != clinicID {
		t.Errorf("Esperado clinic_id=%d, obtido=%v", clinicID, parsedClaims["clinic_id"])
	}
	if parsedClaims["role"].(string) != roleName {
		t.Errorf("Esperado role=%s, obtido=%v", roleName, parsedClaims["role"])
	}
}
